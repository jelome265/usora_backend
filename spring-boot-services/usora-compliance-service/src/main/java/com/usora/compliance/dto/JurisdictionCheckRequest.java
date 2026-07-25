package com.usora.compliance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public record JurisdictionCheckRequest(
        @NotBlank String caseId,
        @NotBlank String entityId,
        @NotBlank String jurisdiction,
        @NotNull Map<String, Object> entityAttributes,
        List<String> applicableRegulations
) {
    public JurisdictionCheckRequest {
        if (applicableRegulations == null) applicableRegulations = List.of();
    }
}
