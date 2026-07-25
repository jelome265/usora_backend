package com.usora.notification.event;

import com.usora.notification.entity.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DomainEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishNotificationDelivered(Notification notification) {
        var event = Map.<String, Object>of(
                "eventType", "NOTIFICATION_DELIVERED",
                "notificationId", notification.getId().toString(),
                "tenantId", notification.getTenantId(),
                "channel", notification.getChannel().name(),
                "status", notification.getStatus().name(),
                "deliveredAt", notification.getDeliveredAt() != null
                        ? notification.getDeliveredAt().toString() : null
        );
        kafkaTemplate.send("notification.events", notification.getTenantId(), event);
        log.info("Published delivery event for notification: {}", notification.getId());
    }

    public void publishNotificationFailed(Notification notification, String errorMessage) {
        var event = Map.<String, Object>of(
                "eventType", "NOTIFICATION_FAILED",
                "notificationId", notification.getId().toString(),
                "tenantId", notification.getTenantId(),
                "channel", notification.getChannel().name(),
                "status", notification.getStatus().name(),
                "errorMessage", errorMessage,
                "failedAt", notification.getFailedAt() != null
                        ? notification.getFailedAt().toString() : null,
                "retryCount", notification.getRetryCount()
        );
        kafkaTemplate.send("notification.events", notification.getTenantId(), event);
        log.info("Published failure event for notification: {}", notification.getId());
    }

    public void publishNotificationAcknowledged(Notification notification) {
        var event = Map.<String, Object>of(
                "eventType", "NOTIFICATION_ACKNOWLEDGED",
                "notificationId", notification.getId().toString(),
                "tenantId", notification.getTenantId(),
                "channel", notification.getChannel().name(),
                "acknowledgedAt", notification.getAcknowledgedAt() != null
                        ? notification.getAcknowledgedAt().toString() : null
        );
        kafkaTemplate.send("notification.events", notification.getTenantId(), event);
        log.info("Published acknowledgment event for notification: {}", notification.getId());
    }
}
