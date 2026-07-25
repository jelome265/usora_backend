package com.usora.audit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public sealed interface RequestDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    final class AuditEventRequest implements RequestDto {
        @NotNull
        private Instant timestamp;

        @NotBlank
        private String actorId;

        @NotBlank
        private String action;

        @NotBlank
        private String resourceType;

        @NotBlank
        private String resourceId;

        @NotBlank
        private String tenantId;

        @NotBlank
        private String outcome;

        private String eventType;
        private String beforeState;
        private String afterState;
        private String metadata;
        private String severity;
        private String category;
        private String ipAddress;
        private String userAgent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    final class AuditSearchRequest implements RequestDto {
        private String tenantId;
        private String actorId;
        private String action;
        private String resourceType;
        private String resourceId;
        private String category;
        private String severity;
        private String outcome;
        private Instant fromTimestamp;
        private Instant toTimestamp;
        private List<String> eventTypes;
        private String query;

        @NotNull
        private Pagination pagination;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    final class Pagination {
        private int page = 0;
        private int size = 20;
        private String sortBy = "eventTimestamp";
        private String sortDirection = "DESC";
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    final class IntegrityVerificationRequest implements RequestDto {
        @NotBlank
        private String tenantId;

        private String startHash;
        private Instant fromTimestamp;
        private Instant toTimestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    final class ComplianceReportRequest implements RequestDto {
        @NotBlank
        private String tenantId;

        @NotNull
        private Instant fromTimestamp;

        @NotNull
        private Instant toTimestamp;

        private String reportType;
        private List<String> categories;
        private String format;
        private boolean includeEvidence;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    final class TamperAlertRequest implements RequestDto {
        private String tenantId;
        private boolean resolved;
        private String severity;
        private Pagination pagination;
    }
}
