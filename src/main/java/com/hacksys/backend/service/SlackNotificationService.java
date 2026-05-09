package com.hacksys.backend.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class SlackNotificationService {

    private static final Logger log = LoggerFactory.getLogger(SlackNotificationService.class);

    private final RestTemplate restTemplate;
    private final String slackWebhookUrl;

    public SlackNotificationService(RestTemplate restTemplate,
                                    @Value("${app.alerting.slack-webhook-url:}") String slackWebhookUrl) {
        this.restTemplate = restTemplate;
        this.slackWebhookUrl = slackWebhookUrl;
    }

    public void sendAlert(ObjectNode logEntry) {
        if (slackWebhookUrl == null || slackWebhookUrl.isBlank()) {
            return;
        }

        String level = logEntry.path("level").asText("INFO");
        String errorType = logEntry.path("error_type").asText("NONE");
        if (!"ERROR".equals(level) && !("WARN".equals(level) && !"NONE".equals(errorType))) {
            return;
        }

        String text = buildSlackMessage(logEntry);
        postToSlack(text);
    }

    public void sendIncidentSummary(String title, String summary) {
        if (slackWebhookUrl == null || slackWebhookUrl.isBlank()) {
            return;
        }
        String formatted = "*" + title + "*\n" + summary;
        postToSlack(formatted);
    }

    private String buildSlackMessage(ObjectNode entry) {
        String level = entry.path("level").asText("INFO");
        String service = entry.path("service").asText("unknown");
        String traceId = entry.path("trace_id").asText("unknown");
        String errorType = entry.path("error_type").asText("NONE");
        String message = entry.path("message").asText("(no message)");
        String timestamp = entry.path("timestamp").asText("unknown");

        StringBuilder builder = new StringBuilder();
        builder.append("*").append(level).append(" alert*");
        builder.append("\nService: ").append(service);
        builder.append("\nTrace: ").append(traceId);
        if (!"NONE".equals(errorType)) {
            builder.append("\nType: ").append(errorType);
        }
        builder.append("\nMessage: ").append(message);
        builder.append("\nTime: ").append(timestamp);
        return builder.toString();
    }

    private void postToSlack(String text) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, String> body = new HashMap<>();
            body.put("text", text);
            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
            restTemplate.postForEntity(slackWebhookUrl, request, String.class);
        } catch (Exception e) {
            log.warn("Slack notification failed: {}", e.getMessage());
        }
    }
}
