package com.usora.tenant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OnboardRequest {
    @NotBlank(message = "Tenant name is required")
    @Size(max = 255)
    private String name;

    @NotBlank(message = "Domain is required")
    @Size(max = 255)
    private String domain;

    @NotBlank(message = "Plan is required")
    @Size(max = 50)
    private String plan;

    @NotBlank(message = "Region is required")
    @Size(max = 50)
    private String region;

    @NotBlank(message = "Admin email is required")
    @Email(message = "Invalid email format")
    @Size(max = 255)
    private String adminEmail;

    private Map<String, Object> features;
}

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

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SuspendRequest {
    @NotBlank(message = "Reason is required")
    @Size(max = 500)
    private String reason;
}
