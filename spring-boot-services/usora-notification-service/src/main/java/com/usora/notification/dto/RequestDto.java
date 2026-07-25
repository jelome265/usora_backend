package com.usora.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

public final class RequestDto {

    private RequestDto() {}

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SendNotificationRequest {
        @NotBlank(message = "Recipient address is required")
        private String to;

        @NotNull(message = "Notification channel is required")
        private String channel;

        @NotBlank(message = "Template ID is required")
        private String templateId;

        private String subject;

        private Map<String, Object> variables;

        private Map<String, Object> attachments;

        @Builder.Default
        private String priority = "NORMAL";
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NotificationListRequest {
        private int page = 0;
        private int size = 20;
        private String channel;
        private String status;
        private LocalDateTime dateFrom;
        private LocalDateTime dateTo;
        private String sortBy = "createdAt";
        private String sortDir = "desc";
    }
}
