package com.usora.compliance.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldHandleBusinessException() {
        var ex = BusinessException.notFound("Rule", "RULE-001");
        var response = handler.handleBusinessException(ex);
        assertEquals(HttpStatus.NOT_FOUND.value(), response.getStatusCode().value());
        assertTrue(response.getBody().containsKey("code"));
        assertEquals("NOT_FOUND", response.getBody().get("code"));
    }

    @Test
    void shouldHandleGeneralException() {
        var ex = new RuntimeException("Unexpected error");
        var response = handler.handleGeneral(ex);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), response.getStatusCode().value());
    }
}
