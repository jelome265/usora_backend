package com.usora.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "tenant_configs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantEntity extends BaseEntity {

    @Id
    @Column(name = "tenant_id", length = 36)
    private String tenantId;

    @Column(name = "tenant_name", length = 255)
    private String tenantName;

    @Column(name = "sendgrid_api_key", length = 500)
    private String sendgridApiKey;

    @Column(name = "sendgrid_from_email", length = 255)
    private String sendgridFromEmail;

    @Column(name = "twilio_account_sid", length = 500)
    private String twilioAccountSid;

    @Column(name = "twilio_auth_token", length = 500)
    private String twilioAuthToken;

    @Column(name = "twilio_from_number", length = 20)
    private String twilioFromNumber;

    @Column(name = "webhook_url_template", length = 1000)
    private String webhookUrlTemplate;

    @Column(name = "webhook_secret", length = 500)
    private String webhookSecret;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "push_fcm_config", columnDefinition = "jsonb")
    private Map<String, Object> pushFcmConfig;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "push_apns_config", columnDefinition = "jsonb")
    private Map<String, Object> pushApnsConfig;

    @Column(name = "retry_max_attempts")
    @Builder.Default
    private int retryMaxAttempts = 3;

    @Column(name = "retry_initial_delay_ms")
    @Builder.Default
    private long retryInitialDelayMs = 1000;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
