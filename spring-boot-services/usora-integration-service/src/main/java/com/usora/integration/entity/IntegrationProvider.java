package com.usora.integration.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "integration_providers", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"tenant_id", "provider_type", "provider_name"})
})
public class IntegrationProvider extends TenantEntity {

    @Column(name = "provider_type", nullable = false, length = 64)
    @Enumerated(EnumType.STRING)
    private ProviderType providerType;

    @Column(name = "provider_name", nullable = false, length = 128)
    private String providerName;

    @Column(name = "config_encrypted", nullable = false, columnDefinition = "TEXT")
    private String configEncrypted;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "priority")
    private Integer priority = 0;

    @Column(name = "circuit_breaker_state", length = 32)
    @Enumerated(EnumType.STRING)
    private CircuitBreakerState circuitBreakerState = CircuitBreakerState.CLOSED;

    @Column(name = "failure_count", nullable = false)
    private Integer failureCount = 0;

    @Column(name = "last_failure_at")
    private java.time.Instant lastFailureAt;

    @Column(name = "last_success_at")
    private java.time.Instant lastSuccessAt;

    @Column(name = "rate_limit_rpm", nullable = false)
    private Integer rateLimitRpm = 100;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    public enum ProviderType {
        BANKING, GOVERNMENT, CREDIT, DOCUMENT_VERIFICATION, SANCTIONS_SCREENING
    }

    public enum CircuitBreakerState {
        CLOSED, OPEN, HALF_OPEN
    }
}
