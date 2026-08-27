package com.usora.notification.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usora.notification.dto.RequestDto.SendNotificationRequest;
import com.usora.notification.security.TenantContext;
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

    /**
     * PRE-EXISTING BUG, found while implementing Postgres row-level
     * security for this service: DomainService.sendNotification() reads
     * TenantContext.getCurrentTenantId() to populate the notification's
     * NOT NULL tenant_id column — but TenantContext is only ever set
     * during HTTP JWT authentication (see TenantInterceptor.java). These
     * three @KafkaListener methods run on Kafka's consumer threads, with
     * no HTTP request and no TenantContext at all — every Kafka-
     * triggered notification has been saving with tenant_id = null,
     * which should already violate the NOT NULL constraint on every
     * single message. Each listener wraps its call in a bare
     * catch (Exception e) { log.error(...) }, so this has been failing
     * completely silently: every KYC status change, compliance alert,
     * and verification result notification triggered via Kafka has
     * never actually been sent, just logged as an error and dropped.
     *
     * Fixed by extracting tenantId from the event payload itself and
     * setting TenantContext for the duration of the call — the same
     * thing TenantInterceptor does for an HTTP request, just for this
     * thread instead. Trusting a tenantId field from a Kafka message is
     * a different trust boundary than trusting an HTTP header from an
     * external caller (see the header-spoofing fix elsewhere this
     * session): these events originate from other internal services via
     * the message bus, not directly from an untrusted end user. If the
     * publishing event genuinely has no tenantId field, this now fails
     * loudly and visibly (logged, message not silently accepted) rather
     * than continuing to save with a value that was always going to be
     * rejected.
     */
    @KafkaListener(topics = "${kafka.topics.notification-events:notification.events}",
                   groupId = "${spring.kafka.consumer.group-id:notification-service-group}")
    public void handleKycStatusChanged(Map<String, Object> event) {
        log.info("Received KYC status change event: {}", event);
        withTenantContext(event, () -> {
            var notificationRequest = mapToNotificationRequest(event);
            domainService.sendNotification(notificationRequest);
        });
    }

    @KafkaListener(topics = "${kafka.topics.compliance-alerts:compliance.alerts}",
                   groupId = "${spring.kafka.consumer.group-id:notification-service-group}")
    public void handleComplianceAlert(Map<String, Object> event) {
        log.info("Received compliance alert event: {}", event);
        withTenantContext(event, () -> {
            var notificationRequest = mapToNotificationRequest(event);
            domainService.sendNotification(notificationRequest);
        });
    }

    @KafkaListener(topics = "${kafka.topics.verification-results:verification.results}",
                   groupId = "${spring.kafka.consumer.group-id:notification-service-group}")
    public void handleVerificationResult(Map<String, Object> event) {
        log.info("Received verification result event: {}", event);
        withTenantContext(event, () -> {
            var notificationRequest = mapToNotificationRequest(event);
            domainService.sendNotification(notificationRequest);
        });
    }

    private void withTenantContext(Map<String, Object> event, Runnable action) {
        if (event == null || event.isEmpty()) {
            log.error("Dropping empty/null Kafka event -- nothing to process");
            return;
        }
        var tenantId = (String) event.get("tenantId");
        if (tenantId == null || tenantId.isBlank()) {
            log.error("Dropping event with no tenantId field — cannot process without a tenant: {}", event);
            return;
        }
        // F-010 remediation item 4: reject malformed payloads before they
        // reach domainService.sendNotification() rather than silently
        // defaulting "to" to an empty string and "channel" to EMAIL --
        // both of which previously let a malformed or unexpected message
        // shape through as a notification request to nobody, on whatever
        // channel happened to be the default, rather than failing visibly.
        var to = (String) event.get("to");
        var channel = (String) event.get("channel");
        if (to == null || to.isBlank()) {
            log.error("Dropping event with no \"to\" field for tenant {} -- cannot send a notification with no recipient", tenantId);
            return;
        }
        if (channel == null || channel.isBlank()) {
            log.error("Dropping event with no \"channel\" field for tenant {} -- refusing to guess a delivery channel", tenantId);
            return;
        }
        try {
            TenantContext.setCurrentTenantId(tenantId);
            action.run();
        } catch (Exception e) {
            log.error("Failed to process event for tenant {}", tenantId, e);
        } finally {
            TenantContext.clear();
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
