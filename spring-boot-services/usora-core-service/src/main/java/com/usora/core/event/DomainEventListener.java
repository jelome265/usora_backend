package com.usora.core.event;

import com.usora.core.service.DomainService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DomainEventListener {

    private static final Logger log = LoggerFactory.getLogger(DomainEventListener.class);

    private final DomainService domainService;

    public DomainEventListener(DomainService domainService) {
        this.domainService = domainService;
    }

    @KafkaListener(
            topics = "${usora.kafka.topics.verification-events:verification.events}",
            groupId = "${usora.kafka.consumer.group-id:orchestrator-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onVerificationEvent(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header("tenant_id") String tenantId,
            Acknowledgment ack) {

        try {
            log.info("Received verification event: key={}, tenantId={}, payload={}", key, tenantId, payload);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process verification event: key={}", key, e);
        }
    }
}
