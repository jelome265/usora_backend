package com.usora.audit.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "tenant_config", schema = "audit")
@Getter
@Setter
@NoArgsConstructor
public class TenantEntity extends BaseEntity {

    @Id
    @Column(name = "tenant_id", nullable = false, length = 50, updatable = false)
    private String tenantId;

    @Column(name = "hmac_key", nullable = false, length = 512)
    private String hmacKey;

    @Column(name = "key_rotation_at")
    private java.time.Instant keyRotationAt;

    @Column(name = "blockchain_enabled", nullable = false)
    private boolean blockchainEnabled = true;

    @Column(name = "blockchain_channel_id", length = 100)
    private String blockchainChannelId = "auditchannel";

    @Column(name = "blockchain_anchor_interval_min", nullable = false)
    private int blockchainAnchorIntervalMin = 60;

    @Column(name = "hot_retention_days", nullable = false)
    private int hotRetentionDays = 90;

    @Column(name = "cold_retention_years", nullable = false)
    private int coldRetentionYears = 7;

    @Column(name = "siem_enabled", nullable = false)
    private boolean siemEnabled = true;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
