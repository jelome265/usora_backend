package com.usora.audit.util;

import com.usora.audit.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Component
public class ValidationUtil {

    private static final Set<String> VALID_CATEGORIES = Set.of(
            "authentication", "authorization", "data_access",
            "data_modification", "configuration_change", "security_event", "compliance_check"
    );

    private static final Set<String> VALID_OUTCOMES = Set.of("SUCCESS", "FAILURE", "DENIED", "ERROR");

    private static final Set<String> VALID_SEVERITIES = Set.of("INFO", "WARNING", "HIGH", "CRITICAL", "LOW");

    public void validateAuditEvent(String tenantId, String action, String resourceType, String resourceId,
                                    String actorId, String outcome) {
        if (isBlank(tenantId)) throw BusinessException.validationError("tenantId is required");
        if (isBlank(action)) throw BusinessException.validationError("action is required");
        if (isBlank(resourceType)) throw BusinessException.validationError("resourceType is required");
        if (isBlank(resourceId)) throw BusinessException.validationError("resourceId is required");
        if (isBlank(actorId)) throw BusinessException.validationError("actorId is required");
        if (isBlank(outcome)) throw BusinessException.validationError("outcome is required");

        if (!VALID_OUTCOMES.contains(outcome.toUpperCase())) {
            throw BusinessException.validationError("Invalid outcome: " + outcome
                    + ". Valid values: " + VALID_OUTCOMES);
        }
    }

    public void validateCategory(String category) {
        if (category != null && !VALID_CATEGORIES.contains(category.toLowerCase())) {
            throw BusinessException.validationError("Invalid category: " + category
                    + ". Valid values: " + VALID_CATEGORIES);
        }
    }

    public void validateSeverity(String severity) {
        if (severity != null && !VALID_SEVERITIES.contains(severity.toUpperCase())) {
            throw BusinessException.validationError("Invalid severity: " + severity
                    + ". Valid values: " + VALID_SEVERITIES);
        }
    }

    public void validateDateRange(Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw BusinessException.validationError("fromTimestamp must be before toTimestamp");
        }
        if (from != null && from.isAfter(Instant.now())) {
            throw BusinessException.validationError("fromTimestamp cannot be in the future");
        }
    }

    public void validatePagination(int page, int size) {
        if (page < 0) throw BusinessException.validationError("page must be >= 0");
        if (size < 1 || size > 1000) throw BusinessException.validationError("size must be between 1 and 1000");
    }

    private boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }
}
