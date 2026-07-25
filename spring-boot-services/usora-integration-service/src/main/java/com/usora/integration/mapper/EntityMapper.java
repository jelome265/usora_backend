package com.usora.integration.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.usora.integration.dto.RequestDto;
import com.usora.integration.dto.ResponseDto;
import com.usora.integration.entity.*;
import com.usora.integration.util.EncryptionUtil;
import com.usora.integration.util.IdGenerator;
import org.mapstruct.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT,
        uses = {EncryptionUtil.class, ObjectMapper.class}
)
public abstract class EntityMapper {

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected EncryptionUtil encryptionUtil;

    @Autowired
    protected IdGenerator idGenerator;

    @Named("toJsonString")
    public String toJsonString(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }

    @Named("fromJsonString")
    public <T> T fromJsonString(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize from JSON", e);
        }
    }

    @Named("toSetFromCollection")
    public Set<String> toSetFromCollection(Collection<String> collection) {
        return collection != null ? new HashSet<>(collection) : new HashSet<>();
    }

    @Named("toListFromSet")
    public List<String> toListFromSet(Set<String> set) {
        return set != null ? new ArrayList<>(set) : new ArrayList<>();
    }

    // WebhookConfig mapping
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "tenantId", source = "tenantId")
    @Mapping(target = "tenantOrgId", ignore = true)
    @Mapping(target = "endpointId", source = "request.endpointId")
    @Mapping(target = "url", source = "request.url")
    @Mapping(target = "description", source = "request.description")
    @Mapping(target = "events", source = "request.events", qualifiedByName = "toSetFromCollection")
    @Mapping(target = "secret", source = "request.secret")
    @Mapping(target = "hmacSecret", ignore = true)
    @Mapping(target = "publicKey", ignore = true)
    @Mapping(target = "authType", source = "request.authType")
    @Mapping(target = "status", constant = "ACTIVE")
    @Mapping(target = "retryCount", source = "request.retryCount", defaultValue = "5")
    @Mapping(target = "retryIntervalMs", source = "request.retryIntervalMs", defaultValue = "1000L")
    @Mapping(target = "rateLimitPerSecond", source = "request.rateLimitPerSecond", defaultValue = "100")
    @Mapping(target = "maxPayloadSizeBytes", constant = "10485760L")
    @Mapping(target = "filterExpression", source = "request.filterExpression")
    @Mapping(target = "headers", ignore = true)
    @Mapping(target = "enabled", constant = "true")
    @Mapping(target = "webhookUrl", source = "request.webhookUrl")
    @Mapping(target = "cloudEventSource", source = "request.cloudEventSource")
    @Mapping(target = "cloudEventTypePrefix", source = "request.cloudEventTypePrefix")
    public abstract WebhookConfig toWebhookConfig(RequestDto.WebhookConfigRequest request, @Context String tenantId);

    @Mapping(target = "id", source = "entity.id")
    @Mapping(target = "endpointId", source = "entity.endpointId")
    @Mapping(target = "url", source = "entity.url")
    @Mapping(target = "description", source = "entity.description")
    @Mapping(target = "events", source = "entity.events", qualifiedByName = "toListFromSet")
    @Mapping(target = "status", source = "entity.status")
    @Mapping(target = "authType", source = "entity.authType")
    @Mapping(target = "retryCount", source = "entity.retryCount")
    @Mapping(target = "retryIntervalMs", source = "entity.retryIntervalMs")
    @Mapping(target = "rateLimitPerSecond", source = "entity.rateLimitPerSecond")
    @Mapping(target = "webhookUrl", source = "entity.webhookUrl")
    @Mapping(target = "cloudEventSource", source = "entity.cloudEventSource")
    @Mapping(target = "cloudEventTypePrefix", source = "entity.cloudEventTypePrefix")
    @Mapping(target = "createdAt", source = "entity.createdAt")
    @Mapping(target = "updatedAt", source = "entity.updatedAt")
    public abstract ResponseDto.WebhookConfigResponse toWebhookConfigResponse(WebhookConfig entity);

    // IntegrationProvider mapping
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "tenantId", source = "tenantId")
    @Mapping(target = "tenantOrgId", ignore = true)
    @Mapping(target = "providerType", expression = "java(com.usora.integration.entity.IntegrationProvider.ProviderType.valueOf(request.getProviderType().toUpperCase()))")
    @Mapping(target = "providerName", source = "request.providerName")
    @Mapping(target = "configEncrypted", expression = "java(encryptConfig(request.getConfig()))")
    @Mapping(target = "enabled", source = "request.enabled", defaultValue = "true")
    @Mapping(target = "priority", source = "request.priority", defaultValue = "0")
    @Mapping(target = "circuitBreakerState", constant = "CLOSED")
    @Mapping(target = "failureCount", constant = "0")
    @Mapping(target = "lastFailureAt", ignore = true)
    @Mapping(target = "lastSuccessAt", ignore = true)
    @Mapping(target = "rateLimitRpm", constant = "100")
    @Mapping(target = "metadata", ignore = true)
    public abstract IntegrationProvider toIntegrationProvider(RequestDto.IntegrationProviderRequest request, @Context String tenantId);

    public ResponseDto.IntegrationProviderResponse toIntegrationProviderResponse(IntegrationProvider entity) {
        if (entity == null) return null;
        return ResponseDto.IntegrationProviderResponse.builder()
                .id(entity.getId())
                .providerType(entity.getProviderType() != null ? entity.getProviderType().name() : null)
                .providerName(entity.getProviderName())
                .enabled(Boolean.TRUE.equals(entity.getEnabled()))
                .priority(entity.getPriority() != null ? entity.getPriority() : 0)
                .circuitBreakerState(entity.getCircuitBreakerState() != null ? entity.getCircuitBreakerState().name() : "CLOSED")
                .failureCount(entity.getFailureCount() != null ? entity.getFailureCount() : 0)
                .lastSuccessAt(entity.getLastSuccessAt())
                .rateLimitRpm(entity.getRateLimitRpm())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    protected String encryptConfig(Map<String, Object> config) {
        try {
            String json = objectMapper.writeValueAsString(config);
            return encryptionUtil.encrypt(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt provider config", e);
        }
    }

    public Map<String, Object> decryptConfig(IntegrationProvider provider) {
        try {
            String json = encryptionUtil.decrypt(provider.getConfigEncrypted());
            return objectMapper.readValue(json, HashMap.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt provider config", e);
        }
    }

    // BankingLink mapping
    public ResponseDto.BankingLinkResponse toBankingLinkResponse(BankingLink entity) {
        if (entity == null) return null;
        return ResponseDto.BankingLinkResponse.builder()
                .linkId(entity.getId().toString())
                .providerName(entity.getProviderName())
                .status(entity.getStatus() != null ? entity.getStatus().name() : null)
                .accountId(entity.getAccountId())
                .institutionName(entity.getInstitutionName())
                .linkedAt(entity.getLinkedAt())
                .kycCompleted(Boolean.TRUE.equals(entity.getKycCompleted()))
                .build();
    }

    public ResponseDto.BankingVerifyResponse toBankingVerifyResponse(BankingLink entity) {
        if (entity == null) return null;
        return ResponseDto.BankingVerifyResponse.builder()
                .verified(entity.getStatus() == BankingLink.LinkStatus.VERIFIED)
                .accountId(entity.getAccountId())
                .accountType(entity.getAccountType())
                .accountNumberMasked(entity.getAccountNumberMasked())
                .routingNumber(entity.getRoutingNumber())
                .institutionName(entity.getInstitutionName())
                .status(entity.getStatus() != null ? entity.getStatus().name() : null)
                .verifiedAt(entity.getVerifiedAt())
                .build();
    }

    // GovernmentVerification mapping
    public ResponseDto.GovernmentVerificationResponse toGovernmentVerificationResponse(GovernmentVerification entity) {
        if (entity == null) return null;
        return ResponseDto.GovernmentVerificationResponse.builder()
                .verificationId(entity.getVerificationId())
                .verificationType(entity.getVerificationType() != null ? entity.getVerificationType().name() : null)
                .status(entity.getStatus() != null ? entity.getStatus().name() : null)
                .verified(entity.getStatus() == GovernmentVerification.VerificationStatus.VERIFIED)
                .confidenceScore(entity.getConfidenceScore() != null ? entity.getConfidenceScore() : 0.0)
                .countryCode(entity.getCountryCode())
                .providerName(entity.getProviderName())
                .verifiedAt(entity.getVerifiedAt())
                .expiresAt(entity.getExpiresAt())
                .errorCode(entity.getErrorCode())
                .errorMessage(entity.getErrorMessage())
                .build();
    }

    public ResponseDto.VerificationStatusResponse toVerificationStatusResponse(GovernmentVerification entity) {
        if (entity == null) return null;
        return ResponseDto.VerificationStatusResponse.builder()
                .verificationId(entity.getVerificationId())
                .status(entity.getStatus() != null ? entity.getStatus().name() : null)
                .verificationType(entity.getVerificationType() != null ? entity.getVerificationType().name() : null)
                .createdAt(entity.getCreatedAt())
                .verifiedAt(entity.getVerifiedAt())
                .expiresAt(entity.getExpiresAt())
                .errorCode(entity.getErrorCode())
                .build();
    }

    // CreditReport mapping
    public ResponseDto.CreditVerificationResponse toCreditVerificationResponse(CreditReport entity) {
        if (entity == null) return null;
        return ResponseDto.CreditVerificationResponse.builder()
                .reportId(entity.getId())
                .bureauName(entity.getBureauName())
                .identityMatch(Boolean.TRUE.equals(entity.getIdentityMatch()))
                .confidenceScore(entity.getConfidenceScore() != null ? entity.getConfidenceScore() : 0.0)
                .status(entity.getStatus() != null ? entity.getStatus().name() : null)
                .build();
    }

    public ResponseDto.CreditReportResponse toCreditReportResponse(CreditReport entity) {
        if (entity == null) return null;
        return ResponseDto.CreditReportResponse.builder()
                .reportId(entity.getId())
                .bureauName(entity.getBureauName())
                .creditScore(entity.getCreditScore())
                .status(entity.getStatus() != null ? entity.getStatus().name() : null)
                .identityMatch(Boolean.TRUE.equals(entity.getIdentityMatch()))
                .confidenceScore(entity.getConfidenceScore() != null ? entity.getConfidenceScore() : 0.0)
                .queriedAt(entity.getQueriedAt())
                .build();
    }

    public ResponseDto.CreditFraudCheckResponse toCreditFraudCheckResponse(CreditReport entity) {
        if (entity == null) return null;
        List<String> indicators = entity.getFraudIndicators() != null
                ? fromJsonString(entity.getFraudIndicators(), List.class)
                : List.of();
        return ResponseDto.CreditFraudCheckResponse.builder()
                .reportId(entity.getId())
                .bureauName(entity.getBureauName())
                .fraudDetected(!indicators.isEmpty())
                .fraudScore(entity.getConfidenceScore() != null ? entity.getConfidenceScore() : 0.0)
                .fraudIndicators(indicators)
                .status(entity.getStatus() != null ? entity.getStatus().name() : null)
                .build();
    }

    public ResponseDto.CreditScoreResponse toCreditScoreResponse(CreditReport entity) {
        if (entity == null) return null;
        return ResponseDto.CreditScoreResponse.builder()
                .reportId(entity.getId())
                .bureauName(entity.getBureauName())
                .creditScore(entity.getCreditScore())
                .scoreRange(entity.getCreditScore() != null ? getScoreRange(entity.getCreditScore()) : null)
                .rating(entity.getCreditScore() != null ? getCreditRating(entity.getCreditScore()) : null)
                .build();
    }

    private String getScoreRange(int score) {
        if (score < 580) return "300-579";
        if (score < 670) return "580-669";
        if (score < 740) return "670-739";
        if (score < 800) return "740-799";
        return "800-850";
    }

    private String getCreditRating(int score) {
        if (score < 580) return "Poor";
        if (score < 670) return "Fair";
        if (score < 740) return "Good";
        if (score < 800) return "Very Good";
        return "Excellent";
    }

    // Webhook ingest response
    public ResponseDto.WebhookIngestResponse toWebhookIngestResponse(String id, String status, String correlationId,
                                                                      String idempotencyKey, boolean duplicate) {
        return ResponseDto.WebhookIngestResponse.builder()
                .id(id)
                .status(status)
                .correlationId(correlationId)
                .idempotencyKey(idempotencyKey)
                .duplicate(duplicate)
                .timestamp(Instant.now())
                .build();
    }

    public ResponseDto.ReplayResponse toReplayResponse(String integrationId, int count, String status) {
        return ResponseDto.ReplayResponse.builder()
                .integrationId(integrationId)
                .eventsReplayed(count)
                .status(status)
                .startedAt(Instant.now())
                .build();
    }
}
