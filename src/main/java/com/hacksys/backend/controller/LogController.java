package com.hacksys.backend.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hacksys.backend.util.LogStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/logs")
public class LogController {

    private final LogStore logStore;

    public LogController(LogStore logStore) {
        this.logStore = logStore;
    }

    /**
     * GET /logs
     * Optional filters: ?trace_id=...&dedupe=true
     */
    @GetMapping
    public List<ObjectNode> getLogs(
            @RequestParam(required = false) String trace_id,
            @RequestParam(defaultValue = "false") boolean dedupe) {

        if (trace_id != null && !trace_id.isBlank()) {
            List<ObjectNode> results = logStore.getByTraceId(trace_id);
            return dedupe ? dedupe(results) : results;
        }

        return dedupe ? logStore.getDeduplicated() : logStore.getAll();
    }

    private List<ObjectNode> dedupe(List<ObjectNode> logs) {
        Set<String> seen = new HashSet<>();
        List<ObjectNode> deduped = new ArrayList<>();

        for (ObjectNode node : logs) {
            String fingerprint = node.path("level").asText("INFO") + "|"
                    + node.path("service").asText("unknown") + "|"
                    + node.path("trace_id").asText("unknown") + "|"
                    + node.path("error_type").asText("NONE") + "|"
                    + node.path("message").asText("") + "|"
                    + node.path("class").asText("unknown");
            if (seen.add(fingerprint)) {
                deduped.add(node);
            }
        }
        return deduped;
    }
}
