package com.usora.integration.exception;

import com.usora.integration.dto.ResponseDto;
import com.usora.integration.security.TenantContext;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ResponseDto.ErrorResponse> handleBusinessException(BusinessException ex, WebRequest request) {
        log.warn("Business exception: {} - {}", ex.getCode(), ex.getMessage());
        return buildErrorResponse(ex.getCode(), ex.getMessage(), ex.getDetail(), ex.getHttpStatus(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ResponseDto.ErrorResponse> handleValidationException(MethodArgumentNotValidException ex,
                                                                               WebRequest request) {
        List<ResponseDto.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> ResponseDto.FieldError.builder()
                        .field(error.getField())
                        .message(error.getDefaultMessage())
                        .rejectedValue(error.getRejectedValue() != null ? error.getRejectedValue().toString() : null)
                        .build())
                .collect(Collectors.toList());

        ResponseDto.ErrorResponse error = ResponseDto.ErrorResponse.builder()
                .code("VALIDATION_FAILED")
                .message("Request validation failed")
                .detail("One or more fields are invalid")
                .requestId(getRequestId())
                .tenantId(TenantContext.getCurrentTenant())
                .timestamp(Instant.now())
                .fieldErrors(fieldErrors)
                .build();

        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ResponseDto.ErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                               WebRequest request) {
        return buildErrorResponse("VALIDATION_FAILED", ex.getMessage(), null, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ResponseDto.ErrorResponse> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        return buildErrorResponse("ACCESS_DENIED", "Access denied", ex.getMessage(),
                HttpStatus.FORBIDDEN, request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ResponseDto.ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex,
                                                                         WebRequest request) {
        return buildErrorResponse("MISSING_HEADER", "Required header missing: " + ex.getHeaderName(),
                null, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ResponseDto.ErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
                                                                           WebRequest request) {
        return buildErrorResponse("INVALID_ARGUMENT", ex.getMessage(), null, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ResponseDto.ErrorResponse> handleIllegalState(IllegalStateException ex, WebRequest request) {
        return buildErrorResponse("ILLEGAL_STATE", ex.getMessage(), null, HttpStatus.CONFLICT, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResponseDto.ErrorResponse> handleGenericException(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        return buildErrorResponse("INTERNAL_ERROR", "An unexpected error occurred",
                ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    private ResponseEntity<ResponseDto.ErrorResponse> buildErrorResponse(String code, String message,
                                                                         String detail, HttpStatus status,
                                                                         WebRequest request) {
        ResponseDto.ErrorResponse error = ResponseDto.ErrorResponse.builder()
                .code(code)
                .message(message)
                .detail(detail)
                .requestId(getRequestId())
                .tenantId(TenantContext.getCurrentTenant())
                .timestamp(Instant.now())
                .build();

        return ResponseEntity.status(status).body(error);
    }

    private String getRequestId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
