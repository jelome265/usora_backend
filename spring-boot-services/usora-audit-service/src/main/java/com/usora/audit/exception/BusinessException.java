package com.usora.audit.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public BusinessException(String message, String code, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public BusinessException(String message, String code) {
        super(message);
        this.code = code;
        this.httpStatus = 400;
    }

    public BusinessException(String message) {
        super(message);
        this.code = "AUDIT_ERROR";
        this.httpStatus = 400;
    }

    public static BusinessException notFound(String resource, String id) {
        return new BusinessException(resource + " not found: " + id, "NOT_FOUND", 404);
    }

    public static BusinessException validationError(String message) {
        return new BusinessException(message, "VALIDATION_ERROR", 422);
    }

    public static BusinessException integrityViolation(String message) {
        return new BusinessException(message, "INTEGRITY_VIOLATION", 409);
    }

    public static BusinessException forbidden(String message) {
        return new BusinessException(message, "FORBIDDEN", 403);
    }

    public static BusinessException tenantMismatch(String expected, String actual) {
        return new BusinessException(
                "Tenant mismatch: expected " + expected + " but was " + actual,
                "TENANT_MISMATCH", 403);
    }
}
