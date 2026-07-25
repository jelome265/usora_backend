package com.usora.compliance.util;

import com.usora.compliance.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ValidationUtilTest {

    @Test
    void shouldRequireNonNull() {
        assertThrows(BusinessException.class, () -> ValidationUtil.requireNonNull(null, "field"));
        assertDoesNotThrow(() -> ValidationUtil.requireNonNull("value", "field"));
    }

    @Test
    void shouldRequireNonBlank() {
        assertThrows(BusinessException.class, () -> ValidationUtil.requireNonBlank("", "field"));
        assertThrows(BusinessException.class, () -> ValidationUtil.requireNonBlank("   ", "field"));
        assertDoesNotThrow(() -> ValidationUtil.requireNonBlank("value", "field"));
    }

    @Test
    void shouldRequireNonEmptyCollection() {
        assertThrows(BusinessException.class, () -> ValidationUtil.requireNonEmpty(List.of(), "list"));
        assertDoesNotThrow(() -> ValidationUtil.requireNonEmpty(List.of("a"), "list"));
    }

    @Test
    void shouldRequireNonEmptyMap() {
        assertThrows(BusinessException.class, () -> ValidationUtil.requireNonEmpty(Map.of(), "map"));
        assertDoesNotThrow(() -> ValidationUtil.requireNonEmpty(Map.of("k", "v"), "map"));
    }

    @Test
    void shouldValidateJurisdiction() {
        assertThrows(BusinessException.class, () -> ValidationUtil.validateJurisdiction("invalid"));
        assertDoesNotThrow(() -> ValidationUtil.validateJurisdiction("eu_gdpr"));
    }

    @Test
    void shouldValidateReportFormat() {
        assertThrows(BusinessException.class, () -> ValidationUtil.validateReportFormat("docx"));
        assertDoesNotThrow(() -> ValidationUtil.validateReportFormat("pdf"));
        assertDoesNotThrow(() -> ValidationUtil.validateReportFormat("xlsx"));
        assertDoesNotThrow(() -> ValidationUtil.validateReportFormat("csv"));
    }

    @Test
    void shouldValidateSeverity() {
        assertThrows(BusinessException.class, () -> ValidationUtil.validateSeverity("urgent"));
        assertDoesNotThrow(() -> ValidationUtil.validateSeverity("critical"));
        assertDoesNotThrow(() -> ValidationUtil.validateSeverity("info"));
    }

    @Test
    void shouldValidateEmail() {
        assertTrue(ValidationUtil.isValidEmail("test@example.com"));
        assertFalse(ValidationUtil.isValidEmail("invalid"));
        assertFalse(ValidationUtil.isValidEmail(null));
    }

    @Test
    void shouldValidateUUID() {
        assertTrue(ValidationUtil.isValidUUID("550e8400-e29b-41d4-a716-446655440000"));
        assertFalse(ValidationUtil.isValidUUID("not-a-uuid"));
    }
}
