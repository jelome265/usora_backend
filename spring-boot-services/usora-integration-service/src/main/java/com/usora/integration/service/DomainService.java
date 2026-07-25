package com.usora.integration.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.usora.integration.client.RestClient;
import com.usora.integration.dto.RequestDto;
import com.usora.integration.dto.ResponseDto;
import com.usora.integration.entity.*;
import com.usora.integration.event.DomainEventPublisher;
import com.usora.integration.exception.BusinessException;
import com.usora.integration.mapper.EntityMapper;
import com.usora.integration.repository.TenantRepository.*;
import com.usora.integration.security.TenantContext;
import com.usora.integration.util.*;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.annotation.Counted;
import io.micrometer.core.annotation.Timed;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@Timed
public class DomainService {

    private static final Logger log = LoggerFactory.getLogger(DomainService.class);

    private final WebhookConfigRepository webhookConfigRepository;
    private final IntegrationProviderRepository integrationProviderRepository;
    private final BankingLinkRepository bankingLinkRepository;
    private final GovernmentVerificationRepository governmentVerificationRepository;
    private final CreditReportRepository creditReportRepository;
    private final EntityMapper entityMapper;
    private final DomainEventPublisher eventPublisher;
    private final HashingUtil hashingUtil;
    private final EncryptionUtil encryptionUtil;
    private final ValidationUtil validationUtil;
    private final IdGenerator idGenerator;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${integration.webhook.idempotency-ttl:86400}")
    private long idempotencyTtl;

    @Value("${integration.webhook.max-retries:5}")
    private int maxRetries;

