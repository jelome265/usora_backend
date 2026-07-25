package com.usora.compliance.dto;

import java.time.Instant;

public record ReportGenerationResponse(
        String reportId,
        String status,
        String format,
        String downloadUrl,
        Instant requestedAt,
        Instant completedAt,
        String message
) {}
