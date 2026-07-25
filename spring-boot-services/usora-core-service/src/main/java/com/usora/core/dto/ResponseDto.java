package com.usora.core.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class ResponseDto {

    private ResponseDto() {}

    public record KYCSubmissionResponse(
            UUID caseId,
            String status,
            String message,
            Instant timestamp
    ) {}

    public record KYCStatusResponse(
            UUID caseId,
            String status,
            String stage,
            Instant createdAt,
            Instant updatedAt,
            Map<String, Object> details
    ) {}

    public record CaseResponse(
            UUID caseId,
            String tenantId,
            String customerId,
            String status,
            String stage,
            Instant createdAt,
            Instant updatedAt,
            Map<String, Object> metadata
    ) {}

    public record TenantConfigResponse(
            String tenantId,
            Map<String, Object> config,
            Instant updatedAt
    ) {}
}
