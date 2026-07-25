package com.usora.integration.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class BusinessException extends RuntimeException {

    private final String code;
    private final HttpStatus httpStatus;
    private final String detail;

    public BusinessException(String code, String message) {
        this(code, message, HttpStatus.BAD_REQUEST, null);
    }

    public BusinessException(String code, String message, HttpStatus httpStatus) {
        this(code, message, httpStatus, null);
    }

    public BusinessException(String code, String message, HttpStatus httpStatus, String detail) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.detail = detail;
    }

    // Standard error codes from webhook agent spec
    public static final String INVALID_PAYLOAD = "INVALID_PAYLOAD";
    public static final String AUTHENTICATION_FAILED = "AUTHENTICATION_FAILED";
    public static final String SIGNATURE_VERIFICATION_FAILED = "SIGNATURE_VERIFICATION_FAILED";
    public static final String TENANT_ISOLATION_VIOLATION = "TENANT_ISOLATION_VIOLATION";
    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
    public static final String RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED";
    public static final String EVENT_BUS_UNAVAILABLE = "EVENT_BUS_UNAVAILABLE";
    public static final String PROVIDER_NOT_FOUND = "PROVIDER_NOT_FOUND";
    public static final String PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE";
    public static final String CONSENT_NOT_GRANTED = "CONSENT_NOT_GRANTED";
    public static final String ACCOUNT_NOT_LINKED = "ACCOUNT_NOT_LINKED";
    public static final String TOKEN_EXPIRED = "TOKEN_EXPIRED";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String CONFLICT = "CONFLICT";
    public static final String CIRCUIT_BREAKER_OPEN = "CIRCUIT_BREAKER_OPEN";
    public static final String FCRA_COMPLIANCE_ERROR = "FCRA_COMPLIANCE_ERROR";
    public static final String DATA_RETENTION_ERROR = "DATA_RETENTION_ERROR";

    public static BusinessException invalidPayload(String message) {
        return new BusinessException(INVALID_PAYLOAD, message, HttpStatus.BAD_REQUEST);
    }

    public static BusinessException authenticationFailed(String message) {
        return new BusinessException(AUTHENTICATION_FAILED, message, HttpStatus.UNAUTHORIZED);
    }

    public static BusinessException signatureVerificationFailed(String message) {
        return new BusinessException(SIGNATURE_VERIFICATION_FAILED, message, HttpStatus.UNAUTHORIZED);
    }

    public static BusinessException idempotencyKeyReused(String key) {
        return new BusinessException(IDEMPOTENCY_KEY_REUSED, "Idempotency key already processed: " + key,
                HttpStatus.CONFLICT);
    }

    public static BusinessException rateLimitExceeded() {
        return new BusinessException(RATE_LIMIT_EXCEEDED, "Rate limit exceeded. Please retry later.",
                HttpStatus.TOO_MANY_REQUESTS);
    }

    public static BusinessException eventBusUnavailable(String message) {
        return new BusinessException(EVENT_BUS_UNAVAILABLE, message, HttpStatus.SERVICE_UNAVAILABLE);
    }

    public static BusinessException providerNotFound(String provider) {
        return new BusinessException(PROVIDER_NOT_FOUND, "Provider not found: " + provider, HttpStatus.NOT_FOUND);
    }

    public static BusinessException consentNotGranted() {
        return new BusinessException(CONSENT_NOT_GRANTED, "Consumer consent is required for this operation",
                HttpStatus.FORBIDDEN);
    }

    public static BusinessException circuitBreakerOpen(String provider) {
        return new BusinessException(CIRCUIT_BREAKER_OPEN,
                "Provider circuit breaker is open: " + provider, HttpStatus.SERVICE_UNAVAILABLE);
    }

    public static BusinessException notFound(String entity, String id) {
        return new BusinessException(NOT_FOUND, entity + " not found: " + id, HttpStatus.NOT_FOUND);
    }
}
