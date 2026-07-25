package com.usora.compliance.dto;

import java.time.Instant;
import java.util.List;

public record ComplianceValidationResponse(
        String validationId,
        String caseId,
        String status,
        String overallDecision,
        List<RuleResult> ruleResults,
        List<AmlScreeningResult> amlResults,
        List<JurisdictionResult> jurisdictionResults,
        Integer totalViolations,
        Integer totalWarnings,
        Instant validatedAt,
        String validatedBy
) {
    public record RuleResult(
            String ruleId,
            String ruleName,
            String severity,
            Boolean passed,
            String message,
            List<String> triggeredConditions
    ) {}

    public record AmlScreeningResult(
            String screeningId,
            String listName,
            String listType,
            Double matchScore,
            Boolean isMatch,
            String matchedName,
            String category,
            String riskLevel
    ) {}

    public record JurisdictionResult(
            String jurisdiction,
            Boolean compliant,
            List<String> requirementsMet,
            List<String> requirementsFailed,
            String message
    ) {}
}
