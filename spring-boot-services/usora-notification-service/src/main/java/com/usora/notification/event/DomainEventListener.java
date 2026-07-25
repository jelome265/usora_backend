package com.usora.notification.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usora.notification.dto.RequestDto.SendNotificationRequest;
import com.usora.notification.service.DomainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DomainEventListener {

    private final DomainService domainService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "${kafka.topics.notification-events:notification.events}",
                   groupId = "${spring.kafka.consumer.group-id:notification-service-group}")
    public void handleKycStatusChanged(Map<String, Object> event) {
        log.info("Received KYC status change event: {}", event);
        try {
            var notificationRequest = mapToNotificationRequest(event);
            domainService.sendNotification(notificationRequest);
        } catch (Exception e) {
            log.error("Failed to process KYC status change event", e);
        }
    }

    @KafkaListener(topics = "${kafka.topics.compliance-alerts:compliance.alerts}",
                   groupId = "${spring.kafka.consumer.group-id:notification-service-group}")
    public void handleComplianceAlert(Map<String, Object> event) {
        log.info("Received compliance alert event: {}", event);
        try {
            var notificationRequest = mapToNotificationRequest(event);
            domainService.sendNotification(notificationRequest);
        } catch (Exception e) {
            log.error("Failed to process compliance alert event", e);
        }
    }

    @KafkaListener(topics = "${kafka.topics.verification-results:verification.results}",
                   groupId = "${spring.kafka.consumer.group-id:notification-service-group}")
    public void handleVerificationResult(Map<String, Object> event) {
        log.info("Received verification result event: {}", event);
        try {
            var notificationRequest = mapToNotificationRequest(event);
            domainService.sendNotification(notificationRequest);
        } catch (Exception e) {
            log.error("Failed to process verification result event", e);
        }
    }

    private SendNotificationRequest mapToNotificationRequest(Map<String, Object> event) {
        return SendNotificationRequest.builder()
                .to((String) event.getOrDefault("to", ""))
                .channel((String) event.getOrDefault("channel", "EMAIL"))
                .templateId((String) event.getOrDefault("templateId", ""))
                .subject((String) event.get("subject"))
                .variables((Map<String, Object>) event.get("variables"))
                .attachments((Map<String, Object>) event.get("attachments"))
                .priority((String) event.getOrDefault("priority", "NORMAL"))
                .build();
    }
}
