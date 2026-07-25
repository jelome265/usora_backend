package com.usora.compliance.dto;

import jakarta.validation.constraints.NotBlank;

public record RequestDto(
        @NotBlank String requestId,
        String tenantId,
        String jurisdiction
) {}
