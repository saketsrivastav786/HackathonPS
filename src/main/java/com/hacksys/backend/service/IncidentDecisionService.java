package com.hacksys.backend.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class IncidentDecisionService {

    private static final Logger log = LoggerFactory.getLogger(IncidentDecisionService.class);

    private final SlackNotificationService slackNotificationService;
    private final int incidentWindowMs;
    private final int traceIncidentThreshold;
    private final int serviceErrorThreshold;

    private final ConcurrentLinkedDeque<IncidentEvent> recentEvents = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<String, Integer> traceCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> serviceErrorCounts = new ConcurrentHashMap<>();
    private final Set<String> traceAlerted = ConcurrentHashMap.newKeySet();
    private final Set<String> serviceAlerted = ConcurrentHashMap.newKeySet();

    public IncidentDecisionService(SlackNotificationService slackNotificationService,
                                   @Value("${app.alerting.incident-window-ms:60000}") int incidentWindowMs,
                                   @Value("${app.alerting.trace-incident-threshold:3}") int traceIncidentThreshold,
                                   @Value("${app.alerting.service-error-threshold:2}") int serviceErrorThreshold) {
        this.slackNotificationService = slackNotificationService;
        this.incidentWindowMs = incidentWindowMs;
        this.traceIncidentThreshold = traceIncidentThreshold;
        this.serviceErrorThreshold = serviceErrorThreshold;
    }

    public void evaluate(ObjectNode logEntry) {
        String level = logEntry.path("level").asText("INFO");
        if (!"WARN".equals(level) && !"ERROR".equals(level)) {
            return;
        }

        String traceId = logEntry.path("trace_id").asText("unknown");
        String service = logEntry.path("service").asText("unknown");
        String errorType = logEntry.path("error_type").asText("NONE");
        String message = logEntry.path("message").asText("(no message)");
        boolean isError = "ERROR".equals(level);

        Instant now = Instant.now();
        cleanupOldEvents(now);

        recentEvents.addLast(new IncidentEvent(traceId, service, level, errorType, message, now));
        traceCounts.merge(traceId, 1, Integer::sum);
        if (isError) {
            serviceErrorCounts.merge(service, 1, Integer::sum);
        }

        if (traceCounts.getOrDefault(traceId, 0) >= traceIncidentThreshold && traceAlerted.add(traceId)) {
            String summary = buildSummaryForTrace(traceId);
            slackNotificationService.sendIncidentSummary("Incident detected for trace " + traceId, summary);
        }

        if (isError && serviceErrorCounts.getOrDefault(service, 0) >= serviceErrorThreshold && serviceAlerted.add(service)) {
            String summary = buildSummaryForService(service);
            slackNotificationService.sendIncidentSummary("Service-level error escalation for " + service, summary);
        }

        if (shouldRecommendSelfHealing(message)) {
            slackNotificationService.sendIncidentSummary("Self-healing recommended", "AI decision engine recommends a remediation action for service=" + service + " trace=" + traceId + ". Message: " + message);
        }

        slackNotificationService.sendAlert(logEntry);
    }

    private void cleanupOldEvents(Instant now) {
        while (!recentEvents.isEmpty()) {
            IncidentEvent oldest = recentEvents.peekFirst();
            if (oldest == null || Duration.between(oldest.timestamp, now).toMillis() <= incidentWindowMs) {
                break;
            }
            recentEvents.pollFirst();
            traceCounts.computeIfPresent(oldest.traceId, (k, v) -> v <= 1 ? null : v - 1);
            if ("ERROR".equals(oldest.level)) {
                serviceErrorCounts.computeIfPresent(oldest.service, (k, v) -> v <= 1 ? null : v - 1);
            }
            traceAlerted.remove(oldest.traceId);
            serviceAlerted.remove(oldest.service);
        }
    }

    private boolean shouldRecommendSelfHealing(String message) {
        String normalized = message.toLowerCase();
        return normalized.contains("timeout")
            || normalized.contains("unavailable")
            || normalized.contains("circuit")
            || normalized.contains("degraded")
            || normalized.contains("stale")
            || normalized.contains("orphaned")
            || normalized.contains("mismatch")
            || normalized.contains("negative stock");
    }

    private String buildSummaryForTrace(String traceId) {
        List<String> lines = new ArrayList<>();
        recentEvents.stream()
            .filter(event -> traceId.equals(event.traceId))
            .limit(6)
            .forEach(event -> lines.add(event.level + " " + event.errorType + ": " + event.message));
        if (lines.isEmpty()) {
            return "Trace " + traceId + " has reached incident threshold with recent WARN/ERROR logs.";
        }
        return "Trace " + traceId + " has " + traceCounts.getOrDefault(traceId, 0) + " recent WARN/ERROR events:\n" + String.join("\n", lines);
    }

    private String buildSummaryForService(String service) {
        List<String> lines = new ArrayList<>();
        recentEvents.stream()
            .filter(event -> service.equals(event.service) && "ERROR".equals(event.level))
            .limit(6)
            .forEach(event -> lines.add(event.traceId + ": " + event.errorType + " - " + event.message));
        return "Service " + service + " has " + serviceErrorCounts.getOrDefault(service, 0) + " recent ERROR events. Recent examples:\n" + String.join("\n", lines);
    }

    private static class IncidentEvent {
        private final String traceId;
        private final String service;
        private final String level;
        private final String errorType;
        private final String message;
        private final Instant timestamp;

        public IncidentEvent(String traceId, String service, String level, String errorType, String message, Instant timestamp) {
            this.traceId = traceId;
            this.service = service;
            this.level = level;
            this.errorType = errorType;
            this.message = message;
            this.timestamp = timestamp;
        }
    }
}
