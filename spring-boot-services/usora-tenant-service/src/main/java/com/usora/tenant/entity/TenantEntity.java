package com.usora.tenant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "tenants")
@Getter
@Setter
public class TenantEntity extends BaseEntity {

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "domain", nullable = false, unique = true, length = 255)
    private String domain;

    @Column(name = "plan", nullable = false, length = 50)
    private String plan;

    @Column(name = "region", nullable = false, length = 50)
    private String region;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TenantStatus status = TenantStatus.PROVISIONING;

    @Column(name = "features", columnDefinition = "jsonb")
    private String features;

    @Column(name = "admin_email", nullable = false, length = 255)
    private String adminEmail;

    @Column(name = "max_users", nullable = false)
    private int maxUsers = 100;

    @Column(name = "storage_quota_bytes", nullable = false)
    private long storageQuotaBytes = 107374182400L;

    @Column(name = "config", columnDefinition = "jsonb")
    private String config;

    @Column(name = "stripe_customer_id", length = 255)
    private String stripeCustomerId;

    @Column(name = "provisioning_status", length = 50)
    private String provisioningStatus;

    public enum TenantStatus {
        PROVISIONING, ACTIVE, SUSPENDED, DELETED
    }
}
