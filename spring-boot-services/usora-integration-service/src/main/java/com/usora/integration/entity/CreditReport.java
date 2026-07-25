package com.usora.integration.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "credit_reports")
public class CreditReport extends TenantEntity {

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "bureau_name", nullable = false, length = 64)
    private String bureauName;

    @Column(name = "report_type", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private ReportType reportType;

    @Column(name = "status", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private CreditReportStatus status;

    @Column(name = "request_payload_encrypted", columnDefinition = "TEXT")
    private String requestPayloadEncrypted;

    @Column(name = "response_payload_encrypted", columnDefinition = "TEXT")
    private String responsePayloadEncrypted;

    @Column(name = "credit_score")
    private Integer creditScore;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "fraud_indicators", columnDefinition = "TEXT")
    private String fraudIndicators;

    @Column(name = "identity_match", nullable = false)
    private Boolean identityMatch = false;

    @Column(name = "consumer_consent_granted", nullable = false)
    private Boolean consumerConsentGranted = false;

    @Column(name = "consent_id", length = 128)
    private String consentId;

    @Column(name = "fcra_compliant", nullable = false)
    private Boolean fcraCompliant = true;

    @Column(name = "adverse_action_notice_sent", nullable = false)
    private Boolean adverseActionNoticeSent = false;

    @Column(name = "queried_at", nullable = false)
    private Instant queriedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    public enum ReportType {
        IDENTITY_VERIFICATION, CREDIT_REPORT, FRAUD_CHECK, ALTERNATIVE_DATA, CREDIT_SCORE
    }

    public enum CreditReportStatus {
        PENDING, COMPLETED, FAILED, PARTIAL, EXPIRED, CONSENT_REQUIRED, CREDIT_FROZEN
    }
}
