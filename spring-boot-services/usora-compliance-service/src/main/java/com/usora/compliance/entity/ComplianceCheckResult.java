package com.usora.compliance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "compliance_check_results", schema = "compliance",
       indexes = {
           @Index(name = "idx_check_case", columnList = "caseId"),
           @Index(name = "idx_check_tenant", columnList = "tenantId"),
           @Index(name = "idx_check_decision", columnList = "overallDecision")
       })
public class ComplianceCheckResult extends TenantEntity {

    @Column(name = "case_id", nullable = false, length = 100)
    private String caseId;

    @Column(name = "entity_id", length = 100)
    private String entityId;

    @Column(name = "entity_type", length = 50)
    private String entityType;

    @Column(name = "overall_decision", nullable = false, length = 20)
    private String overallDecision;

    @Column(name = "validation_json", columnDefinition = "TEXT")
    private String validationJson;

    @Column(name = "total_violations")
    private Integer totalViolations = 0;

    @Column(name = "total_warnings")
    private Integer totalWarnings = 0;

    @Column(name = "validated_at", nullable = false)
    private Instant validatedAt;

    @Column(name = "validated_by", length = 100)
    private String validatedBy;

    @Column(name = "expires_at")
    private Instant expiresAt;
}
