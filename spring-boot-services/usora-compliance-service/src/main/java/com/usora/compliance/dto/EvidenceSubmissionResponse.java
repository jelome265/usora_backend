package com.usora.compliance.dto;

import java.time.Instant;

public record EvidenceSubmissionResponse(
        String evidenceId,
        String caseId,
        String status,
        String storagePath,
        String verificationHash,
        String blockchainTransactionId,
        Instant submittedAt,
        String message
) {}
