package com.usora.compliance.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final String code;
    private final int httpStatus;
    private final String detail;

    public BusinessException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.detail = null;
    }

    public BusinessException(String code, String message, int httpStatus, String detail) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.detail = detail;
    }

    public BusinessException(String code, String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
        this.detail = cause.getMessage();
    }

    public static BusinessException notFound(String entity, String id) {
        return new BusinessException("NOT_FOUND", entity + " not found: " + id, 404);
    }

    public static BusinessException validationFailed(String message) {
        return new BusinessException("VALIDATION_FAILED", message, 400);
    }

    public static BusinessException authorizationFailed(String message) {
        return new BusinessException("AUTHORIZATION_FAILED", message, 403);
    }

    public static BusinessException ruleCompilationFailed(String message) {
        return new BusinessException("RULE_COMPILATION_FAILED", message, 500);
    }

    public static BusinessException dualAuthorizationRequired() {
        return new BusinessException("DUAL_AUTHORIZATION_REQUIRED",
                "Rule update requires approval from both compliance officer and legal review", 403);
    }

    public static BusinessException jurisdictionConflict(String jurisdiction, String detail) {
        return new BusinessException("JURISDICTION_CONFLICT",
                "Jurisdiction configuration conflict for " + jurisdiction, 409, detail);
    }

    public static BusinessException evidenceCorrupted(String evidenceId) {
        return new BusinessException("EVIDENCE_CORRUPTED",
                "Evidence integrity check failed for " + evidenceId, 400);
    }

    public static BusinessException auditTrailCorrupted() {
        return new BusinessException("AUDIT_TRAIL_CORRUPTED",
                "Audit trail hash chain verification failed - possible tampering detected", 500);
    }
}
