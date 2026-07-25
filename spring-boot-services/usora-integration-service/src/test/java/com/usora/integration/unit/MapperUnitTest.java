package com.usora.integration.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.usora.integration.dto.RequestDto;
import com.usora.integration.dto.ResponseDto;
import com.usora.integration.entity.*;
import com.usora.integration.mapper.EntityMapper;
import com.usora.integration.util.EncryptionUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class MapperUnitTest {

    private EntityMapper entityMapper;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        EncryptionUtil encryptionUtil = new EncryptionUtil("TestEncryptionKeyForUnitTestingPurposesOnly");
        entityMapper = new EntityMapper();
        entityMapper.objectMapper = objectMapper;
        entityMapper.encryptionUtil = encryptionUtil;
        entityMapper.idGenerator = null;
    }

    @Test
    @DisplayName("Should map WebhookConfigRequest to WebhookConfig entity")
    void testWebhookConfigMapping() {
        RequestDto.WebhookConfigRequest request = RequestDto.WebhookConfigRequest.builder()
                .endpointId("endpoint-1")
                .url("https://example.com/webhook")
                .description("Test webhook")
                .events(Set.of("user.created", "user.updated"))
                .secret("test-secret")
                .authType(RequestDto.WebhookConfigRequest.AuthType.HMAC)
                .retryCount(3)
                .retryIntervalMs(2000L)
                .build();

        WebhookConfig entity = entityMapper.toWebhookConfig(request, "tenant-1");

        assertNotNull(entity);
        assertEquals("tenant-1", entity.getTenantId());
        assertEquals("endpoint-1", entity.getEndpointId());
        assertEquals("https://example.com/webhook", entity.getUrl());
        assertEquals("test-secret", entity.getSecret());
        assertEquals(WebhookConfig.AuthType.HMAC, entity.getAuthType());
        assertEquals(WebhookConfig.WebhookStatus.ACTIVE, entity.getStatus());
        assertTrue(entity.getEnabled());
        assertEquals(3, entity.getRetryCount());
        assertEquals(2000L, entity.getRetryIntervalMs());
    }

    @Test
    @DisplayName("Should map WebhookConfig entity to response DTO")
    void testWebhookConfigResponseMapping() {
        WebhookConfig entity = new WebhookConfig();
        entity.setId(UUID.randomUUID());
        entity.setTenantId("tenant-1");
        entity.setEndpointId("endpoint-1");
        entity.setUrl("https://example.com/webhook");
        entity.setEvents(Set.of("event1", "event2"));
        entity.setStatus(WebhookConfig.WebhookStatus.ACTIVE);
        entity.setAuthType(WebhookConfig.AuthType.HMAC);
        entity.setRetryCount(5);
        entity.setRetryIntervalMs(1000L);
        entity.setRateLimitPerSecond(100);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());

        ResponseDto.WebhookConfigResponse response = entityMapper.toWebhookConfigResponse(entity);

        assertNotNull(response);
        assertEquals(entity.getId(), response.getId());
        assertEquals("endpoint-1", response.getEndpointId());
        assertEquals(2, response.getEvents().size());
        assertEquals("ACTIVE", response.getStatus());
    }

    @Test
    @DisplayName("Should map IntegrationProvider entity to response")
    void testIntegrationProviderResponseMapping() {
        IntegrationProvider entity = new IntegrationProvider();
        entity.setId(UUID.randomUUID());
        entity.setTenantId("tenant-1");
        entity.setProviderType(IntegrationProvider.ProviderType.BANKING);
        entity.setProviderName("plaid");
        entity.setEnabled(true);
        entity.setPriority(1);
        entity.setCircuitBreakerState(IntegrationProvider.CircuitBreakerState.CLOSED);
        entity.setFailureCount(0);
        entity.setRateLimitRpm(100);
        entity.setCreatedAt(Instant.now());

        ResponseDto.IntegrationProviderResponse response = entityMapper.toIntegrationProviderResponse(entity);

        assertNotNull(response);
        assertEquals("BANKING", response.getProviderType());
        assertEquals("plaid", response.getProviderName());
        assertTrue(response.isEnabled());
        assertEquals("CLOSED", response.getCircuitBreakerState());
    }

    @Test
    @DisplayName("Should map BankingLink entity to response")
    void testBankingLinkResponseMapping() {
        BankingLink entity = new BankingLink();
        entity.setId(UUID.randomUUID());
        entity.setTenantId("tenant-1");
        entity.setProviderName("plaid");
        entity.setStatus(BankingLink.LinkStatus.VERIFIED);
        entity.setAccountId("acc-123");
        entity.setInstitutionName("Test Bank");
        entity.setLinkedAt(Instant.now());
        entity.setKycCompleted(true);

        ResponseDto.BankingLinkResponse response = entityMapper.toBankingLinkResponse(entity);

        assertNotNull(response);
        assertEquals("plaid", response.getProviderName());
        assertEquals("VERIFIED", response.getStatus());
        assertEquals("acc-123", response.getAccountId());
        assertTrue(response.isKycCompleted());
    }

    @Test
    @DisplayName("Should map GovernmentVerification entity to response")
    void testGovernmentVerificationResponseMapping() {
        GovernmentVerification entity = new GovernmentVerification();
        entity.setVerificationId(UUID.randomUUID());
        entity.setVerificationType(GovernmentVerification.VerificationType.EIDAS);
        entity.setStatus(GovernmentVerification.VerificationStatus.VERIFIED);
        entity.setConfidenceScore(0.97);
        entity.setCountryCode("DE");
        entity.setVerifiedAt(Instant.now());

        ResponseDto.GovernmentVerificationResponse response = entityMapper.toGovernmentVerificationResponse(entity);

        assertNotNull(response);
        assertEquals("EIDAS", response.getVerificationType());
        assertEquals("VERIFIED", response.getStatus());
        assertTrue(response.isVerified());
        assertEquals(0.97, response.getConfidenceScore());
    }

    @Test
    @DisplayName("Should map CreditReport entity to response")
    void testCreditReportResponseMapping() {
        CreditReport entity = new CreditReport();
        entity.setId(UUID.randomUUID());
        entity.setBureauName("experian");
        entity.setReportType(CreditReport.ReportType.CREDIT_REPORT);
        entity.setStatus(CreditReport.CreditReportStatus.COMPLETED);
        entity.setCreditScore(750);
        entity.setConfidenceScore(0.95);
        entity.setIdentityMatch(true);
        entity.setQueriedAt(Instant.now());

        ResponseDto.CreditReportResponse response = entityMapper.toCreditReportResponse(entity);

        assertNotNull(response);
        assertEquals("experian", response.getBureauName());
        assertEquals(750, response.getCreditScore());
        assertTrue(response.isIdentityMatch());
    }

    @Test
    @DisplayName("Should map to CreditFraudCheckResponse")
    void testCreditFraudCheckResponseMapping() {
        CreditReport entity = new CreditReport();
        entity.setId(UUID.randomUUID());
        entity.setBureauName("transunion");
        entity.setStatus(CreditReport.CreditReportStatus.COMPLETED);
        entity.setFraudIndicators("[\"suspicious_activity\",\"address_mismatch\"]");
        entity.setConfidenceScore(0.88);

        ResponseDto.CreditFraudCheckResponse response = entityMapper.toCreditFraudCheckResponse(entity);

        assertNotNull(response);
        assertTrue(response.isFraudDetected());
        assertEquals(2, response.getFraudIndicators().size());
    }

    @Test
    @DisplayName("Should compute credit score range and rating")
    void testCreditScoreRange() {
        CreditReport entity = new CreditReport();
        entity.setId(UUID.randomUUID());
        entity.setBureauName("experian");
        entity.setCreditScore(750);

        ResponseDto.CreditScoreResponse response = entityMapper.toCreditScoreResponse(entity);

        assertNotNull(response);
        assertEquals(750, response.getCreditScore());
        assertEquals("740-799", response.getScoreRange());
        assertEquals("Very Good", response.getRating());
    }

    @Test
    @DisplayName("Should map webhook ingest response")
    void testWebhookIngestResponse() {
        ResponseDto.WebhookIngestResponse response = entityMapper.toWebhookIngestResponse(
                "event-1", "ACCEPTED", "corr-1", "key-1", false);

        assertNotNull(response);
        assertEquals("event-1", response.getId());
        assertEquals("ACCEPTED", response.getStatus());
        assertEquals("corr-1", response.getCorrelationId());
        assertFalse(response.isDuplicate());
    }
}
