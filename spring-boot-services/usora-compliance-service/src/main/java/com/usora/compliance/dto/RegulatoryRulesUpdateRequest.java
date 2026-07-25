package com.usora.compliance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record RegulatoryRulesUpdateRequest(
        @NotBlank String ruleId,
        @NotBlank String name,
        String description,
        @NotBlank String jurisdiction,
        @NotBlank String category,
        @NotBlank String severity,
        @NotBlank String drlContent,
        @NotNull Boolean active,
        String effectiveFrom,
        String expiresAt,
        List<String> replaceRuleIds
) {}
