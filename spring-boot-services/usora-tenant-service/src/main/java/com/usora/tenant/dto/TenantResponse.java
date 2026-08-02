package com.usora.tenant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantResponse {
    private String id;
    private String name;
    private String domain;
    private String plan;
    private String region;
    private String status;
    private String adminEmail;
    private int maxUsers;
    private long storageQuotaBytes;
    private Map<String, Object> features;
    private Map<String, Object> config;
    private String stripeCustomerId;
    private String provisioningStatus;
    private Instant createdAt;
    private Instant updatedAt;
}
