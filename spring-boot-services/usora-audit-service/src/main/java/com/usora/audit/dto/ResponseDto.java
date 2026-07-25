package com.usora.audit.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public sealed interface ResponseDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    final class AuditEventResponse implements ResponseDto {
        private String id;
        private String tenantId;
        private String eventType;
        private String actorId;
        private String action;
        private String resourceType;
        private String resourceId;
        private String beforeState;
        private String afterState;
        private String metadata;
        private String outcome;
        private String severity;
        private String category;
        private String ipAddress;
        private String userAgent;
        private String previousHash;
        private String currentHash;
        private String signature;
        private Instant eventTimestamp;
        private Instant createdAt;
        private boolean anchored;
        private boolean forensicFlag;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    final class AuditTrailResponse implements ResponseDto {
        private String entityType;
        private String entityId;
        private List<AuditEventResponse> events;
        private long totalEvents;
        private int page;
        private int size;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    final class IntegrityResponse implements ResponseDto {
        private boolean valid;
        private String rootHash;
        private Instant verifiedAt;
        private IntegrityDetails details;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    final class IntegrityDetails {
        private long totalEventsVerified;
        private String merkleRoot;
        private String blockchainAnchorHash;
        private Instant intervalStart;
        private Instant intervalEnd;
        private List<String> mismatchedHashes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    final class TamperAlertResponse implements ResponseDto {
        private String id;
        private String tenantId;
        private String alertType;
        private String severity;
        private String description;
        private String affectedHash;
        private String expectedHash;
        private Instant detectedAt;
        private boolean resolved;
        private Instant resolvedAt;
        private String resolvedBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    final class SearchResponse implements ResponseDto {
        private List<AuditEventResponse> events;
        private long totalHits;
        private int page;
        private int size;
        private long tookMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    final class ComplianceReportResponse implements ResponseDto {
        private String reportId;
        private String tenantId;
        private String status;
        private Instant requestedAt;
        private String downloadUrl;
        private Instant expiresAt;
        private long totalEvents;
    }
}
