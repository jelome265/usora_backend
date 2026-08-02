package com.usora.tenant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfigUpdateRequest {
    private Map<String, Object> config;
    private Integer maxUsers;
    private Long storageQuotaBytes;
    private String plan;
}
