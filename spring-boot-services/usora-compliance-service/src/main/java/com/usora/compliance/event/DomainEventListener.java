package com.usora.compliance.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DomainEventListener {

    private static final Logger log = LoggerFactory.getLogger(DomainEventListener.class);

    private final ObjectMapper objectMapper;

    public DomainEventListener(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "compliance.rule.updated", groupId = "compliance-service")
    public void handleRuleUpdated(String message) {
        try {
            var event = objectMapper.readValue(message, new TypeReference<Map<String, Object>>() {});
            log.info("Rule update event received: ruleId={}, version={}", event.get("ruleId"), event.get("newVersion"));
        } catch (Exception e) {
            log.error("Failed to process rule updated event: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "compliance.evidence.submitted", groupId = "compliance-service")
    public void handleEvidenceSubmitted(String message) {
        try {
            var event = objectMapper.readValue(message, new TypeReference<Map<String, Object>>() {});
            log.info("Evidence submitted event: evidenceId={}, caseId={}", event.get("evidenceId"), event.get("caseId"));
        } catch (Exception e) {
            log.error("Failed to process evidence submitted event: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "compliance.sanctions.stale", groupId = "compliance-service")
    public void handleSanctionsStale(String message) {
        try {
            var event = objectMapper.readValue(message, new TypeReference<Map<String, Object>>() {});
            log.warn("Sanctions list stale for tenant {}: {}", event.get("tenantId"), event.get("listName"));
        } catch (Exception e) {
            log.error("Failed to process sanctions stale event: {}", e.getMessage());
        }
    }
}
