package com.usora.compliance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public record EvidenceSubmissionRequest(
        @NotBlank String caseId,
        @NotBlank String evidenceType,
        @NotBlank String contentHash,
        @NotNull byte[] content,
        String mimeType,
        Map<String, String> metadata,
        List<String> tags,
        Boolean requireNotarization
) {
    public EvidenceSubmissionRequest {
        if (tags == null) tags = List.of();
        if (metadata == null) metadata = Map.of();
        if (requireNotarization == null) requireNotarization = false;
    }
}
