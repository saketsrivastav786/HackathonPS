package com.hacksys.backend.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hacksys.backend.service.IncidentDecisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * Thread-safe in-memory log store.
 * Backs the /logs API endpoint.
 * Capped at 2000 entries — older entries silently dropped.
 */
@Component
public class LogStore {

    private static final Logger log = LoggerFactory.getLogger(LogStore.class);
    private static final int MAX_ENTRIES = 2000;
    private static final long DUPLICATE_WINDOW_MS = 5000;
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Random jitter = new Random();

    private static final CopyOnWriteArrayList<ObjectNode> entries = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedDeque<RecentFingerprint> recentFingerprints = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<String, Instant> recentFingerprintIndex = new ConcurrentHashMap<>();
    private final Object fingerprintLock = new Object();

    private final IncidentDecisionService incidentDecisionService;

    public LogStore(IncidentDecisionService incidentDecisionService) {
        this.incidentDecisionService = incidentDecisionService;
    }

    public void append(String level, String service, String traceId,
                       String errorType, String message, String className) {
        appendWithSkew(level, service, traceId, errorType, message, className, 0);
    }

    public void appendWithSkew(String level, String service, String traceId,
                               String errorType, String message, String className, int skewMs) {
        ObjectNode node = mapper.createObjectNode();
        Instant ts = Instant.now().plusMillis(skewMs);
        node.put("timestamp", ts.toString());
        node.put("trace_id", traceId != null ? traceId : "unknown");
        node.put("service", service != null ? service : "unknown");
        node.put("class", className != null ? className : "unknown");
        node.put("error_type", errorType != null ? errorType : "NONE");
        node.put("message", message);
        node.put("level", level);

        String fingerprint = createFingerprint(level, service, traceId, errorType, message, className);
        if (isDuplicateWithinWindow(fingerprint, ts)) {
            return;
        }

        entries.add(node);
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }

        incidentDecisionService.evaluate(node);
    }

    public List<ObjectNode> getAll() {
        return new ArrayList<>(entries);
    }

    public List<ObjectNode> getDeduplicated() {
        Set<String> seen = new HashSet<>();
        List<ObjectNode> unique = new ArrayList<>();

        for (ObjectNode node : entries) {
            String fingerprint = createFingerprint(
                    node.path("level").asText("INFO"),
                    node.path("service").asText("unknown"),
                    node.path("trace_id").asText("unknown"),
                    node.path("error_type").asText("NONE"),
                    node.path("message").asText(""),
                    node.path("class").asText("unknown")
            );
            if (seen.add(fingerprint)) {
                unique.add(node);
            }
        }
        return unique;
    }

    public List<ObjectNode> getByTraceId(String traceId) {
        return entries.stream()
                .filter(e -> traceId.equals(e.path("trace_id").asText()))
                .collect(Collectors.toList());
    }

    public void clear() {
        entries.clear();
        recentFingerprintIndex.clear();
        recentFingerprints.clear();
    }

    // Convenience shorthands used by services
    public void info(String service, String traceId, String message) {
        String caller = getCaller();
        append("INFO", service, traceId, null, message, caller);
    }

    public void warn(String service, String traceId, String errorType, String message) {
        String caller = getCaller();
        append("WARN", service, traceId, errorType, message, caller);
    }

    public void error(String service, String traceId, String errorType, String message) {
        String caller = getCaller();
        append("ERROR", service, traceId, errorType, message, caller);
    }

    // Skewed shorthands — for async paths where clock drift is expected
    public void skewInfo(String service, String traceId, String message) {
        String caller = getCaller();
        int skew = jitter.nextInt(501) - 250; // ±250ms
        appendWithSkew("INFO", service, traceId, null, message, caller, skew);
    }

    public void skewWarn(String service, String traceId, String errorType, String message) {
        String caller = getCaller();
        int skew = jitter.nextInt(501) - 250;
        appendWithSkew("WARN", service, traceId, errorType, message, caller, skew);
    }

    public void skewError(String service, String traceId, String errorType, String message) {
        String caller = getCaller();
        int skew = jitter.nextInt(501) - 250;
        appendWithSkew("ERROR", service, traceId, errorType, message, caller, skew);
    }

    private boolean isDuplicateWithinWindow(String fingerprint, Instant now) {
        synchronized (fingerprintLock) {
            Instant cutoff = now.minusMillis(DUPLICATE_WINDOW_MS);
            while (!recentFingerprints.isEmpty() && recentFingerprints.peekFirst().timestamp.isBefore(cutoff)) {
                RecentFingerprint expired = recentFingerprints.pollFirst();
                if (expired != null) {
                    recentFingerprintIndex.remove(expired.fingerprint, expired.timestamp);
                }
            }

            Instant previous = recentFingerprintIndex.get(fingerprint);
            if (previous != null && Math.abs(Duration.between(previous, now).toMillis()) <= DUPLICATE_WINDOW_MS) {
                return true;
            }

            recentFingerprintIndex.put(fingerprint, now);
            recentFingerprints.addLast(new RecentFingerprint(fingerprint, now));
            return false;
        }
    }

    private String createFingerprint(String level, String service, String traceId,
                                     String errorType, String message, String className) {
        return String.join("|",
                level != null ? level : "",
                service != null ? service : "",
                traceId != null ? traceId : "",
                errorType != null ? errorType : "",
                message != null ? message : "",
                className != null ? className : ""
        );
    }

    private String getCaller() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement el : stack) {
            String cls = el.getClassName();
            if (!cls.contains("LogStore") && !cls.contains("Thread") && cls.contains("hacksys")) {
                return cls.substring(cls.lastIndexOf('.') + 1);
            }
        }
        return "unknown";
    }

    private static class RecentFingerprint {
        private final String fingerprint;
        private final Instant timestamp;

        private RecentFingerprint(String fingerprint, Instant timestamp) {
            this.fingerprint = fingerprint;
            this.timestamp = timestamp;
        }
    }
}
