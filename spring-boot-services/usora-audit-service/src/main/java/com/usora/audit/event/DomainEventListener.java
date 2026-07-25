package com.usora.audit.event;

import com.usora.audit.dto.RequestDto.AuditEventRequest;
import com.usora.audit.service.DomainService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class DomainEventListener {

    private static final Logger log = LoggerFactory.getLogger(DomainEventListener.class);

    private final DomainService domainService;

    public DomainEventListener(DomainService domainService) {
        this.domainService = domainService;
    }

    @KafkaListener(topics = "${audit.kafka.topic.audit-events:audit-events}",
            groupId = "${spring.kafka.consumer.group-id:audit-service-group}",
            containerFactory = "kafkaListenerContainerFactory")
    public void handleAuditEvent(AuditEventRequest request) {
        log.info("Received audit event via Kafka: action={}, actor={}, tenant={}",
                request.getAction(), request.getActorId(), request.getTenantId());
        try {
            if (request.getTimestamp() == null) {
                request.setTimestamp(Instant.now());
            }
            domainService.logEvent(request);
        } catch (Exception e) {
            log.error("Failed to process audit event from Kafka: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "${audit.kafka.topic.cross-service-events:cross-service-audit-events}",
            groupId = "${spring.kafka.consumer.group-id:audit-service-group}")
    public void handleCrossServiceEvent(String message) {
        log.info("Received cross-service audit event: {}", message);
    }
}
