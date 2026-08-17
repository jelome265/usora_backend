package com.usora.identity.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "tenants", schema = "identity")
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TenantEntity extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_name", nullable = false, unique = true)
    private String tenantName;

    @Column(name = "domain")
    private String domain;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "public_key", columnDefinition = "TEXT")
    private String publicKey;

    @Column(name = "private_key_encrypted", columnDefinition = "TEXT")
    private String privateKeyEncrypted;

    @Column(name = "key_algorithm")
    private String keyAlgorithm;

    @Column(name = "key_id")
    private String keyId;

    @Column(name = "key_rotation_at")
    private Instant keyRotationAt;

    @Column(name = "opa_policy_url")
    private String opaPolicyUrl;

    @OneToMany(mappedBy = "tenant", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Set<OAuth2ClientEntity> oauth2Clients;

    @OneToMany(mappedBy = "tenant", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Set<UserEntity> users;

    @Entity
    @Table(name = "oauth2_clients", schema = "identity")
    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OAuth2ClientEntity extends BaseEntity {

        @Id
        @Column(name = "id", nullable = false)
        private UUID id;

        @Column(name = "client_id", nullable = false, unique = true)
        private String clientId;

        @Column(name = "client_secret")
        private String clientSecret;

        @Column(name = "client_name")
        private String clientName;

        @ElementCollection
        @CollectionTable(name = "client_redirect_uris", schema = "identity",
                joinColumns = @JoinColumn(name = "client_id"))
        @Column(name = "redirect_uri")
        private Set<String> redirectUris;

        @ElementCollection
        @CollectionTable(name = "client_grant_types", schema = "identity",
                joinColumns = @JoinColumn(name = "client_id"))
        @Column(name = "grant_type")
        private Set<String> grantTypes;

        @ElementCollection
        @CollectionTable(name = "client_scopes", schema = "identity",
                joinColumns = @JoinColumn(name = "client_id"))
        @Column(name = "scope")
        private Set<String> scopes;

        @Column(name = "require_pkce")
        private boolean requirePkce;

        @Column(name = "require_consent")
        private boolean requireConsent;

        @Column(name = "access_token_ttl_seconds")
        private long accessTokenTtlSeconds;

        @Column(name = "refresh_token_ttl_seconds")
        private long refreshTokenTtlSeconds;

        @Column(name = "enabled")
        private boolean enabled;

        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "tenant_id", nullable = false)
        private TenantEntity tenant;
    }

    @Entity
    @Table(name = "\"users\"", schema = "identity")
    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserEntity extends BaseEntity {

        @Id
        @Column(name = "id", nullable = false)
        private UUID id;

        @Column(name = "username", nullable = false)
        private String username;

        @Column(name = "email", nullable = false)
        private String email;

        @Column(name = "password_hash")
        private String passwordHash;

        @Column(name = "display_name")
        private String displayName;

        @Column(name = "enabled", nullable = false)
        private boolean enabled;

        @Column(name = "mfa_enabled")
        private boolean mfaEnabled;

        @Column(name = "mfa_type")
        private String mfaType;

        @Column(name = "last_login_at")
        private Instant lastLoginAt;

        @ElementCollection
        @CollectionTable(name = "user_roles", schema = "identity",
                joinColumns = @JoinColumn(name = "user_id"))
        @Column(name = "role")
        private Set<String> roles;

        @Column(name = "attributes", columnDefinition = "JSONB")
        private String attributes;

        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "tenant_id", nullable = false)
        private TenantEntity tenant;

        @PrePersist
        protected void onCreate() {
            super.onCreate();
            if (id == null) {
                id = UUID.randomUUID();
            }
        }
    }
}
