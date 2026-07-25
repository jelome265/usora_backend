package com.usora.core.event;

import com.usora.core.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DomainEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${usora.kafka.topics.verification-events:verification.events}")
    private String verificationTopic;

    public DomainEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public CompletableFuture<SendResult<String, String>> publishKYCEvent(String caseId, String eventType, String payload) {
        var tenantId = TenantContext.getCurrentTenantId();
        log.info("Publishing KYC event: caseId={}, eventType={}, tenantId={}", caseId, eventType, tenantId);

        var future = kafkaTemplate.send(verificationTopic, caseId, payload);
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish KYC event: caseId={}, eventType={}", caseId, eventType, ex);
            } else {
                log.info("KYC event published: caseId={}, offset={}", caseId, result.getRecordMetadata().offset());
            }
        });

        return future;
    }
}
