package com.usora.compliance.util;

import com.usora.compliance.exception.BusinessException;

import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

public final class ValidationUtil {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@(.+)$");
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private ValidationUtil() {}

    public static void requireNonNull(Object obj, String fieldName) {
        if (obj == null) {
            throw BusinessException.validationFailed(fieldName + " must not be null");
        }
    }

    public static void requireNonBlank(String str, String fieldName) {
        if (str == null || str.isBlank()) {
            throw BusinessException.validationFailed(fieldName + " must not be blank");
        }
    }

    public static void requireNonEmpty(Collection<?> collection, String fieldName) {
        if (collection == null || collection.isEmpty()) {
            throw BusinessException.validationFailed(fieldName + " must not be empty");
        }
    }

    public static void requireNonEmpty(Map<?, ?> map, String fieldName) {
        if (map == null || map.isEmpty()) {
            throw BusinessException.validationFailed(fieldName + " must not be empty");
        }
    }

    public static void validateJurisdiction(String jurisdiction) {
        var supported = java.util.Set.of("eu_gdpr", "us_aml", "uk_aml", "singapore_mas", "uae_central_bank");
        if (!supported.contains(jurisdiction)) {
            throw BusinessException.validationFailed("Unsupported jurisdiction: " + jurisdiction
                    + ". Supported: " + String.join(", ", supported));
        }
    }

    public static void validateReportFormat(String format) {
        var supported = java.util.Set.of("pdf", "xlsx", "csv");
        if (!supported.contains(format.toLowerCase())) {
            throw BusinessException.validationFailed("Unsupported report format: " + format
                    + ". Supported: " + String.join(", ", supported));
        }
    }

    public static boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }

    public static boolean isValidUUID(String uuid) {
        return uuid != null && UUID_PATTERN.matcher(uuid).matches();
    }

    public static void validateSeverity(String severity) {
        var valid = java.util.Set.of("critical", "high", "medium", "low", "info");
        if (!valid.contains(severity.toLowerCase())) {
            throw BusinessException.validationFailed("Invalid severity: " + severity);
        }
    }
}
