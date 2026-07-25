package com.usora.core.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

public final class RequestDto {

    private RequestDto() {}

    public record KYCSubmissionRequest(
            @NotBlank String tenantId,
            @NotBlank String customerId,
            @NotNull @Valid Document document,
            @NotNull @Valid Biometric biometric,
            Map<String, Object> metadata
    ) {
        public record Document(
                @NotBlank String type,
                @NotBlank String number,
                @NotBlank String issuingCountry,
                @NotBlank String frontImageUrl,
                String backImageUrl
        ) {}

        public record Biometric(
                @NotBlank String selfieImageUrl,
                @NotBlank String livenessVideoUrl
        ) {}
    }

    public record KYCStatusRequest(
            @NotNull UUID caseId
    ) {}

    public record CaseStatusUpdateRequest(
            @NotBlank String status,
            String comment,
            Map<String, Object> metadata
    ) {}

    public record TenantConfigUpdateRequest(
            @NotNull Map<String, Object> config
    ) {}
}
