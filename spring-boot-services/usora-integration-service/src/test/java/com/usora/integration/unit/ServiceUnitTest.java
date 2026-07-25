package com.usora.integration.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usora.integration.dto.RequestDto;
import com.usora.integration.dto.ResponseDto;
import com.usora.integration.entity.*;
import com.usora.integration.event.DomainEventPublisher;
import com.usora.integration.exception.BusinessException;
import com.usora.integration.mapper.EntityMapper;
import com.usora.integration.repository.TenantRepository.*;
import com.usora.integration.security.TenantContext;
import com.usora.integration.service.DomainService;
import com.usora.integration.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ServiceUnitTest {

    @Mock private WebhookConfigRepository webhookConfigRepository;
    @Mock private IntegrationProviderRepository integrationProviderRepository;
    @Mock private BankingLinkRepository bankingLinkRepository;
    @Mock private GovernmentVerificationRepository governmentVerificationRepository;
    @Mock private CreditReportRepository creditReportRepository;
    @Mock private EntityMapper entityMapper;
    @Mock private DomainEventPublisher eventPublisher;
    @Mock private HashingUtil hashingUtil;
    @Mock private EncryptionUtil encryptionUtil;
    @Mock private ValidationUtil validationUtil;
    @Mock private IdGenerator idGenerator;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private RedissonClient redissonClient;
    @Mock private WebClient webClient;
    @Mock private ValueOperations<String, Object> valueOperations;
    @Mock private RLock lock;

    private ObjectMapper objectMapper;
    private DomainService domainService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        domainService = new DomainService(
                webhookConfigRepository, integrationProviderRepository,
                bankingLinkRepository, governmentVerificationRepository,
                creditReportRepository, entityMapper, eventPublisher,
                hashingUtil, encryptionUtil, validationUtil, idGenerator,
                redisTemplate, redissonClient, null, objectMapper);
    }

    @Nested
    @DisplayName("Webhook Operations")
    class WebhookTests {

        @Test
        @DisplayName("Should ingest webhook successfully")
        void shouldIngestWebhook() throws Exception {
            String tenantId = "tenant-1";
            String integrationId = "integration-1";
            String idempotencyKey = "key-1";

            RequestDto.WebhookIngestRequest request = RequestDto.WebhookIngestRequest.builder()
                    .eventType("user.created")
                    .idempotencyKey(idempotencyKey)
                    .payload(Map.of("userId", "123"))
                    .build();

            WebhookConfig config = new WebhookConfig();
            config.setEnabled(true);
            config.setEndpointId(integrationId);
            config.setTenantId(tenantId);
            config.setAuthType(WebhookConfig.AuthType.NONE);
            config.setStatus(WebhookConfig.WebhookStatus.ACTIVE);
            config.setMaxPayloadSizeBytes(10485760L);

            when(webhookConfigRepository.findByTenantIdAndEndpointId(tenantId, integrationId))
                    .thenReturn(Optional.of(config));
            when(redissonClient.getLock(anyString())).thenReturn(lock);
            when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get(anyString())).thenReturn(null);
            when(idGenerator.generateEventId()).thenReturn("event-1");
            when(idGenerator.generateCorrelationId()).thenReturn("corr-1");

            ResponseDto.WebhookIngestResponse response = domainService.ingestWebhook(tenantId, integrationId, request);

            assertNotNull(response);
            assertEquals("ACCEPTED", response.getStatus());
            assertEquals("key-1", response.getIdempotencyKey());

            verify(redisTemplate.opsForValue()).set(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("Should reject duplicate webhook")
        void shouldRejectDuplicateWebhook() throws Exception {
            String tenantId = "tenant-1";
            String integrationId = "integration-1";

            RequestDto.WebhookIngestRequest request = RequestDto.WebhookIngestRequest.builder()
                    .eventType("user.created")
                    .idempotencyKey("dup-key")
                    .payload(Map.of("userId", "123"))
                    .build();

            WebhookConfig config = new WebhookConfig();
            config.setEnabled(true);
            config.setEndpointId(integrationId);
            config.setTenantId(tenantId);
            config.setAuthType(WebhookConfig.AuthType.NONE);
            config.setStatus(WebhookConfig.WebhookStatus.ACTIVE);
            config.setMaxPayloadSizeBytes(10485760L);

            when(webhookConfigRepository.findByTenantIdAndEndpointId(tenantId, integrationId))
                    .thenReturn(Optional.of(config));
            when(redissonClient.getLock(anyString())).thenReturn(lock);
            when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get(anyString())).thenReturn("existing-event-id");

            ResponseDto.WebhookIngestResponse response = domainService.ingestWebhook(tenantId, integrationId, request);

            assertEquals("DUPLICATE", response.getStatus());
            assertTrue(response.isDuplicate());
        }

        @Test
        @DisplayName("Should throw when webhook config not found")
        void shouldThrowWhenConfigNotFound() {
            when(webhookConfigRepository.findByTenantIdAndEndpointId("tenant-1", "unknown"))
                    .thenReturn(Optional.empty());

            RequestDto.WebhookIngestRequest request = RequestDto.WebhookIngestRequest.builder()
                    .eventType("test")
                    .idempotencyKey("key")
                    .payload(Map.of())
                    .build();

            assertThrows(BusinessException.class,
                    () -> domainService.ingestWebhook("tenant-1", "unknown", request));
        }
    }

    @Nested
    @DisplayName("Banking Operations")
    class BankingTests {

        @Test
        @DisplayName("Should initiate account linking")
        void shouldInitiateAccountLinking() {
            String tenantId = "tenant-1";
            RequestDto.BankingLinkRequest request = RequestDto.BankingLinkRequest.builder()
                    .providerName("plaid")
                    .redirectUri("https://example.com/callback")
                    .build();

            IntegrationProvider provider = new IntegrationProvider();
            provider.setEnabled(true);
            provider.setProviderName("plaid");
            provider.setCircuitBreakerState(IntegrationProvider.CircuitBreakerState.CLOSED);

            when(integrationProviderRepository.findByTenantIdAndProviderTypeAndProviderName(
                    eq(tenantId), eq(IntegrationProvider.ProviderType.BANKING), eq("plaid")))
                    .thenReturn(Optional.of(provider));

            when(bankingLinkRepository.save(any(BankingLink.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            TenantContext.setCurrentUserId("user-1");

            ResponseDto.BankingLinkResponse response = domainService.initiateAccountLinking(tenantId, request);

            assertNotNull(response);
            verify(bankingLinkRepository).save(any(BankingLink.class));
        }
    }

    @Nested
    @DisplayName("Government Verification Operations")
    class GovernmentTests {

        @Test
        @DisplayName("Should verify government identity")
        void shouldVerifyGovernmentIdentity() {
            String tenantId = "tenant-1";
            RequestDto.GovernmentVerificationRequest request = RequestDto.GovernmentVerificationRequest.builder()
                    .verificationType(RequestDto.GovernmentVerificationType.EIDAS)
                    .userId("user-1")
                    .countryCode("DE")
                    .identityData(Map.of("documentId", "ABC123"))
                    .consentGranted(true)
                    .build();

            TenantContext.setCurrentUserId("user-1");

            when(governmentVerificationRepository.save(any(GovernmentVerification.class)))
                    .thenAnswer(invocation -> {
                        GovernmentVerification v = invocation.getArgument(0);
                        v.setVerificationId(UUID.randomUUID());
                        return v;
                    });

            ResponseDto.GovernmentVerificationResponse response = domainService.verifyGovernment(tenantId, request);

            assertNotNull(response);
            assertEquals("EIDAS", response.getVerificationType());
        }

        @Test
        @DisplayName("Should reject government verification without consent")
        void shouldRejectWithoutConsent() {
            RequestDto.GovernmentVerificationRequest request = RequestDto.GovernmentVerificationRequest.builder()
                    .verificationType(RequestDto.GovernmentVerificationType.PASSPORT)
                    .userId("user-1")
                    .identityData(Map.of())
                    .consentGranted(false)
                    .build();

            assertThrows(BusinessException.class,
                    () -> domainService.verifyGovernment("tenant-1", request));
        }
    }

    @Nested
    @DisplayName("Credit Bureau Operations")
    class CreditTests {

        @Test
        @DisplayName("Should verify credit identity")
        void shouldVerifyCreditIdentity() {
            String tenantId = "tenant-1";
            RequestDto.CreditVerificationRequest request = RequestDto.CreditVerificationRequest.builder()
                    .userId("user-1")
                    .bureauName("experian")
                    .identityData(Map.of("ssn", "***-****-1234"))
                    .consumerConsentGranted(true)
                    .consentId("consent-1")
                    .build();

            IntegrationProvider provider = new IntegrationProvider();
            provider.setEnabled(true);
            provider.setCircuitBreakerState(IntegrationProvider.CircuitBreakerState.CLOSED);

            TenantContext.setCurrentUserId("user-1");

            when(integrationProviderRepository.findByTenantIdAndProviderTypeAndProviderName(
                    eq(tenantId), eq(IntegrationProvider.ProviderType.CREDIT), eq("experian")))
                    .thenReturn(Optional.of(provider));

            when(creditReportRepository.save(any(CreditReport.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            ResponseDto.CreditVerificationResponse response = domainService.verifyIdentity(tenantId, request);

            assertNotNull(response);
            verify(creditReportRepository).save(any(CreditReport.class));
        }

        @Test
        @DisplayName("Should reject credit verification without consent")
        void shouldRejectCreditWithoutConsent() {
            RequestDto.CreditVerificationRequest request = RequestDto.CreditVerificationRequest.builder()
                    .userId("user-1")
                    .identityData(Map.of())
                    .consumerConsentGranted(false)
                    .build();

            assertThrows(BusinessException.class,
                    () -> domainService.verifyIdentity("tenant-1", request));
        }
    }
}
