package com.usora.integration.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.Set;

public final class RequestDto {

    private RequestDto() {}

    @Data
    @Builder
    public static class WebhookIngestRequest {
        @NotNull
        private String eventType;

        private String eventVersion;

        @NotNull
        private String idempotencyKey;

        private String correlationId;

        @NotNull
        private Object payload;

        private Map<String, String> headers;

        private Long timestamp;
    }

    @Data
    @Builder
    public static class WebhookConfigRequest {
        @NotBlank
        @Size(max = 128)
        private String endpointId;

        @NotBlank
        @Size(max = 1024)
        private String url;

        @NotBlank
        @Size(max = 256)
        private String secret;

        @Size(max = 512)
        private String description;

        @NotEmpty
        private Set<String> events;

        @NotNull
        private WebhookConfigRequest.AuthType authType;

        @Size(max = 1024)
        private String filterExpression;

        private Integer retryCount;
        private Long retryIntervalMs;
        private Integer rateLimitPerSecond;

        @Size(max = 1024)
        private String webhookUrl;

        @Size(max = 512)
        private String cloudEventSource;

        @Size(max = 256)
        private String cloudEventTypePrefix;

        public enum AuthType {
            NONE, API_KEY, HMAC, RSA, ECDSA, OAUTH2, CUSTOM
        }
    }

    @Data
    @Builder
    public static class BankingLinkRequest {
        @NotBlank
        @Size(max = 64)
        private String providerName;

        @NotBlank
        @Size(max = 128)
        private String redirectUri;

        @Size(max = 256)
        private String countryCodes;

        private String publicToken;

        @Size(max = 1024)
        private Map<String, String> metadata;
    }

    @Data
    @Builder
    public static class BankingVerifyRequest {
        @NotBlank
        @Size(max = 128)
        private String accountId;

        @Size(max = 64)
        private String providerName;

        private Map<String, Object> verificationData;
    }

    @Data
    @Builder
    public static class BankingTransactionRequest {
        @NotBlank
        @Size(max = 128)
        private String accountId;

        private String startDate;
        private String endDate;
        private Integer limit;
        private String cursor;
    }

    @Data
    @Builder
    public static class BankingIncomeRequest {
        @NotBlank
        @Size(max = 128)
        private String accountId;

        @Min(1)
        @Max(24)
        private Integer months;
    }

    @Data
    @Builder
    public static class BankingBalanceRequest {
        @NotBlank
        @Size(max = 128)
        private String accountId;
    }

    @Data
    @Builder
    public static class BankingDisconnectRequest {
        @NotBlank
        @Size(max = 128)
        private String accountId;
    }

    @Data
    @Builder
    public static class GovernmentVerificationRequest {
        @NotNull
        private GovernmentVerificationType verificationType;

        @NotBlank
        @Size(max = 128)
        private String userId;

        @Size(max = 4)
        private String countryCode;

        @Valid
        @NotNull
        private Map<String, Object> identityData;

        private Boolean consentGranted;
    }

    public enum GovernmentVerificationType {
        EIDAS, AADHAAR, DMV, PASSPORT
    }

    @Data
    @Builder
    public static class CreditVerificationRequest {
        @NotBlank
        @Size(max = 128)
        private String userId;

        @Size(max = 64)
        private String bureauName;

        @Valid
        @NotNull
        private Map<String, Object> identityData;

        private Boolean consumerConsentGranted;
        private String consentId;
    }

    @Data
    @Builder
    public static class CreditReportRequest {
        @NotBlank
        @Size(max = 128)
        private String userId;

        @Size(max = 64)
        private String bureauName;

        @Valid
        @NotNull
        private Map<String, Object> identityData;

        private Boolean consumerConsentGranted;
        private String consentId;

        @Size(max = 64)
        private String reportType;
    }

    @Data
    @Builder
    public static class CreditFraudCheckRequest {
        @NotBlank
        @Size(max = 128)
        private String userId;

        @Size(max = 64)
        private String bureauName;

        @Valid
        @NotNull
        private Map<String, Object> identityData;

        private Boolean consumerConsentGranted;
    }

    @Data
    @Builder
    public static class CreditAlternativeDataRequest {
        @NotBlank
        @Size(max = 128)
        private String userId;

        @Size(max = 64)
        private String providerName;

        @Valid
        @NotNull
        private Map<String, Object> identityData;
    }

    @Data
    @Builder
    public static class CreditScoreRequest {
        @NotBlank
        @Size(max = 128)
        private String userId;

        @Size(max = 64)
        private String bureauName;

        @Valid
        @NotNull
        private Map<String, Object> identityData;

        private Boolean consumerConsentGranted;
    }

    @Data
    @Builder
    public static class IntegrationProviderRequest {
        @NotNull
        private String providerType;

        @NotBlank
        @Size(max = 128)
        private String providerName;

        @NotNull
        private Map<String, Object> config;

        private Boolean enabled;
        private Integer priority;
    }
}
