package com.usora.integration.util;

import com.usora.integration.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class ValidationUtil {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern TENANT_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "^\\+?[1-9]\\d{1,14}$");
    private static final Pattern URL_PATTERN = Pattern.compile(
            "^(https?)://[^\\s/$.?#].[^\\s]*$");
    private static final Pattern BASE64_PATTERN = Pattern.compile(
            "^[A-Za-z0-9+/]*={0,2}$");

    public void validateTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw BusinessException.invalidPayload("Tenant ID is required");
        }
        if (!TENANT_ID_PATTERN.matcher(tenantId).matches()) {
            throw BusinessException.invalidPayload("Invalid tenant ID format");
        }
    }

    public void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw BusinessException.invalidPayload("Idempotency key is required");
        }
        if (key.length() > 256) {
            throw BusinessException.invalidPayload("Idempotency key exceeds maximum length");
        }
    }

    public void validateEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw BusinessException.invalidPayload("Event type is required");
        }
        if (eventType.length() > 256) {
            throw BusinessException.invalidPayload("Event type too long");
        }
    }

    public void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw BusinessException.invalidPayload("URL is required");
        }
        if (!URL_PATTERN.matcher(url).matches()) {
            throw BusinessException.invalidPayload("Invalid URL format");
        }
    }

    public void validatePayloadSize(byte[] payload, long maxBytes) {
        if (payload != null && payload.length > maxBytes) {
            throw BusinessException.invalidPayload(
                    "Payload exceeds maximum size of " + maxBytes + " bytes");
        }
    }

    public void validateEmail(String email) {
        if (email != null && !email.isBlank() && !EMAIL_PATTERN.matcher(email).matches()) {
            throw BusinessException.invalidPayload("Invalid email format");
        }
    }

    public void validatePhone(String phone) {
        if (phone != null && !phone.isBlank() && !PHONE_PATTERN.matcher(phone).matches()) {
            throw BusinessException.invalidPayload("Invalid phone number format");
        }
    }

    public void validateNotEmpty(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw BusinessException.invalidPayload(fieldName + " is required");
        }
    }

    public void validateNotEmpty(Collection<?> collection, String fieldName) {
        if (collection == null || collection.isEmpty()) {
            throw BusinessException.invalidPayload(fieldName + " must not be empty");
        }
    }

    public void validateNotNull(Object value, String fieldName) {
        if (value == null) {
            throw BusinessException.invalidPayload(fieldName + " is required");
        }
    }

    public void validateCountryCode(String code) {
        if (code != null && (code.length() != 2 && code.length() != 3)) {
            throw BusinessException.invalidPayload("Invalid country code: " + code);
        }
    }

    public void validateIntegrity(String payload, String expectedHash, String actualHash) {
        if (!expectedHash.equals(actualHash)) {
            throw BusinessException.signatureVerificationFailed("Payload integrity check failed");
        }
    }
}
