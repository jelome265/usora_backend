package com.usora.tenant.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final String errorCode;
    private final String details;

    public BusinessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.details = null;
    }

    public BusinessException(String errorCode, String message, String details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public BusinessException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = cause.getMessage();
    }

    public static BusinessException tenantNotFound(String tenantId) {
        return new BusinessException("TENANT_NOT_FOUND", "Tenant not found: " + tenantId);
    }

    public static BusinessException tenantAlreadyExists(String domain) {
        return new BusinessException("TENANT_ALREADY_EXISTS", "Tenant with domain already exists: " + domain);
    }

    public static BusinessException tenantNotActive(String tenantId) {
        return new BusinessException("TENANT_NOT_ACTIVE", "Tenant is not in active state: " + tenantId);
    }

    public static BusinessException tenantSuspended(String tenantId) {
        return new BusinessException("TENANT_SUSPENDED", "Tenant is suspended: " + tenantId);
    }

    public static BusinessException provisioningFailed(String tenantId, String reason) {
        return new BusinessException("PROVISIONING_FAILED", "Tenant provisioning failed: " + tenantId, reason);
    }

    public static BusinessException offboardingFailed(String tenantId, String reason) {
        return new BusinessException("OFFBOARDING_FAILED", "Tenant offboarding failed: " + tenantId, reason);
    }

    public static BusinessException invalidOperation(String message) {
        return new BusinessException("INVALID_OPERATION", message);
    }
}
