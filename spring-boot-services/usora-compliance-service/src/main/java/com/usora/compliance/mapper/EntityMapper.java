package com.usora.compliance.mapper;

import com.usora.compliance.dto.*;
import com.usora.compliance.entity.*;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface EntityMapper {

    // ComplianceRule mappings

    RegulatoryRulesResponse.RuleDefinition toRuleDefinition(ComplianceRule rule);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "isDeleted", ignore = true)
    @Mapping(target = "merkleRoot", ignore = true)
    @Mapping(target = "previousVersionId", ignore = true)
    ComplianceRule toComplianceRule(RegulatoryRulesUpdateRequest request);

    // AuditTrailEntry mappings

    AuditTrailResponse.AuditEntry toAuditEntry(AuditTrailEntry entry);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "isDeleted", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "jurisdiction", ignore = true)
    AuditTrailEntry toAuditTrailEntry(AuditTrailResponse.AuditEntry dto);

    // EvidenceRecord mappings

    EvidenceSubmissionResponse toEvidenceSubmissionResponse(EvidenceRecord record);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "isDeleted", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "jurisdiction", ignore = true)
    EvidenceRecord toEvidenceRecord(EvidenceSubmissionRequest request);

    // ComplianceCheckResult mappings

    ComplianceValidationResponse toComplianceValidationResponse(ComplianceCheckResult result);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "isDeleted", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "jurisdiction", ignore = true)
    ComplianceCheckResult toComplianceCheckResult(ComplianceValidationRequest request);

    // JurisdictionConfig mappings

    JurisdictionCheckResponse toJurisdictionCheckResponse(JurisdictionConfig config);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "isDeleted", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "jurisdiction", ignore = true)
    JurisdictionConfig toJurisdictionConfig(JurisdictionCheckRequest request);
}
