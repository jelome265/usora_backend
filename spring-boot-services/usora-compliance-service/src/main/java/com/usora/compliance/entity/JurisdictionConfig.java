package com.usora.compliance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "jurisdiction_configs", schema = "compliance",
       indexes = {
           @Index(name = "idx_juris_tenant", columnList = "tenantId"),
           @Index(name = "idx_juris_code", columnList = "jurisdictionCode")
       })
public class JurisdictionConfig extends TenantEntity {

    @Column(name = "jurisdiction_code", nullable = false, length = 50)
    private String jurisdictionCode;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "regulations", columnDefinition = "TEXT")
    private String regulationsJson;

    @Column(name = "requirements", columnDefinition = "TEXT")
    private String requirementsJson;

    @Column(name = "sanctions_lists", columnDefinition = "TEXT")
    private String sanctionsListsJson;

    @Column(name = "aml_threshold")
    private Double amlThreshold = 0.85;

    @Column(name = "require_adverse_media", nullable = false)
    private Boolean requireAdverseMedia = false;

    @Column(name = "require_pep_check", nullable = false)
    private Boolean requirePepCheck = true;

    @Column(name = "max_report_retention_days")
    private Integer maxReportRetentionDays = 2555;

    @Column(name = "active", nullable = false)
    private Boolean active = true;
}
