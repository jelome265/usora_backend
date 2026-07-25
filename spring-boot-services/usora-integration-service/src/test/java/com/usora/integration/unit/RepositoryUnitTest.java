package com.usora.integration.unit;

import com.usora.integration.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryUnitTest {

    private WebhookConfig webhookConfig;
    private IntegrationProvider integrationProvider;
    private BankingLink bankingLink;
    private GovernmentVerification governmentVerification;
    private CreditReport creditReport;

    @BeforeEach
    void setUp() {
        webhookConfig = new WebhookConfig();
        webhookConfig.setId(UUID.randomUUID());
        webhookConfig.setTenantId("tenant-1");
        webhookConfig.setEndpointId("endpoint-1");
        webhookConfig.setUrl("https://example.com/webhook");
        webhookConfig.setSecret("test-secret");
        webhookConfig.setAuthType(WebhookConfig.AuthType.HMAC);
        webhookConfig.setStatus(WebhookConfig.WebhookStatus.ACTIVE);
        webhookConfig.setEnabled(true);
        webhookConfig.setEvents(new HashSet<>(Set.of("user.created", "user.updated")));
        webhookConfig.setRetryCount(5);
        webhookConfig.setRetryIntervalMs(1000L);

        integrationProvider = new IntegrationProvider();
        integrationProvider.setId(UUID.randomUUID());
        integrationProvider.setTenantId("tenant-1");
        integrationProvider.setProviderType(IntegrationProvider.ProviderType.BANKING);
        integrationProvider.setProviderName("plaid");
        integrationProvider.setConfigEncrypted("encrypted-config");
        integrationProvider.setEnabled(true);
        integrationProvider.setCircuitBreakerState(IntegrationProvider.CircuitBreakerState.CLOSED);

        bankingLink = new BankingLink();
        bankingLink.setId(UUID.randomUUID());
        bankingLink.setTenantId("tenant-1");
        bankingLink.setUserId("user-1");
        bankingLink.setProviderName("plaid");
        bankingLink.setAccessTokenEncrypted("encrypted-token");
        bankingLink.setStatus(BankingLink.LinkStatus.PENDING);
        bankingLink.setLinkedAt(Instant.now());
        bankingLink.setUserConsentGranted(true);

        governmentVerification = new GovernmentVerification();
        governmentVerification.setId(UUID.randomUUID());
        governmentVerification.setTenantId("tenant-1");
        governmentVerification.setUserId("user-1");
        governmentVerification.setVerificationType(GovernmentVerification.VerificationType.EIDAS);
        governmentVerification.setStatus(GovernmentVerification.VerificationStatus.PENDING);
        governmentVerification.setVerificationId(UUID.randomUUID());
        governmentVerification.setCountryCode("DE");
        governmentVerification.setConsentGranted(true);

        creditReport = new CreditReport();
        creditReport.setId(UUID.randomUUID());
        creditReport.setTenantId("tenant-1");
        creditReport.setUserId("user-1");
        creditReport.setBureauName("experian");
        creditReport.setReportType(CreditReport.ReportType.CREDIT_REPORT);
        creditReport.setStatus(CreditReport.CreditReportStatus.PENDING);
        creditReport.setConsumerConsentGranted(true);
        creditReport.setFcraCompliant(true);
        creditReport.setQueriedAt(Instant.now());
    }

    @Test
    @DisplayName("WebhookConfig should have correct fields")
    void testWebhookConfig() {
        assertNotNull(webhookConfig.getId());
        assertEquals("tenant-1", webhookConfig.getTenantId());
        assertEquals("endpoint-1", webhookConfig.getEndpointId());
        assertEquals(WebhookConfig.AuthType.HMAC, webhookConfig.getAuthType());
        assertEquals(WebhookConfig.WebhookStatus.ACTIVE, webhookConfig.getStatus());
        assertTrue(webhookConfig.getEnabled());
        assertEquals(2, webhookConfig.getEvents().size());
        assertEquals(5, webhookConfig.getRetryCount());
    }

    @Test
    @DisplayName("WebhookConfig should support soft delete")
    void testWebhookConfigSoftDelete() {
        assertNull(webhookConfig.getDeletedAt());
        assertFalse(webhookConfig.isDeleted());
        webhookConfig.softDelete();
        assertNotNull(webhookConfig.getDeletedAt());
        assertTrue(webhookConfig.isDeleted());
    }

    @Test
    @DisplayName("IntegrationProvider should track circuit breaker state")
    void testIntegrationProviderCircuitBreaker() {
        assertEquals(IntegrationProvider.CircuitBreakerState.CLOSED, integrationProvider.getCircuitBreakerState());

        integrationProvider.setCircuitBreakerState(IntegrationProvider.CircuitBreakerState.OPEN);
        assertEquals(IntegrationProvider.CircuitBreakerState.OPEN, integrationProvider.getCircuitBreakerState());

        integrationProvider.setFailureCount(5);
        assertEquals(5, integrationProvider.getFailureCount());
    }

    @Test
    @DisplayName("BankingLink should have correct lifecycle states")
    void testBankingLinkStates() {
        assertEquals(BankingLink.LinkStatus.PENDING, bankingLink.getStatus());

        bankingLink.setStatus(BankingLink.LinkStatus.LINKED);
        assertEquals(BankingLink.LinkStatus.LINKED, bankingLink.getStatus());

        bankingLink.setStatus(BankingLink.LinkStatus.VERIFIED);
        assertEquals(BankingLink.LinkStatus.VERIFIED, bankingLink.getStatus());
        bankingLink.setVerifiedAt(Instant.now());
        assertNotNull(bankingLink.getVerifiedAt());

        bankingLink.setStatus(BankingLink.LinkStatus.DISCONNECTED);
        assertEquals(BankingLink.LinkStatus.DISCONNECTED, bankingLink.getStatus());
    }

    @Test
    @DisplayName("GovernmentVerification should track verification status")
    void testGovernmentVerification() {
        assertEquals(GovernmentVerification.VerificationStatus.PENDING, governmentVerification.getStatus());
        assertEquals("DE", governmentVerification.getCountryCode());
        assertEquals(GovernmentVerification.VerificationType.EIDAS, governmentVerification.getVerificationType());
        assertTrue(governmentVerification.getConsentGranted());

        governmentVerification.setStatus(GovernmentVerification.VerificationStatus.VERIFIED);
        governmentVerification.setVerifiedAt(Instant.now());
        assertEquals(GovernmentVerification.VerificationStatus.VERIFIED, governmentVerification.getStatus());
        assertNotNull(governmentVerification.getVerifiedAt());
    }

    @Test
    @DisplayName("CreditReport should track FCRA compliance")
    void testCreditReport() {
        assertEquals(CreditReport.ReportType.CREDIT_REPORT, creditReport.getReportType());
        assertEquals("experian", creditReport.getBureauName());
        assertTrue(creditReport.getConsumerConsentGranted());
        assertTrue(creditReport.getFcraCompliant());

        creditReport.setCreditScore(750);
        assertEquals(750, creditReport.getCreditScore());
    }

    @Test
    @DisplayName("BaseEntity should support auditing")
    void testBaseEntityAuditing() {
        assertNull(webhookConfig.getCreatedBy());
        assertNull(webhookConfig.getUpdatedBy());
        assertNull(webhookConfig.getVersion());
    }
}
