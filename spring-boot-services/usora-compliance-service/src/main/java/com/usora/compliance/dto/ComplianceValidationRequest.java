package com.usora.compliance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public record ComplianceValidationRequest(
        @NotBlank String caseId,
        @NotBlank String entityId,
        @NotBlank String entityType,
        @NotNull Map<String, Object> entityData,
        List<String> jurisdictions,
        List<String> watchlistTypes,
        Boolean includeAdverseMedia,
        Boolean includeTransactionScreening
) {
    public ComplianceValidationRequest {
        if (jurisdictions == null) jurisdictions = List.of("eu_gdpr");
        if (watchlistTypes == null) watchlistTypes = List.of("sanctions", "pep");
        if (includeAdverseMedia == null) includeAdverseMedia = false;
        if (includeTransactionScreening == null) includeTransactionScreening = false;
    }
}
