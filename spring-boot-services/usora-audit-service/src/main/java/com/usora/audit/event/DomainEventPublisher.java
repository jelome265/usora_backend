package com.usora.audit.event;

import com.usora.audit.entity.AuditEvent;
import com.usora.audit.entity.TamperAlert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DomainEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public DomainEventPublisher(@Qualifier("kafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Async("auditTaskExecutor")
    public void publishAuditEvent(AuditEvent event) {
        try {
            kafkaTemplate.send("audit-events-persisted", event.getTenantId(), event);
            log.debug("Published audit event {} to Kafka", event.getId());
        } catch (Exception e) {
            log.error("Failed to publish audit event to Kafka: {}", e.getMessage(), e);
        }
    }

    @Async("auditTaskExecutor")
    public void publishAnchorEvent(String tenantId, String merkleRoot, Instant intervalStart,
                                    Instant intervalEnd, String signature) {
        try {
            AnchorEvent anchor = new AnchorEvent(tenantId, merkleRoot, intervalStart, intervalEnd, signature, Instant.now());
            kafkaTemplate.send("audit-merkle-anchors", tenantId, anchor);
            log.info("Published Merkle anchor for tenant {}: root={}", tenantId, merkleRoot);
        } catch (Exception e) {
            log.error("Failed to publish anchor event: {}", e.getMessage(), e);
        }
    }

    @Async("auditTaskExecutor")
    public void publishTamperAlert(TamperAlert alert) {
        try {
            kafkaTemplate.send("audit-tamper-alerts", alert.getTenantId(), alert);
            log.warn("Published tamper alert: type={}, tenant={}", alert.getAlertType(), alert.getTenantId());
        } catch (Exception e) {
            log.error("Failed to publish tamper alert: {}", e.getMessage(), e);
        }
    }

    public record AnchorEvent(
            String tenantId,
            String merkleRoot,
            Instant intervalStart,
            Instant intervalEnd,
            String signature,
            Instant anchoredAt
    ) {}
}
