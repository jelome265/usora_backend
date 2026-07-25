package com.usora.compliance.dto;

import java.time.Instant;
import java.util.List;

public record RegulatoryRulesResponse(
        List<RuleDefinition> rules,
        Integer totalRules,
        Instant lastUpdated,
        String version
) {
    public record RuleDefinition(
            String ruleId,
            String name,
            String description,
            String jurisdiction,
            String category,
            String severity,
            String drlContent,
            Boolean active,
            Integer version,
            String signedBy,
            Instant effectiveFrom,
            Instant expiresAt
    ) {}
}
