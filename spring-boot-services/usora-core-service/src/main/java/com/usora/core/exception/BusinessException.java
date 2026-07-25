package com.usora.core.exception;

public class BusinessException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public BusinessException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public BusinessException(String code, String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public static BusinessException notFound(String entity, Object id) {
        return new BusinessException("NOT_FOUND", entity + " not found: " + id, 404);
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException("BAD_REQUEST", message, 400);
    }

    public static BusinessException conflict(String message) {
        return new BusinessException("CONFLICT", message, 409);
    }

    public static BusinessException forbidden(String message) {
        return new BusinessException("FORBIDDEN", message, 403);
    }
}
