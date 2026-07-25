package com.usora.integration.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

@Getter
@Setter
@Entity
@Table(name = "webhook_configs", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"tenant_id", "endpoint_id"})
})
public class WebhookConfig extends TenantEntity {

    @Column(name = "endpoint_id", nullable = false, length = 128)
    private String endpointId;

    @Column(name = "url", nullable = false, length = 1024)
    private String url;

    @Column(name = "description", length = 512)
    private String description;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "webhook_config_events", joinColumns = @JoinColumn(name = "webhook_config_id"))
    @Column(name = "event_type", length = 256)
    private Set<String> events = new HashSet<>();

    @Column(name = "secret", length = 512)
    private String secret;

    @Column(name = "hmac_secret", length = 512)
    private String hmacSecret;

    @Column(name = "public_key", columnDefinition = "TEXT")
    private String publicKey;

    @Column(name = "auth_type", length = 32)
    @Enumerated(EnumType.STRING)
    private AuthType authType;

    @Column(name = "status", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private WebhookStatus status;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 5;

    @Column(name = "retry_interval_ms", nullable = false)
    private Long retryIntervalMs = 1000L;

    @Column(name = "rate_limit_per_second", nullable = false)
    private Integer rateLimitPerSecond = 100;

    @Column(name = "max_payload_size_bytes", nullable = false)
    private Long maxPayloadSizeBytes = 10_485_760L;

    @Column(name = "filter_expression", length = 1024)
    private String filterExpression;

    @Column(name = "headers", columnDefinition = "TEXT")
    private String headers;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "webhook_url", length = 1024)
    private String webhookUrl;

    @Column(name = "cloud_event_source", length = 512)
    private String cloudEventSource;

    @Column(name = "cloud_event_type_prefix", length = 256)
    private String cloudEventTypePrefix;

    public enum AuthType {
        NONE, API_KEY, HMAC, RSA, ECDSA, OAUTH2, CUSTOM
    }

    public enum WebhookStatus {
        ACTIVE, PAUSED, DISABLED, PENDING_VERIFICATION
    }
}
