package com.usora.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class ResponseDto {

    private ResponseDto() {}

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NotificationResponse {
        private UUID id;
        private String tenantId;
        private String channel;
        private String toAddress;
        private String templateId;
        private String status;
        private String priority;
        private String errorMessage;
        private int retryCount;
        private LocalDateTime sentAt;
        private LocalDateTime deliveredAt;
        private LocalDateTime failedAt;
        private LocalDateTime acknowledgedAt;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NotificationListResponse {
        private List<NotificationResponse> notifications;
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
        private boolean first;
        private boolean last;
    }
}
