package com.usora.compliance.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DomainEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public DomainEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishComplianceChecked(String caseId, String tenantId, String decision) {
        publish("compliance.checked", Map.of(
                "eventId", UUID.randomUUID().toString(),
                "eventType", "compliance.checked",
                "caseId", caseId,
                "tenantId", tenantId,
                "decision", decision,
                "timestamp", Instant.now().toString()
        ));
    }

    public void publishRuleUpdated(String ruleId, Integer newVersion, String tenantId, String updatedBy) {
        publish("compliance.rule.updated", Map.of(
                "eventId", UUID.randomUUID().toString(),
                "eventType", "compliance.rule.updated",
                "ruleId", ruleId,
                "newVersion", newVersion,
                "tenantId", tenantId,
                "updatedBy", updatedBy,
                "timestamp", Instant.now().toString()
        ));
    }

    public void publishReportGenerated(String reportId, String format, String tenantId) {
        publish("compliance.report.generated", Map.of(
                "eventId", UUID.randomUUID().toString(),
                "eventType", "compliance.report.generated",
                "reportId", reportId,
                "format", format,
                "tenantId", tenantId,
                "timestamp", Instant.now().toString()
        ));
    }

    public void publishEvidenceSubmitted(String evidenceId, String caseId, String tenantId) {
        publish("compliance.evidence.submitted", Map.of(
                "eventId", UUID.randomUUID().toString(),
                "eventType", "compliance.evidence.submitted",
                "evidenceId", evidenceId,
                "caseId", caseId,
                "tenantId", tenantId,
                "timestamp", Instant.now().toString()
        ));
    }

    public void publishSanctionsStale(String tenantId, String listName) {
        publish("compliance.sanctions.stale", Map.of(
                "eventId", UUID.randomUUID().toString(),
                "eventType", "compliance.sanctions.stale",
                "tenantId", tenantId,
                "listName", listName,
                "timestamp", Instant.now().toString()
        ));
    }

    private void publish(String topic, Map<String, Object> event) {
        try {
            var json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, json).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish event {}: {}", topic, ex.getMessage());
                } else {
                    log.debug("Published event {} to topic {}", event.get("eventId"), topic);
                }
            });
        } catch (Exception e) {
            log.error("Failed to serialize event for topic {}: {}", topic, e.getMessage());
        }
    }
}