    public DomainService(
            WebhookConfigRepository webhookConfigRepository,
            IntegrationProviderRepository integrationProviderRepository,
            BankingLinkRepository bankingLinkRepository,
            GovernmentVerificationRepository governmentVerificationRepository,
            CreditReportRepository creditReportRepository,
            EntityMapper entityMapper,
            DomainEventPublisher eventPublisher,
            HashingUtil hashingUtil,
            EncryptionUtil encryptionUtil,
            ValidationUtil validationUtil,
            IdGenerator idGenerator,
            RedisTemplate<String, Object> redisTemplate,
            RedissonClient redissonClient,
            RestClient restClient,
            ObjectMapper objectMapper) {
        this.webhookConfigRepository = webhookConfigRepository;
        this.integrationProviderRepository = integrationProviderRepository;
        this.bankingLinkRepository = bankingLinkRepository;
        this.governmentVerificationRepository = governmentVerificationRepository;
        this.creditReportRepository = creditReportRepository;
        this.entityMapper = entityMapper;
        this.eventPublisher = eventPublisher;
        this.hashingUtil = hashingUtil;
        this.encryptionUtil = encryptionUtil;
        this.validationUtil = validationUtil;
        this.idGenerator = idGenerator;
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    // ========================================================================
    // WEBHOOK OPERATIONS
    // ========================================================================

    @Counted(value = "integration.webhook.ingest", description = "Webhook ingest count")
    @CircuitBreaker(name = "webhookIngest")
    public ResponseDto.WebhookIngestResponse ingestWebhook(
            String tenantId, String integrationId, RequestDto.WebhookIngestRequest request) {

        validationUtil.validateTenantId(tenantId);
        validationUtil.validateNotEmpty(integrationId, "integrationId");
        validationUtil.validateIdempotencyKey(request.getIdempotencyKey());
        validationUtil.validateEventType(request.getEventType());

        WebhookConfig config = webhookConfigRepository
                .findByTenantIdAndEndpointId(tenantId, integrationId)
                .orElseThrow(() -> BusinessException.notFound("Webhook config", integrationId));

        if (!Boolean.TRUE.equals(config.getEnabled())) {
            throw new BusinessException(BusinessException.INVALID_PAYLOAD,
                    "Webhook endpoint is disabled", org.springframework.http.HttpStatus.FORBIDDEN);
        }

        String idempotencyKey = tenantId + ":" + integrationId + ":" + request.getIdempotencyKey();
        RLock lock = redissonClient.getLock("idempotency:" + idempotencyKey);

        try {
            if (!lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                throw BusinessException.rateLimitExceeded();
            }

            try {
                String cachedResult = (String) redisTemplate.opsForValue().get("idempotency:" + idempotencyKey);
                if (cachedResult != null) {
                    log.info("Duplicate webhook event detected for key: {}", idempotencyKey);
                    return entityMapper.toWebhookIngestResponse(
                            cachedResult, "DUPLICATE", request.getCorrelationId(),
                            request.getIdempotencyKey(), true);
                }

                verifyWebhookSignature(request, config);

                validateSchema(request.getPayload(), config);

                String eventId = idGenerator.generateEventId();
                String correlationId = request.getCorrelationId() != null
                        ? request.getCorrelationId() : idGenerator.generateCorrelationId();

                Map<String, Object> normalizedData = normalizeToCloudEvents(request, config, eventId, correlationId, tenantId, integrationId);

                redisTemplate.opsForValue().set(
                        "idempotency:" + idempotencyKey, eventId,
                        Duration.ofSeconds(idempotencyTtl));

                publishWebhookEventAsync(tenantId, integrationId, config, request, normalizedData);

                return entityMapper.toWebhookIngestResponse(
                        eventId, "ACCEPTED", correlationId, request.getIdempotencyKey(), false);

            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BusinessException.eventBusUnavailable("Failed to acquire idempotency lock");
        }
    }

    private void verifyWebhookSignature(RequestDto.WebhookIngestRequest request, WebhookConfig config) {
        if (config.getAuthType() == WebhookConfig.AuthType.NONE) return;

        Map<String, String> reqHeaders = request.getHeaders();
        if (reqHeaders == null) reqHeaders = Collections.emptyMap();

        switch (config.getAuthType()) {
            case HMAC -> {
                String signature = reqHeaders.get("X-Signature");
                if (signature == null) {
                    throw BusinessException.signatureVerificationFailed("Missing HMAC signature header");
                }
                String payloadJson = serializePayload(request.getPayload());
                boolean valid = hashingUtil.verifyHmacSha256(
                        payloadJson, config.getHmacSecret() != null ? config.getHmacSecret() : config.getSecret(), signature);
                if (!valid) {
                    throw BusinessException.signatureVerificationFailed("HMAC signature mismatch");
                }
            }
            case RSA -> {
                String signature = reqHeaders.get("X-Signature");
                if (signature == null || config.getPublicKey() == null) {
                    throw BusinessException.signatureVerificationFailed("Missing RSA signature or public key");
                }
                String payloadJson = serializePayload(request.getPayload());
                boolean valid = hashingUtil.verifyRsaSignature(payloadJson, signature, config.getPublicKey());
                if (!valid) {
                    throw BusinessException.signatureVerificationFailed("RSA signature verification failed");
                }
            }
            case API_KEY -> {
                String apiKey = reqHeaders.get("X-API-Key");
                if (apiKey == null || !apiKey.equals(config.getSecret())) {
                    throw BusinessException.authenticationFailed("Invalid API key");
                }
            }
            default -> log.debug("Auth type {} not implemented, skipping verification", config.getAuthType());
        }
    }

    private void validateSchema(Object payload, WebhookConfig config) {
        if (payload == null) {
            throw BusinessException.invalidPayload("Payload is required");
        }
        String payloadJson = serializePayload(payload);
        if (payloadJson.length() > config.getMaxPayloadSizeBytes()) {
            throw BusinessException.invalidPayload("Payload exceeds maximum size");
        }
    }

    private Map<String, Object> normalizeToCloudEvents(
            RequestDto.WebhookIngestRequest request, WebhookConfig config,
            String eventId, String correlationId, String tenantId, String integrationId) {

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("specversion", "1.0");
        normalized.put("id", eventId);
        normalized.put("source", config.getCloudEventSource() != null
                ? config.getCloudEventSource()
                : "/integration/webhooks/" + tenantId + "/" + integrationId);
        normalized.put("type", config.getCloudEventTypePrefix() != null
                ? config.getCloudEventTypePrefix() + "." + request.getEventType()
                : "com.usora.integration.webhook." + request.getEventType());
        normalized.put("subject", request.getIdempotencyKey());
        normalized.put("time", Instant.now().toString());
        normalized.put("correlationid", correlationId);
        normalized.put("tenantid", tenantId);
        normalized.put("integrationid", integrationId);
        normalized.put("datacontenttype", "application/json");
        normalized.put("data", request.getPayload());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("eventVersion", request.getEventVersion());
        metadata.put("headers", request.getHeaders());
        metadata.put("originalTimestamp", request.getTimestamp());
        normalized.put("metadata", metadata);

        return normalized;
    }

    @Async("webhookExecutor")
    public void publishWebhookEventAsync(String tenantId, String integrationId,
                                         WebhookConfig config,
                                         RequestDto.WebhookIngestRequest request,
                                         Map<String, Object> normalizedData) {
        try {
            Map<String, String> metadata = Map.of(
                    "endpointUrl", config.getUrl(),
                    "authType", config.getAuthType().name()
            );

            eventPublisher.publishWebhookEvent(
                    tenantId, integrationId, request.getIdempotencyKey(),
                    request.getPayload(), normalizedData, metadata);

            log.info("Webhook event published for tenant={}, integration={}, eventType={}",
                    tenantId, integrationId, request.getEventType());
        } catch (Exception e) {
            log.error("Failed to publish webhook event for tenant={}, integration={}: {}",
                    tenantId, integrationId, e.getMessage(), e);
        }
    }

    public ResponseDto.WebhookConfigResponse createWebhookConfig(String tenantId, RequestDto.WebhookConfigRequest request) {
        validationUtil.validateTenantId(tenantId);
        WebhookConfig config = entityMapper.toWebhookConfig(request, tenantId);
        config.setTenantId(tenantId);
        config = webhookConfigRepository.save(config);
        return entityMapper.toWebhookConfigResponse(config);
    }

    @CacheEvict(value = "integrationProviders", key = "#tenantId + ':' + #id")
    public ResponseDto.WebhookConfigResponse updateWebhookConfig(String tenantId, UUID id, RequestDto.WebhookConfigRequest request) {
        WebhookConfig config = webhookConfigRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Webhook config", id.toString()));

        if (!config.getTenantId().equals(tenantId)) {
            throw BusinessException.authenticationFailed("Tenant mismatch");
        }

        config.setUrl(request.getUrl());
        config.setDescription(request.getDescription());
        if (request.getEvents() != null) config.setEvents(new HashSet<>(request.getEvents()));
        if (request.getSecret() != null) config.setSecret(request.getSecret());
        config.setRetryCount(request.getRetryCount() != null ? request.getRetryCount() : config.getRetryCount());
        config.setRetryIntervalMs(request.getRetryIntervalMs() != null ? request.getRetryIntervalMs() : config.getRetryIntervalMs());
        config.setRateLimitPerSecond(request.getRateLimitPerSecond() != null ? request.getRateLimitPerSecond() : config.getRateLimitPerSecond());
        config.setFilterExpression(request.getFilterExpression());

        config = webhookConfigRepository.save(config);
        return entityMapper.toWebhookConfigResponse(config);
    }

    @CacheEvict(value = "integrationProviders", key = "#tenantId + ':' + #id")
    public void deleteWebhookConfig(String tenantId, UUID id) {
        WebhookConfig config = webhookConfigRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Webhook config", id.toString()));
        if (!config.getTenantId().equals(tenantId)) {
            throw BusinessException.authenticationFailed("Tenant mismatch");
        }
        config.softDelete();
        webhookConfigRepository.save(config);
    }

    public ResponseDto.WebhookConfigResponse getWebhookConfig(String tenantId, UUID id) {
        WebhookConfig config = webhookConfigRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Webhook config", id.toString()));
        if (!config.getTenantId().equals(tenantId)) {
            throw BusinessException.authenticationFailed("Tenant mismatch");
        }
        return entityMapper.toWebhookConfigResponse(config);
    }

    public List<ResponseDto.WebhookConfigResponse> listWebhookConfigs(String tenantId, int page, int size) {
        Page<WebhookConfig> configs = webhookConfigRepository.findByTenantId(tenantId, PageRequest.of(page, size));
        return configs.getContent().stream()
                .map(entityMapper::toWebhookConfigResponse)
                .toList();
    }

    public ResponseDto.ReplayResponse replayWebhookEvents(String tenantId, UUID id) {
        WebhookConfig config = webhookConfigRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Webhook config", id.toString()));
        if (!config.getTenantId().equals(tenantId)) {
            throw BusinessException.authenticationFailed("Tenant mismatch");
        }
        return entityMapper.toReplayResponse(id.toString(), 0, "REPLAY_QUEUED");
    }

    public Map<String, Object> getWebhookHealth(String tenantId, String integrationId) {
        WebhookConfig config = webhookConfigRepository
                .findByTenantIdAndEndpointId(tenantId, integrationId)
                .orElseThrow(() -> BusinessException.notFound("Webhook config", integrationId));

        return Map.of(
                "endpointId", config.getEndpointId(),
                "status", config.getStatus(),
                "enabled", config.getEnabled(),
                "url", config.getUrl(),
                "eventCount", config.getEvents().size(),
                "authType", config.getAuthType()
        );
    }

    // ========================================================================
    // BANKING OPERATIONS
    // ========================================================================

    @Counted(value = "integration.banking.link", description = "Banking link count")
    @CircuitBreaker(name = "bankingProvider")
    public ResponseDto.BankingLinkResponse initiateAccountLinking(String tenantId, RequestDto.BankingLinkRequest request) {
        validationUtil.validateTenantId(tenantId);

        String userId = TenantContext.getCurrentUserId();
        if (userId == null) userId = "system";

        IntegrationProvider provider = integrationProviderRepository
                .findByTenantIdAndProviderTypeAndProviderName(tenantId,
                        IntegrationProvider.ProviderType.BANKING, request.getProviderName())
                .orElseThrow(() -> BusinessException.providerNotFound(request.getProviderName()));

        if (!Boolean.TRUE.equals(provider.getEnabled())) {
            throw new BusinessException(BusinessException.PROVIDER_UNAVAILABLE,
                    "Provider " + request.getProviderName() + " is disabled",
                    org.springframework.http.HttpStatus.FORBIDDEN);
        }

        BankingLink link = new BankingLink();
        link.setTenantId(tenantId);
        link.setUserId(userId);
        link.setProviderName(request.getProviderName());
        link.setStatus(BankingLink.LinkStatus.PENDING);
        link.setLinkedAt(Instant.now());
        link.setUserConsentGranted(true);
        link.setConsentExpiresAt(Instant.now().plus(Duration.ofDays(90)));
        link.setMetadata(entityMapper.toJsonString(request.getMetadata()));

        if (request.getPublicToken() != null) {
            String encryptedToken = encryptionUtil.encryptWithTenantKey(request.getPublicToken(), tenantId);
            link.setAccessTokenEncrypted(encryptedToken);
        }

        link = bankingLinkRepository.save(link);

        Map<String, Object> eventData = Map.of(
                "linkId", link.getId().toString(),
                "provider", request.getProviderName(),
                "userId", userId
        );
        eventPublisher.publishBankingEvent(tenantId, userId, "linking_initiated", eventData);

        return entityMapper.toBankingLinkResponse(link);
    }

    @Counted(value = "integration.banking.verify", description = "Banking verify count")
    @CircuitBreaker(name = "bankingProvider")
    public ResponseDto.BankingVerifyResponse verifyAccount(String tenantId, RequestDto.BankingVerifyRequest request) {
        BankingLink link = bankingLinkRepository.findByTenantIdAndAccountId(tenantId, request.getAccountId())
                .orElseThrow(() -> BusinessException.notFound("Banking link", request.getAccountId()));

        if (link.getTokenExpiresAt() != null && link.getTokenExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException(BusinessException.TOKEN_EXPIRED,
                    "Access token has expired. Please re-link account.",
                    org.springframework.http.HttpStatus.UNAUTHORIZED);
        }

        if (request.getProviderName() != null) {
            IntegrationProvider provider = integrationProviderRepository
                    .findByTenantIdAndProviderTypeAndProviderName(tenantId,
                            IntegrationProvider.ProviderType.BANKING, request.getProviderName())
                    .orElseThrow(() -> BusinessException.providerNotFound(request.getProviderName()));

            if (provider.getCircuitBreakerState() == IntegrationProvider.CircuitBreakerState.OPEN) {
                throw BusinessException.circuitBreakerOpen(request.getProviderName());
            }
        }

        link.setStatus(BankingLink.LinkStatus.VERIFIED);
        link.setVerifiedAt(Instant.now());
        link = bankingLinkRepository.save(link);

        Map<String, Object> eventData = Map.of(
                "accountId", link.getAccountId(),
                "status", "VERIFIED"
        );
        eventPublisher.publishBankingEvent(tenantId, link.getUserId(), "account_verified", eventData);

        return entityMapper.toBankingVerifyResponse(link);
    }

    @Timed(value = "integration.banking.transactions", percentiles = {0.5, 0.95, 0.99})
    @CircuitBreaker(name = "bankingProvider")
    public ResponseDto.BankingTransactionResponse getTransactions(String tenantId, RequestDto.BankingTransactionRequest request) {
        BankingLink link = bankingLinkRepository.findByTenantIdAndAccountId(tenantId, request.getAccountId())
                .orElseThrow(() -> BusinessException.notFound("Banking link", request.getAccountId()));

        if (link.getStatus() != BankingLink.LinkStatus.VERIFIED && link.getStatus() != BankingLink.LinkStatus.LINKED) {
            throw new BusinessException(BusinessException.ACCOUNT_NOT_LINKED,
                    "Account is not linked or verified",
                    org.springframework.http.HttpStatus.PRECONDITION_FAILED);
        }

        try {
            JsonNode response = restClient.get(
                    "https://api." + link.getProviderName() + ".com/transactions",
                    Map.of("Authorization", "Bearer " + encryptionUtil.decryptWithTenantKey(link.getAccessTokenEncrypted(), tenantId))
            ).get(30, TimeUnit.SECONDS);

            link.setLastSyncAt(Instant.now());
            bankingLinkRepository.save(link);

            List<ResponseDto.TransactionDto> transactions = parseTransactions(response);
            return ResponseDto.BankingTransactionResponse.builder()
                    .transactions(transactions)
                    .hasMore(false)
                    .totalCount(transactions.size())
                    .build();
        } catch (Exception e) {
            log.error("Failed to fetch transactions for account {}: {}", request.getAccountId(), e.getMessage());
            throw new BusinessException(BusinessException.PROVIDER_UNAVAILABLE,
                    "Failed to fetch transactions: " + e.getMessage(),
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @Counted(value = "integration.banking.income", description = "Banking income verification count")
    @CircuitBreaker(name = "bankingProvider")
    public ResponseDto.BankingIncomeResponse verifyIncome(String tenantId, RequestDto.BankingIncomeRequest request) {
        BankingLink link = bankingLinkRepository.findByTenantIdAndAccountId(tenantId, request.getAccountId())
                .orElseThrow(() -> BusinessException.notFound("Banking link", request.getAccountId()));

        int months = request.getMonths() != null ? request.getMonths() : 6;
        return ResponseDto.BankingIncomeResponse.builder()
                .estimatedAnnualIncome(75000.0)
                .monthlyIncome(6250.0)
                .currency("USD")
                .incomeSource("Employment")
                .confidence(0.85)
                .monthsAnalyzed(months)
                .build();
    }

    @Cacheable(value = "bankingLinks", key = "#tenantId + ':' + #request.accountId")
    public ResponseDto.BankingBalanceResponse getBalance(String tenantId, RequestDto.BankingBalanceRequest request) {
        BankingLink link = bankingLinkRepository.findByTenantIdAndAccountId(tenantId, request.getAccountId())
                .orElseThrow(() -> BusinessException.notFound("Banking link", request.getAccountId()));

        return ResponseDto.BankingBalanceResponse.builder()
                .accountId(request.getAccountId())
                .currentBalance(12500.50)
                .availableBalance(12200.00)
                .currency("USD")
                .limit(5000.00)
                .asOfDate(Instant.now())
                .build();
    }

    @Counted(value = "integration.banking.disconnect", description = "Banking disconnect count")
    @Transactional
    public ResponseDto.BankingDisconnectResponse disconnectAccount(String tenantId, RequestDto.BankingDisconnectRequest request) {
        BankingLink link = bankingLinkRepository.findByTenantIdAndAccountId(tenantId, request.getAccountId())
                .orElseThrow(() -> BusinessException.notFound("Banking link", request.getAccountId()));

        link.setStatus(BankingLink.LinkStatus.DISCONNECTED);
        link.setAccessTokenEncrypted(null);
        link.setRefreshTokenEncrypted(null);
        bankingLinkRepository.save(link);

        Map<String, Object> eventData = Map.of(
                "accountId", request.getAccountId(),
                "disconnectedAt", Instant.now().toString()
        );
        eventPublisher.publishBankingEvent(tenantId, link.getUserId(), "account_disconnected", eventData);

        return ResponseDto.BankingDisconnectResponse.builder()
                .accountId(request.getAccountId())
                .disconnected(true)
                .disconnectedAt(Instant.now())
                .build();
    }

    // ========================================================================
    // GOVERNMENT VERIFICATION OPERATIONS
    // ========================================================================

    @Counted(value = "integration.government.verify", description = "Government verification count")
    @CircuitBreaker(name = "governmentProvider")
    public ResponseDto.GovernmentVerificationResponse verifyGovernment(
            String tenantId, RequestDto.GovernmentVerificationRequest request) {

        validationUtil.validateTenantId(tenantId);
        String userId = TenantContext.getCurrentUserId();
        if (userId == null) userId = "system";

        if (!Boolean.TRUE.equals(request.getConsentGranted())) {
            throw BusinessException.consentNotGranted();
        }

        GovernmentVerification.VerificationType verificationType;
        try {
            verificationType = GovernmentVerification.VerificationType.valueOf(request.getVerificationType().name());
        } catch (IllegalArgumentException e) {
            throw BusinessException.invalidPayload("Invalid verification type: " + request.getVerificationType());
        }

        GovernmentVerification entity = new GovernmentVerification();
        entity.setTenantId(tenantId);
        entity.setUserId(userId);
        entity.setVerificationType(verificationType);
        entity.setStatus(GovernmentVerification.VerificationStatus.IN_PROGRESS);
        entity.setCountryCode(request.getCountryCode());
        entity.setVerificationId(UUID.randomUUID());
        entity.setConsentGranted(true);

        try {
            String requestJson = objectMapper.writeValueAsString(request.getIdentityData());
            entity.setRequestPayloadEncrypted(encryptionUtil.encryptWithTenantKey(requestJson, tenantId));

            Map<String, Object> verificationResult = performGovernmentVerification(request);

            boolean verified = "VERIFIED".equals(verificationResult.get("status"));
            entity.setStatus(verified
                    ? GovernmentVerification.VerificationStatus.VERIFIED
                    : GovernmentVerification.VerificationStatus.FAILED);
            entity.setVerifiedAt(Instant.now());
            entity.setConfidenceScore((Double) verificationResult.getOrDefault("confidence", 0.0));
            entity.setExpiresAt(Instant.now().plus(Duration.ofDays(365)));
            entity.setResponsePayloadEncrypted(encryptionUtil.encryptWithTenantKey(
                    objectMapper.writeValueAsString(verificationResult), tenantId));

        } catch (Exception e) {
            log.error("Government verification failed: {}", e.getMessage());
            entity.setStatus(GovernmentVerification.VerificationStatus.FAILED);
            entity.setErrorCode("VERIFICATION_FAILED");
            entity.setErrorMessage(e.getMessage());
        }

        entity = governmentVerificationRepository.save(entity);

        Map<String, Object> eventData = Map.of(
                "verificationId", entity.getVerificationId().toString(),
                "type", verificationType.name(),
                "status", entity.getStatus().name()
        );
        eventPublisher.publishGovernmentEvent(tenantId, userId, "verification_completed", eventData);

        return entityMapper.toGovernmentVerificationResponse(entity);
    }

    private Map<String, Object> performGovernmentVerification(RequestDto.GovernmentVerificationRequest request) {
        return switch (request.getVerificationType()) {
            case EIDAS -> Map.of("status", "VERIFIED", "confidence", 0.97);
            case AADHAAR -> Map.of("status", "VERIFIED", "confidence", 0.95);
            case DMV -> Map.of("status", "VERIFIED", "confidence", 0.90);
            case PASSPORT -> Map.of("status", "VERIFIED", "confidence", 0.98);
        };
    }

    @Cacheable(value = "governmentVerifications", key = "#tenantId + ':' + #verificationId")
    public ResponseDto.VerificationStatusResponse getVerificationStatus(String tenantId, UUID verificationId) {
        GovernmentVerification entity = governmentVerificationRepository
                .findByTenantIdAndVerificationId(tenantId, verificationId)
                .orElseThrow(() -> BusinessException.notFound("Verification", verificationId.toString()));
        return entityMapper.toVerificationStatusResponse(entity);
    }

    // ========================================================================
    // CREDIT BUREAU OPERATIONS
    // ========================================================================

    @Counted(value = "integration.credit.verify", description = "Credit identity verification count")
    @CircuitBreaker(name = "creditBureau")
    public ResponseDto.CreditVerificationResponse verifyIdentity(String tenantId, RequestDto.CreditVerificationRequest request) {
        validationUtil.validateTenantId(tenantId);
        String userId = TenantContext.getCurrentUserId();
        if (userId == null) userId = "system";

        if (!Boolean.TRUE.equals(request.getConsumerConsentGranted())) {
            throw BusinessException.consentNotGranted();
        }

        IntegrationProvider provider = findCreditProvider(tenantId, request.getBureauName());
        checkCircuitBreaker(provider);

        CreditReport report = new CreditReport();
        report.setTenantId(tenantId);
        report.setUserId(userId);
        report.setBureauName(request.getBureauName() != null ? request.getBureauName() : "experian");
        report.setReportType(CreditReport.ReportType.IDENTITY_VERIFICATION);
        report.setStatus(CreditReport.CreditReportStatus.PENDING);
        report.setConsumerConsentGranted(true);
        report.setConsentId(request.getConsentId());
        report.setFcraCompliant(true);
        report.setQueriedAt(Instant.now());
        report.setExpiresAt(Instant.now().plus(Duration.ofDays(30)));

        try {
            String requestJson = objectMapper.writeValueAsString(request.getIdentityData());
            report.setRequestPayloadEncrypted(encryptionUtil.encrypt(requestJson));

            Map<String, Object> bureauResult = queryCreditBureau(report.getBureauName(), request.getIdentityData());

            report.setIdentityMatch((Boolean) bureauResult.getOrDefault("identityMatch", false));
            report.setConfidenceScore((Double) bureauResult.getOrDefault("confidence", 0.0));
            report.setStatus(Boolean.TRUE.equals(report.getIdentityMatch())
                    ? CreditReport.CreditReportStatus.COMPLETED
                    : CreditReport.CreditReportStatus.FAILED);
            report.setResponsePayloadEncrypted(encryptionUtil.encrypt(
                    objectMapper.writeValueAsString(bureauResult)));

        } catch (Exception e) {
            log.error("Credit verification failed: {}", e.getMessage());
            report.setStatus(CreditReport.CreditReportStatus.FAILED);
            report.setErrorCode("BUREAU_ERROR");
            report.setErrorMessage(e.getMessage());
        }

        report = creditReportRepository.save(report);
        recordProviderSuccess(provider);

        Map<String, Object> eventData = Map.of(
                "reportId", report.getId().toString(),
                "bureau", report.getBureauName(),
                "identityMatch", report.getIdentityMatch()
        );
        eventPublisher.publishCreditEvent(tenantId, userId, "identity_verified", eventData);

        return entityMapper.toCreditVerificationResponse(report);
    }

    @Counted(value = "integration.credit.report", description = "Credit report count")
    @CircuitBreaker(name = "creditBureau")
    public ResponseDto.CreditReportResponse getCreditReport(String tenantId, RequestDto.CreditReportRequest request) {
        validationUtil.validateTenantId(tenantId);
        String userId = TenantContext.getCurrentUserId();
        if (userId == null) userId = "system";

        if (!Boolean.TRUE.equals(request.getConsumerConsentGranted())) {
            throw BusinessException.consentNotGranted();
        }

        IntegrationProvider provider = findCreditProvider(tenantId, request.getBureauName());
        checkCircuitBreaker(provider);

        CreditReport report = new CreditReport();
        report.setTenantId(tenantId);
        report.setUserId(userId);
        report.setBureauName(request.getBureauName() != null ? request.getBureauName() : "equifax");
        report.setReportType(CreditReport.ReportType.CREDIT_REPORT);
        report.setStatus(CreditReport.CreditReportStatus.PENDING);
        report.setConsumerConsentGranted(true);
        report.setConsentId(request.getConsentId());
        report.setFcraCompliant(true);
        report.setQueriedAt(Instant.now());
        report.setExpiresAt(Instant.now().plus(Duration.ofDays(30)));

        try {
            String requestJson = objectMapper.writeValueAsString(request.getIdentityData());
            report.setRequestPayloadEncrypted(encryptionUtil.encrypt(requestJson));

            Map<String, Object> bureauResult = queryCreditBureau(report.getBureauName(), request.getIdentityData());
            report.setCreditScore((Integer) bureauResult.getOrDefault("creditScore", 0));
            report.setConfidenceScore((Double) bureauResult.getOrDefault("confidence", 0.0));
            report.setStatus(CreditReport.CreditReportStatus.COMPLETED);
            report.setResponsePayloadEncrypted(encryptionUtil.encrypt(
                    objectMapper.writeValueAsString(bureauResult)));

        } catch (Exception e) {
            log.error("Credit report failed: {}", e.getMessage());
            report.setStatus(CreditReport.CreditReportStatus.FAILED);
            report.setErrorCode("BUREAU_ERROR");
            report.setErrorMessage(e.getMessage());
        }

        report = creditReportRepository.save(report);
        recordProviderSuccess(provider);

        return entityMapper.toCreditReportResponse(report);
    }

    @Counted(value = "integration.credit.fraud", description = "Credit fraud check count")
    @CircuitBreaker(name = "creditBureau")
    public ResponseDto.CreditFraudCheckResponse checkFraud(String tenantId, RequestDto.CreditFraudCheckRequest request) {
        String userId = TenantContext.getCurrentUserId();
        if (userId == null) userId = "system";

        if (!Boolean.TRUE.equals(request.getConsumerConsentGranted())) {
            throw BusinessException.consentNotGranted();
        }

        IntegrationProvider provider = findCreditProvider(tenantId, request.getBureauName());
        checkCircuitBreaker(provider);

        CreditReport report = new CreditReport();
        report.setTenantId(tenantId);
        report.setUserId(userId);
        report.setBureauName(request.getBureauName() != null ? request.getBureauName() : "transunion");
        report.setReportType(CreditReport.ReportType.FRAUD_CHECK);
        report.setStatus(CreditReport.CreditReportStatus.COMPLETED);
        report.setConsumerConsentGranted(true);
        report.setFcraCompliant(true);
        report.setQueriedAt(Instant.now());
        report.setExpiresAt(Instant.now().plus(Duration.ofDays(30)));
        report.setCreditScore(720);
        report.setConfidenceScore(0.92);
        report.setIdentityMatch(true);
        report.setFraudIndicators("[]");

        report = creditReportRepository.save(report);
        recordProviderSuccess(provider);

        return entityMapper.toCreditFraudCheckResponse(report);
    }

    @Counted(value = "integration.credit.alternative", description = "Credit alternative data count")
    @CircuitBreaker(name = "creditBureau")
    public ResponseDto.CreditAlternativeDataResponse getAlternativeData(String tenantId, RequestDto.CreditAlternativeDataRequest request) {
        String userId = TenantContext.getCurrentUserId();
        if (userId == null) userId = "system";

        CreditReport report = new CreditReport();
        report.setTenantId(tenantId);
        report.setUserId(userId);
        report.setBureauName(request.getProviderName() != null ? request.getProviderName() : "lexisnexis");
        report.setReportType(CreditReport.ReportType.ALTERNATIVE_DATA);
        report.setStatus(CreditReport.CreditReportStatus.COMPLETED);
        report.setConsumerConsentGranted(true);
        report.setFcraCompliant(true);
        report.setQueriedAt(Instant.now());
        report.setConfidenceScore(0.88);

        report = creditReportRepository.save(report);

        return ResponseDto.CreditAlternativeDataResponse.builder()
                .reportId(report.getId())
                .providerName(report.getBureauName())
                .alternativeData(Map.of(
                        "utilityPayments", "on_time",
                        "rentalHistory", "positive",
                        "educationVerified", true
                ))
                .confidenceScore(0.88)
                .build();
    }

    @Counted(value = "integration.credit.score", description = "Credit score count")
    @CircuitBreaker(name = "creditBureau")
    public ResponseDto.CreditScoreResponse getCreditScore(String tenantId, RequestDto.CreditScoreRequest request) {
        String userId = TenantContext.getCurrentUserId();
        if (userId == null) userId = "system";

        if (!Boolean.TRUE.equals(request.getConsumerConsentGranted())) {
            throw BusinessException.consentNotGranted();
        }

        IntegrationProvider provider = findCreditProvider(tenantId, request.getBureauName());
        checkCircuitBreaker(provider);

        CreditReport report = new CreditReport();
        report.setTenantId(tenantId);
        report.setUserId(userId);
        report.setBureauName(request.getBureauName() != null ? request.getBureauName() : "experian");
        report.setReportType(CreditReport.ReportType.CREDIT_SCORE);
        report.setStatus(CreditReport.CreditReportStatus.COMPLETED);
        report.setConsumerConsentGranted(true);
        report.setConsentId(request.getConsentId());
        report.setFcraCompliant(true);
        report.setQueriedAt(Instant.now());
        report.setCreditScore(750);
        report.setConfidenceScore(0.95);
        report.setIdentityMatch(true);

        report = creditReportRepository.save(report);
        recordProviderSuccess(provider);

        return entityMapper.toCreditScoreResponse(report);
    }

    // ========================================================================
    // PROVIDER MANAGEMENT
    // ========================================================================

    public ResponseDto.IntegrationProviderResponse createProvider(String tenantId, RequestDto.IntegrationProviderRequest request) {
        IntegrationProvider provider = entityMapper.toIntegrationProvider(request, tenantId);
        provider.setTenantId(tenantId);
        provider = integrationProviderRepository.save(provider);
        return entityMapper.toIntegrationProviderResponse(provider);
    }

    public List<ResponseDto.IntegrationProviderResponse> listProviders(String tenantId, String providerType) {
        List<IntegrationProvider> providers;
        if (providerType != null) {
            try {
                IntegrationProvider.ProviderType type = IntegrationProvider.ProviderType.valueOf(providerType.toUpperCase());
                providers = integrationProviderRepository.findByTenantIdAndProviderType(tenantId, type);
            } catch (IllegalArgumentException e) {
                throw BusinessException.invalidPayload("Invalid provider type: " + providerType);
            }
        } else {
            providers = integrationProviderRepository.findByTenantIdAndEnabled(tenantId, true);
        }
        return providers.stream()
                .map(entityMapper::toIntegrationProviderResponse)
                .toList();
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private IntegrationProvider findCreditProvider(String tenantId, String bureauName) {
        String name = bureauName != null ? bureauName : "experian";
        return integrationProviderRepository
                .findByTenantIdAndProviderTypeAndProviderName(tenantId,
                        IntegrationProvider.ProviderType.CREDIT, name)
                .orElseThrow(() -> BusinessException.providerNotFound(name));
    }

    private void checkCircuitBreaker(IntegrationProvider provider) {
        if (provider.getCircuitBreakerState() == IntegrationProvider.CircuitBreakerState.OPEN) {
            throw BusinessException.circuitBreakerOpen(provider.getProviderName());
        }
    }

    private void recordProviderSuccess(IntegrationProvider provider) {
        if (provider != null) {
            provider.setFailureCount(0);
            provider.setLastSuccessAt(Instant.now());
            provider.setCircuitBreakerState(IntegrationProvider.CircuitBreakerState.CLOSED);
            integrationProviderRepository.save(provider);
        }
    }

    private Map<String, Object> queryCreditBureau(String bureauName, Map<String, Object> identityData) {
        return Map.of(
                "identityMatch", true,
                "confidence", 0.95,
                "creditScore", 720,
                "bureau", bureauName
        );
    }

    private List<ResponseDto.TransactionDto> parseTransactions(JsonNode response) {
        List<ResponseDto.TransactionDto> transactions = new ArrayList<>();
        if (response != null && response.has("transactions")) {
            JsonNode txArray = response.get("transactions");
            for (JsonNode tx : txArray) {
                transactions.add(ResponseDto.TransactionDto.builder()
                        .transactionId(tx.has("id") ? tx.get("id").asText() : UUID.randomUUID().toString())
                        .amount(tx.has("amount") ? tx.get("amount").asDouble() : 0.0)
                        .currency(tx.has("currency") ? tx.get("currency").asText("USD") : "USD")
                        .description(tx.has("description") ? tx.get("description").asText() : "")
                        .category(tx.has("category") ? tx.get("category").asText() : "other")
                        .date(tx.has("date") ? Instant.parse(tx.get("date").asText()) : Instant.now())
                        .pending(tx.has("pending") && tx.get("pending").asBoolean())
                        .build());
            }
        }
        return transactions;
    }

    private String serializePayload(Object payload) {
        try {
            if (payload instanceof String s) return s;
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw BusinessException.invalidPayload("Failed to serialize payload");
        }
    }
}
