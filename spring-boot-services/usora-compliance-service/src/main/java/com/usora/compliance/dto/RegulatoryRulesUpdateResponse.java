package com.usora.compliance.dto;

import java.time.Instant;

public record RegulatoryRulesUpdateResponse(
        String ruleId,
        Integer newVersion,
        String status,
        String signatureHash,
        Instant updatedAt,
        String updatedBy,
        String message
) {}
