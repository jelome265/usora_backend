package com.usora.audit.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log", schema = "audit")
@Getter
@Setter
@NoArgsConstructor
public class AuditEvent extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false, length = 50)
    private String tenantId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    @Column(name = "actor_id", nullable = false, updatable = false, length = 100)
    private String actorId;

    @Column(name = "action", nullable = false, updatable = false, length = 100)
    private String action;

    @Column(name = "resource_type", nullable = false, updatable = false, length = 100)
    private String resourceType;

    @Column(name = "resource_id", nullable = false, updatable = false, length = 100)
    private String resourceId;

    @Column(name = "before_state", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String beforeState;

    @Column(name = "after_state", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String afterState;

    @Column(name = "metadata", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String metadata;

    @Column(name = "outcome", nullable = false, updatable = false, length = 20)
    private String outcome;

    @Column(name = "severity", updatable = false, length = 20)
    private String severity;

    @Column(name = "category", updatable = false, length = 50)
    private String category;

    @Column(name = "ip_address", updatable = false, length = 45)
    private String ipAddress;

    @Column(name = "user_agent", updatable = false, length = 500)
    private String userAgent;

    @Column(name = "previous_hash", updatable = false, length = 64)
    private String previousHash;

    @Column(name = "current_hash", nullable = false, updatable = false, length = 64)
    private String currentHash;

    @Column(name = "signature", nullable = false, updatable = false, length = 128)
    private String signature;

    @Column(name = "event_timestamp", nullable = false, updatable = false)
    private Instant eventTimestamp;

    @Column(name = "forensic_flag", updatable = false)
    private boolean forensicFlag = false;

    @Column(name = "anchored", nullable = false)
    private boolean anchored = false;

    @Column(name = "archived", nullable = false)
    private boolean archived = false;
}
