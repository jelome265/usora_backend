package com.usora.compliance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "audit_trail", schema = "compliance",
       indexes = {
           @Index(name = "idx_audit_case", columnList = "caseId"),
           @Index(name = "idx_audit_tenant", columnList = "tenantId"),
           @Index(name = "idx_audit_timestamp", columnList = "timestamp"),
           @Index(name = "idx_audit_event_type", columnList = "eventType")
       })
public class AuditTrailEntry extends TenantEntity {

    @Column(name = "case_id", nullable = false, length = 100)
    private String caseId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "actor", length = 100)
    private String actor;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "details_json", columnDefinition = "TEXT")
    private String detailsJson;

    @Column(name = "previous_hash", length = 64)
    private String previousHash;

    @Column(name = "current_hash", length = 64, nullable = false)
    private String currentHash;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Column(name = "severity", length = 20)
    private String severity;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;
}
