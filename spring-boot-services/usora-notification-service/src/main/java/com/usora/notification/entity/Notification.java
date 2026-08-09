package com.usora.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "notifications")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notification extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(name = "to_address", nullable = false, length = 500)
    private String toAddress;

    @Column(name = "template_id", nullable = false, length = 100)
    private String templateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private NotificationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 10)
    private NotificationPriority priority;

    @Column(name = "subject", length = 500)
    private String subject;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables")
    private Map<String, Object> variables;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attachments")
    private Map<String, Object> attachments;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    public enum NotificationChannel {
        EMAIL, SMS, WEBHOOK, PUSH_IN_APP
    }

    public enum NotificationStatus {
        PENDING, SENDING, SENT, DELIVERED, FAILED, ACKNOWLEDGED
    }

    public enum NotificationPriority {
        LOW, NORMAL, HIGH, URGENT
    }
}
