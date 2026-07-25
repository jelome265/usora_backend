package com.usora.compliance.dto;

import java.time.Instant;
import java.util.List;

public record JurisdictionCheckResponse(
        String checkId,
        String jurisdiction,
        Boolean overallCompliant,
        List<RegulationResult> regulationResults,
        List<String> requiredActions,
        List<String> recommendations,
        Instant checkedAt,
        String checkedBy
) {
    public record RegulationResult(
            String regulation,
            String status,
            Boolean compliant,
            List<String> requirementsMet,
            List<String> requirementsFailed,
            String description
    ) {}
}
