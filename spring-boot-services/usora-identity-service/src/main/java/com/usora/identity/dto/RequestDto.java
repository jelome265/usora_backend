package com.usora.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Set;

public final class RequestDto {

    private RequestDto() {}

    @Data
    @Builder
    public static class TokenRequest {
        @NotBlank
        private String grantType;
        private String code;
        private String redirectUri;
        private String clientId;
        private String clientSecret;
        private String codeVerifier;
        private String refreshToken;
        private String scope;
        private String username;
        private String password;
    }

    @Data
    @Builder
    public static class UserCreateRequest {
        @NotBlank
        private String tenantId;
        @NotBlank
        @Size(min = 3, max = 100)
        private String username;
        @NotBlank
        private String email;
        private String password;
        private String displayName;
        private Set<String> roles;
        private Map<String, Object> attributes;
    }

    @Data
    @Builder
    public static class RoleUpdateRequest {
        @NotBlank
        private String userId;
        private Set<String> addRoles;
        private Set<String> removeRoles;
    }
}
