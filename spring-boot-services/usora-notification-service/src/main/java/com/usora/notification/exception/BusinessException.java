package com.usora.notification.exception;

public abstract class BusinessException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    protected BusinessException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    protected BusinessException(String code, String message, int httpStatus, Throwable cause) {
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

    public static class NotificationFailureException extends BusinessException {
        public NotificationFailureException(String message) {
            super("NOTIFICATION_FAILURE", message, 500);
        }

        public NotificationFailureException(String message, Throwable cause) {
            super("NOTIFICATION_FAILURE", message, 500, cause);
        }
    }

    public static class InvalidChannelException extends BusinessException {
        public InvalidChannelException(String channel) {
            super("INVALID_CHANNEL", "Unsupported notification channel: " + channel, 400);
        }
    }

    public static class ProviderUnavailableException extends BusinessException {
        public ProviderUnavailableException(String provider) {
            super("PROVIDER_UNAVAILABLE", "Notification provider unavailable: " + provider, 503);
        }

        public ProviderUnavailableException(String provider, Throwable cause) {
            super("PROVIDER_UNAVAILABLE", "Notification provider unavailable: " + provider, 503, cause);
        }
    }

    public static class NotificationNotFoundException extends BusinessException {
        public NotificationNotFoundException(String id) {
            super("NOTIFICATION_NOT_FOUND", "Notification not found: " + id, 404);
        }
    }

    public static class TemplateRenderException extends BusinessException {
        public TemplateRenderException(String templateId, Throwable cause) {
            super("TEMPLATE_RENDER_ERROR",
                    "Failed to render template: " + templateId, 500, cause);
        }
    }

    public static class InvalidAddressException extends BusinessException {
        public InvalidAddressException(String address, String channel) {
            super("INVALID_ADDRESS",
                    "Invalid " + channel + " address: " + address, 400);
        }
    }
}
