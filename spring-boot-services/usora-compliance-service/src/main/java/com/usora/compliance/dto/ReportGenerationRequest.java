package com.usora.compliance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ReportGenerationRequest(
        @NotBlank String reportType,
        @NotBlank String format,
        String caseId,
        String jurisdiction,
        Instant startDate,
        Instant endDate,
        List<String> includeSections,
        Map<String, String> filters,
        Boolean includeEvidence,
        Boolean includeAuditTrail
) {
    public ReportGenerationRequest {
        if (includeSections == null) includeSections = List.of("overview", "rules", "aml", "jurisdictions");
        if (includeEvidence == null) includeEvidence = true;
        if (includeAuditTrail == null) includeAuditTrail = true;
    }
}
