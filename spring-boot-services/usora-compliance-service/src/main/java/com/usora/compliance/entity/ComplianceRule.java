package com.usora.compliance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "compliance_rules", schema = "compliance",
       indexes = {
           @Index(name = "idx_rules_tenant_jurisdiction", columnList = "tenantId, jurisdiction"),
           @Index(name = "idx_rules_active", columnList = "active"),
           @Index(name = "idx_rules_rule_id_version", columnList = "ruleId, ruleVersion")
       })
public class ComplianceRule extends TenantEntity {

    @Column(name = "rule_id", nullable = false, length = 100)
    private String ruleId;

    @Column(name = "rule_version", nullable = false)
    private Integer ruleVersion;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "category", length = 100)
    private String category;

    @Column(name = "severity", nullable = false, length = 20)
    private String severity;

    @Column(name = "drl_content", nullable = false, columnDefinition = "TEXT")
    private String drlContent;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "signature_hash", length = 64)
    private String signatureHash;

    @Column(name = "signed_by", length = 100)
    private String signedBy;

    @Column(name = "officer_approved_by", length = 100)
    private String officerApprovedBy;

    @Column(name = "legal_approved_by", length = 100)
    private String legalApprovedBy;

    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "merkle_root", length = 64)
    private String merkleRoot;

    @Column(name = "previous_version_id", length = 36)
    private String previousVersionId;
}
