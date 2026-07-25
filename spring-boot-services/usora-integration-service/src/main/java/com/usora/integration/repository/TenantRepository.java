package com.usora.integration.repository;

import com.usora.integration.entity.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantRepository {

    // Webhook Config Repository
    interface WebhookConfigRepository extends JpaRepository<WebhookConfig, UUID> {
        List<WebhookConfig> findByTenantIdAndEnabled(String tenantId, Boolean enabled);

        Optional<WebhookConfig> findByTenantIdAndEndpointId(String tenantId, String endpointId);

        Page<WebhookConfig> findByTenantId(String tenantId, Pageable pageable);

        List<WebhookConfig> findByTenantIdAndStatus(String tenantId, WebhookConfig.WebhookStatus status);

        long countByTenantId(String tenantId);

        @Query("SELECT w FROM WebhookConfig w WHERE w.status = :status AND w.enabled = true")
        List<WebhookConfig> findAllByStatusAndEnabled(@Param("status") WebhookConfig.WebhookStatus status);
    }

    // Integration Provider Repository
    interface IntegrationProviderRepository extends JpaRepository<IntegrationProvider, UUID> {
        List<IntegrationProvider> findByTenantIdAndProviderType(String tenantId, IntegrationProvider.ProviderType providerType);

        Optional<IntegrationProvider> findByTenantIdAndProviderTypeAndProviderName(
                String tenantId, IntegrationProvider.ProviderType providerType, String providerName);

        List<IntegrationProvider> findByTenantIdAndEnabled(String tenantId, Boolean enabled);

        @Query("SELECT p FROM IntegrationProvider p WHERE p.enabled = true AND p.circuitBreakerState = 'CLOSED'")
        List<IntegrationProvider> findAvailableProviders();

        @Modifying
        @Query("UPDATE IntegrationProvider p SET p.failureCount = p.failureCount + 1, p.lastFailureAt = :now WHERE p.id = :id")
        void incrementFailureCount(@Param("id") UUID id, @Param("now") Instant now);

        @Modifying
        @Query("UPDATE IntegrationProvider p SET p.failureCount = 0, p.lastSuccessAt = :now, p.circuitBreakerState = 'CLOSED' WHERE p.id = :id")
        void recordSuccess(@Param("id") UUID id, @Param("now") Instant now);
    }

    // Banking Link Repository
    interface BankingLinkRepository extends JpaRepository<BankingLink, UUID> {
        List<BankingLink> findByTenantIdAndUserId(String tenantId, String userId);

        Optional<BankingLink> findByTenantIdAndUserIdAndProviderName(
                String tenantId, String userId, String providerName);

        Optional<BankingLink> findByTenantIdAndAccountId(String tenantId, String accountId);

        List<BankingLink> findByTenantIdAndStatus(String tenantId, BankingLink.LinkStatus status);

        @Query("SELECT b FROM BankingLink b WHERE b.status = 'LINKED' AND b.tokenExpiresAt < :now")
        List<BankingLink> findExpiredTokens(@Param("now") Instant now);

        @Query("SELECT b FROM BankingLink b WHERE b.status = 'PENDING' AND b.linkedAt < :threshold")
        List<BankingLink> findStalePendingLinks(@Param("threshold") Instant threshold);
    }

    // Government Verification Repository
    interface GovernmentVerificationRepository extends JpaRepository<GovernmentVerification, UUID> {
        Optional<GovernmentVerification> findByTenantIdAndVerificationId(
                String tenantId, UUID verificationId);

        List<GovernmentVerification> findByTenantIdAndUserId(
                String tenantId, String userId);

        Page<GovernmentVerification> findByTenantId(String tenantId, Pageable pageable);

        @Query("SELECT g FROM GovernmentVerification g WHERE g.expiresAt < :now AND g.status = 'VERIFIED'")
        List<GovernmentVerification> findExpiredVerifications(@Param("now") Instant now);

        long countByTenantIdAndUserIdAndStatus(
                String tenantId, String userId, GovernmentVerification.VerificationStatus status);
    }

    // Credit Report Repository
    interface CreditReportRepository extends JpaRepository<CreditReport, UUID> {
        List<CreditReport> findByTenantIdAndUserId(String tenantId, String userId);

        Optional<CreditReport> findByTenantIdAndUserIdAndBureauNameAndReportType(
                String tenantId, String userId, String bureauName, CreditReport.ReportType reportType);

        @Query("SELECT c FROM CreditReport c WHERE c.expiresAt < :now AND c.status = 'COMPLETED'")
        List<CreditReport> findExpiredReports(@Param("now") Instant now);

        @Query("SELECT c FROM CreditReport c WHERE c.tenantId = :tenantId AND c.userId = :userId ORDER BY c.queriedAt DESC")
        List<CreditReport> findLatestByTenantAndUser(
                @Param("tenantId") String tenantId, @Param("userId") String userId, Pageable pageable);
    }
}
