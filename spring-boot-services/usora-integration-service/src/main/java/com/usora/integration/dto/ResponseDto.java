package com.usora.integration.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ResponseDto {

    private ResponseDto() {}

    @Data
    @Builder
    public static class WebhookIngestResponse {
        private String id;
        private String status;
        private String correlationId;
        private String idempotencyKey;
        private boolean duplicate;
        private Instant timestamp;
    }

    @Data
    @Builder
    public static class WebhookConfigResponse {
        private UUID id;
        private String endpointId;
        private String url;
        private String description;
        private List<String> events;
        private String status;
        private String authType;
        private Integer retryCount;
        private Long retryIntervalMs;
        private Integer rateLimitPerSecond;
        private String webhookUrl;
        private String cloudEventSource;
        private String cloudEventTypePrefix;
        private Instant createdAt;
        private Instant updatedAt;
    }

    @Data
    @Builder
    public static class BankingLinkResponse {
        private String linkId;
        private String providerName;
        private String linkUrl;
        private String status;
        private String accountId;
        private String institutionName;
        private Instant linkedAt;
        private boolean kycCompleted;
    }

    @Data
    @Builder
    public static class BankingVerifyResponse {
        private boolean verified;
        private String accountId;
        private String accountType;
        private String accountNumberMasked;
        private String routingNumber;
        private String institutionName;
        private String status;
        private Instant verifiedAt;
    }

    @Data
    @Builder
    public static class BankingTransactionResponse {
        private List<TransactionDto> transactions;
        private String cursor;
        private boolean hasMore;
        private int totalCount;
    }

    @Data
    @Builder
    public static class TransactionDto {
        private String transactionId;
        private String accountId;
        private double amount;
        private String currency;
        private String description;
        private String category;
        private Instant date;
        private boolean pending;
    }

    @Data
    @Builder
    public static class BankingIncomeResponse {
        private double estimatedAnnualIncome;
        private double monthlyIncome;
        private String currency;
        private String incomeSource;
        private double confidence;
        private int monthsAnalyzed;
    }

    @Data
    @Builder
    public static class BankingBalanceResponse {
        private String accountId;
        private double currentBalance;
        private double availableBalance;
        private String currency;
        private double limit;
        private Instant asOfDate;
    }

    @Data
    @Builder
    public static class BankingDisconnectResponse {
        private String accountId;
        private boolean disconnected;
        private Instant disconnectedAt;
    }

    @Data
    @Builder
    public static class GovernmentVerificationResponse {
        private UUID verificationId;
        private String verificationType;
        private String status;
        private boolean verified;
        private double confidenceScore;
        private String countryCode;
        private String providerName;
        private Instant verifiedAt;
        private Instant expiresAt;
        private String errorCode;
        private String errorMessage;
    }

    @Data
    @Builder
    public static class VerificationStatusResponse {
        private UUID verificationId;
        private String status;
        private String verificationType;
        private Instant createdAt;
        private Instant verifiedAt;
        private Instant expiresAt;
        private String errorCode;
    }

    @Data
    @Builder
    public static class CreditVerificationResponse {
        private UUID reportId;
        private String bureauName;
        private boolean identityMatch;
        private double confidenceScore;
        private String status;
    }

    @Data
    @Builder
    public static class CreditReportResponse {
        private UUID reportId;
        private String bureauName;
        private Integer creditScore;
        private String status;
        private boolean identityMatch;
        private double confidenceScore;
        private Instant queriedAt;
        private String summary;
    }

    @Data
    @Builder
    public static class CreditFraudCheckResponse {
        private UUID reportId;
        private String bureauName;
        private boolean fraudDetected;
        private double fraudScore;
        private List<String> fraudIndicators;
        private String riskLevel;
        private String status;
    }

    @Data
    @Builder
    public static class CreditAlternativeDataResponse {
        private UUID reportId;
        private String providerName;
        private Map<String, Object> alternativeData;
        private double confidenceScore;
    }

    @Data
    @Builder
    public static class CreditScoreResponse {
        private UUID reportId;
        private String bureauName;
        private Integer creditScore;
        private String scoreRange;
        private String rating;
        private Map<String, Integer> scoreFactors;
    }

    @Data
    @Builder
    public static class IntegrationProviderResponse {
        private UUID id;
        private String providerType;
        private String providerName;
        private boolean enabled;
        private int priority;
        private String circuitBreakerState;
        private int failureCount;
        private Instant lastSuccessAt;
        private Integer rateLimitRpm;
        private Instant createdAt;
    }

    @Data
    @Builder
    public static class ReplayResponse {
        private String integrationId;
        private int eventsReplayed;
        private String status;
        private Instant startedAt;
    }

    @Data
    @Builder
    public static class ErrorResponse {
        private String code;
        private String message;
        private String detail;
        private String requestId;
        private String tenantId;
        private Instant timestamp;
        private List<FieldError> fieldErrors;
    }

    @Data
    @Builder
    public static class FieldError {
        private String field;
        private String message;
        private String rejectedValue;
    }

    @Data
    @Builder
    public static class PagedResponse<T> {
        private List<T> content;
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
        private boolean last;
    }
}
