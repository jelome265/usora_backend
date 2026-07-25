package com.usora.compliance.dto;

import java.time.Instant;

public record ResponseDto(
        String responseId,
        String status,
        String message,
        Instant timestamp
) {}
