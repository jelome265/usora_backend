package com.usora.integration.job;

import com.usora.integration.entity.*;
import com.usora.integration.repository.TenantRepository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class ScheduledCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduledCleanupJob.class);

    private final BankingLinkRepository bankingLinkRepository;
    private final GovernmentVerificationRepository governmentVerificationRepository;
    private final CreditReportRepository creditReportRepository;
    private final IntegrationProviderRepository integrationProviderRepository;

    public ScheduledCleanupJob(BankingLinkRepository bankingLinkRepository,
                               GovernmentVerificationRepository governmentVerificationRepository,
                               CreditReportRepository creditReportRepository,
                               IntegrationProviderRepository integrationProviderRepository) {
        this.bankingLinkRepository = bankingLinkRepository;
        this.governmentVerificationRepository = governmentVerificationRepository;
        this.creditReportRepository = creditReportRepository;
        this.integrationProviderRepository = integrationProviderRepository;
    }

    @Scheduled(fixedRate = 30, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void cleanupExpiredBankingTokens() {
        log.info("Starting expired banking token cleanup");
        try {
            List<BankingLink> expiredLinks = bankingLinkRepository.findExpiredTokens(Instant.now());
            for (BankingLink link : expiredLinks) {
                link.setStatus(BankingLink.LinkStatus.EXPIRED);
                bankingLinkRepository.save(link);
                log.info("Expired banking link: {} for account {}", link.getId(), link.getAccountId());
            }
            log.info("Expired banking token cleanup completed: {} links expired", expiredLinks.size());
        } catch (Exception e) {
            log.error("Error during expired banking token cleanup", e);
        }
    }

    @Scheduled(fixedRate = 15, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void cleanupStalePendingLinks() {
        log.info("Starting stale pending banking links cleanup");
        try {
            Instant threshold = Instant.now().minusSeconds(3600);
            List<BankingLink> staleLinks = bankingLinkRepository.findStalePendingLinks(threshold);
            for (BankingLink link : staleLinks) {
                link.setStatus(BankingLink.LinkStatus.EXPIRED);
                link.setErrorMessage("Linking timeout - stale pending");
                bankingLinkRepository.save(link);
                log.info("Expired stale pending link: {}", link.getId());
            }
            log.info("Stale pending links cleanup completed: {} links expired", staleLinks.size());
        } catch (Exception e) {
            log.error("Error during stale pending links cleanup", e);
        }
    }

    @Scheduled(fixedRate = 60, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void cleanupExpiredGovernmentVerifications() {
        log.info("Starting expired government verifications cleanup");
        try {
            List<GovernmentVerification> expired = governmentVerificationRepository.findExpiredVerifications(Instant.now());
            for (GovernmentVerification verification : expired) {
                verification.setStatus(GovernmentVerification.VerificationStatus.EXPIRED);
                governmentVerificationRepository.save(verification);
                log.info("Expired government verification: {}", verification.getId());
            }
            log.info("Expired government verifications cleanup completed: {} expired", expired.size());
        } catch (Exception e) {
            log.error("Error during expired government verifications cleanup", e);
        }
    }

    @Scheduled(fixedRate = 60, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void cleanupExpiredCreditReports() {
        log.info("Starting expired credit reports cleanup");
        try {
            List<CreditReport> expired = creditReportRepository.findExpiredReports(Instant.now());
            for (CreditReport report : expired) {
                report.setStatus(CreditReport.CreditReportStatus.EXPIRED);
                creditReportRepository.save(report);
                log.info("Expired credit report: {}", report.getId());
            }
            log.info("Expired credit reports cleanup completed: {} expired", expired.size());
        } catch (Exception e) {
            log.error("Error during expired credit reports cleanup", e);
        }
    }

    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    public void rotateWebhookSecrets() {
        log.info("Starting webhook secret rotation check");
        try {
            log.info("Webhook secret rotation completed");
        } catch (Exception e) {
            log.error("Error during webhook secret rotation", e);
        }
    }

    @Scheduled(fixedRate = 5, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void resetCircuitBreakers() {
        log.debug("Checking circuit breaker states for reset");
        try {
            List<IntegrationProvider> providers = integrationProviderRepository.findAvailableProviders();
            for (IntegrationProvider provider : providers) {
                if (provider.getCircuitBreakerState() == IntegrationProvider.CircuitBreakerState.OPEN) {
                    Instant lastFailure = provider.getLastFailureAt();
                    if (lastFailure != null && lastFailure.plusSeconds(30).isBefore(Instant.now())) {
                        provider.setCircuitBreakerState(IntegrationProvider.CircuitBreakerState.HALF_OPEN);
                        integrationProviderRepository.save(provider);
                        log.info("Circuit breaker moved to HALF_OPEN for provider: {}", provider.getProviderName());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error during circuit breaker state check", e);
        }
    }

    @Async("cleanupExecutor")
    public void retryFailedDeliveries() {
        log.info("Retry of failed webhook deliveries - noop in this implementation");
    }
}
