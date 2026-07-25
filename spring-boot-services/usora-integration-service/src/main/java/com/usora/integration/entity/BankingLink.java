package com.usora.integration.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "banking_links", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"tenant_id", "user_id", "provider_name"})
})
public class BankingLink extends TenantEntity {

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "provider_name", nullable = false, length = 64)
    private String providerName;

    @Column(name = "access_token_encrypted", nullable = false, columnDefinition = "TEXT")
    private String accessTokenEncrypted;

    @Column(name = "refresh_token_encrypted", columnDefinition = "TEXT")
    private String refreshTokenEncrypted;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    @Column(name = "account_id", length = 128)
    private String accountId;

    @Column(name = "account_type", length = 64)
    private String accountType;

    @Column(name = "account_number_masked", length = 32)
    private String accountNumberMasked;

    @Column(name = "routing_number", length = 16)
    private String routingNumber;

    @Column(name = "institution_name", length = 256)
    private String institutionName;

    @Column(name = "institution_id", length = 128)
    private String institutionId;

    @Column(name = "status", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private LinkStatus status;

    @Column(name = "linked_at")
    private Instant linkedAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "kyc_completed", nullable = false)
    private Boolean kycCompleted = false;

    @Column(name = "user_consent_granted", nullable = false)
    private Boolean userConsentGranted = false;

    @Column(name = "consent_expires_at")
    private Instant consentExpiresAt;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    public enum LinkStatus {
        PENDING, LINKED, VERIFIED, EXPIRED, ERROR, DISCONNECTED
    }
}
