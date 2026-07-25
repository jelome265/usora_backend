package com.usora.identity.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ResponseDto {

    private ResponseDto() {}

    @Data
    @Builder
    public static class TokenResponse {
        private String accessToken;
        private String tokenType;
        private long expiresIn;
        private String refreshToken;
        private String scope;
        private String idToken;
    }

    @Data
    @Builder
    public static class IntrospectResponse {
        private boolean active;
        private String sub;
        private String tid;
        private String clientId;
        private Instant exp;
        private Instant iat;
        private String scope;
        private Set<String> roles;
        private List<String> permissions;
        private String tokenType;
        private String username;
    }

    @Data
    @Builder
    public static class UserResponse {
        private String id;
        private String tenantId;
        private String username;
        private String email;
        private String displayName;
        private boolean enabled;
        private Set<String> roles;
        private Map<String, Object> attributes;
        private Instant createdAt;
        private Instant updatedAt;
    }
}
