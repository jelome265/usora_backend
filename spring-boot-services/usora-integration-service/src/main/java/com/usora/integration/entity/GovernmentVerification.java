package com.usora.integration.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "government_verifications")
public class GovernmentVerification extends TenantEntity {

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "verification_type", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private VerificationType verificationType;

    @Column(name = "provider_name", length = 64)
    private String providerName;

    @Column(name = "status", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private VerificationStatus status;

    @Column(name = "request_payload_encrypted", columnDefinition = "TEXT")
    private String requestPayloadEncrypted;

    @Column(name = "response_payload_encrypted", columnDefinition = "TEXT")
    private String responsePayloadEncrypted;

    @Column(name = "identity_document_hash", length = 128)
    private String identityDocumentHash;

    @Column(name = "country_code", length = 4)
    private String countryCode;

    @Column(name = "document_number_masked", length = 32)
    private String documentNumberMasked;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "verification_id", nullable = false, unique = true)
    private UUID verificationId;

    @Column(name = "consent_granted", nullable = false)
    private Boolean consentGranted = false;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    public enum VerificationType {
        EIDAS, AADHAAR, DMV, PASSPORT, NATIONAL_ID, VISA, RESIDENCE_PERMIT
    }

    public enum VerificationStatus {
        PENDING, IN_PROGRESS, VERIFIED, FAILED, EXPIRED, FLAGGED_FOR_REVIEW
    }
}
