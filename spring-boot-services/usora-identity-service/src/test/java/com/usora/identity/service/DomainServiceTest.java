package com.usora.identity.service;

import com.usora.identity.config.TenantConfig;
import com.usora.identity.dto.RequestDto;
import com.usora.identity.entity.TenantEntity;
import com.usora.identity.event.DomainEventPublisher;
import com.usora.identity.mapper.EntityMapper;
import com.usora.identity.repository.OAuth2ClientRepository;
import com.usora.identity.repository.TenantRepository;
import com.usora.identity.repository.UserRepository;
import com.usora.identity.security.JwtTokenProvider;
import com.usora.identity.security.PermissionEvaluator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DomainServiceTest {

    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private OAuth2ClientRepository oAuth2ClientRepository;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private PermissionEvaluator permissionEvaluator;
    @Mock
    private DomainEventPublisher eventPublisher;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private PasswordEncoder passwordEncoder;
    private TenantConfig tenantConfig;
    private EntityMapper entityMapper;
    private MeterRegistry meterRegistry;
    private DomainService domainService;

    private TenantEntity tenant;
    private TenantEntity.OAuth2ClientEntity client;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        tenantConfig = new TenantConfig();
        entityMapper = new EntityMapperImpl();
        meterRegistry = new SimpleMeterRegistry();

        domainService = new DomainService(
                tenantRepository, userRepository, oAuth2ClientRepository,
                jwtTokenProvider, permissionEvaluator, passwordEncoder,
                entityMapper, eventPublisher, redisTemplate, tenantConfig, meterRegistry
        );

        tenant = TenantEntity.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .tenantName("test-tenant")
                .enabled(true)
                .build();

        client = TenantEntity.OAuth2ClientEntity.builder()
                .id(UUID.randomUUID())
                .clientId("test-client")
                .clientSecret(passwordEncoder.encode("secret"))
                .tenant(tenant)
                .accessTokenTtlSeconds(900)
                .refreshTokenTtlSeconds(604800)
                .grantTypes(Set.of("client_credentials"))
                .scopes(Set.of("admin", "tenant:read"))
                .requirePkce(false)
                .enabled(true)
                .build();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void shouldFailAuthenticationWithInvalidClient() {
        when(oAuth2ClientRepository.findByClientId("invalid")).thenReturn(Optional.empty());

        var request = RequestDto.TokenRequest.builder()
                .grantType("client_credentials")
                .clientId("invalid")
                .build();

        assertThatThrownBy(() -> domainService.authenticate(request))
                .hasMessageContaining("Invalid client");
    }

    @Test
    void shouldFailWithUnsupportedGrantType() {
        when(oAuth2ClientRepository.findByClientId("test-client")).thenReturn(Optional.of(client));

        var request = RequestDto.TokenRequest.builder()
                .grantType("unsupported")
                .clientId("test-client")
                .build();

        assertThatThrownBy(() -> domainService.authenticate(request))
                .hasMessageContaining("Unsupported grant type");
    }

    @Test
    void shouldReturnInactiveForIntrospectExpiredToken() {
        var response = domainService.introspect("invalid-token");
        assertThat(response.isActive()).isFalse();
    }

    @Test
    void shouldReturnInactiveForNullToken() {
        var response = domainService.introspect(null);
        assertThat(response.isActive()).isFalse();
    }

    @Test
    void shouldReturnInactiveForBlankToken() {
        var response = domainService.introspect("  ");
        assertThat(response.isActive()).isFalse();
    }

    @Test
    void shouldCreateUser() {
        when(tenantRepository.findActiveById(any())).thenReturn(Optional.of(tenant));
        when(userRepository.existsByUsernameAndTenantId(anyString(), any())).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // F-017: an ordinary tenant-scoped admin caller must have a tenant
        // context matching the request's tenantId -- this is the normal,
        // successful path (not the platform-admin path exercised by the
        // tests below).
        com.usora.identity.security.TenantContext.getContext().setTenantId(tenant.getId().toString());
        try {
            var request = RequestDto.UserCreateRequest.builder()
                    .tenantId(tenant.getId().toString())
                    .username("newuser")
                    .email("new@example.com")
                    .displayName("New User")
                    .password("Password1!")
                    .roles(Set.of("user"))
                    .build();

            var response = domainService.createUser(request);

            assertThat(response).isNotNull();
            assertThat(response.getUsername()).isEqualTo("newuser");
            assertThat(response.getEmail()).isEqualTo("new@example.com");
            assertThat(response.getTenantId()).isEqualTo(tenant.getId().toString());
            assertThat(response.isEnabled()).isTrue();

            verify(userRepository).save(any());
            // BUG found while implementing F-017: this previously verified
            // publishTokenEvent(anyString(), anyString(), anyString(), anyMap())
            // -- a call shape that could never have existed, since
            // publishTokenEvent's real fourth parameter is a String, not a
            // Map. The production code had the same bug (calling
            // publishTokenEvent with a Map argument); both are now fixed to
            // use publishUserEvent, which is what this assertion checks.
            verify(eventPublisher).publishUserEvent(eq("user.created"), anyString(), anyString(), anyMap());
        } finally {
            com.usora.identity.security.TenantContext.clear();
        }
    }

    @Test
    void shouldRejectCrossTenantUserCreation() {
        // F-017 regression: an ordinary tenant-scoped admin whose own
        // tenant does not match the request's tenantId must be rejected --
        // this was already correctly enforced before this change, kept
        // here as an explicit regression test per the finding's own
        // acceptance criteria ("tenant admins can never create ... in
        // another tenant").
        when(tenantRepository.findActiveById(any())).thenReturn(Optional.of(tenant));

        com.usora.identity.security.TenantContext.getContext().setTenantId(UUID.randomUUID().toString());
        try {
            var request = RequestDto.UserCreateRequest.builder()
                    .tenantId(tenant.getId().toString())
                    .username("newuser")
                    .email("new@example.com")
                    .build();

            assertThatThrownBy(() -> domainService.createUser(request))
                    .hasMessageContaining("Cannot create a user in a tenant other than the caller's own tenant");
        } finally {
            com.usora.identity.security.TenantContext.clear();
        }
    }

    @Test
    void shouldRejectPlatformAdminActionWithoutElevatedScope() {
        // F-017 regression: a caller with NO tenant context at all (e.g.
        // this service's own client_credentials "admin"-scoped client --
        // see the RISK TO VERIFY note on PR #150) previously fell through
        // every tenant check entirely. Now requires a distinct
        // "platform:admin" authority; without it, even a caller with no
        // tenant context is rejected rather than silently allowed through.
        when(tenantRepository.findActiveById(any())).thenReturn(Optional.of(tenant));
        com.usora.identity.security.TenantContext.clear();

        var request = RequestDto.UserCreateRequest.builder()
                .tenantId(tenant.getId().toString())
                .username("newuser")
                .email("new@example.com")
                .platformAdminReason("emergency onboarding")
                .build();

        assertThatThrownBy(() -> domainService.createUser(request))
                .hasMessageContaining("platform:admin");
    }

    @Test
    void shouldRejectPlatformAdminActionWithoutReason() {
        // F-017 regression: even with the elevated scope granted, a
        // platform-admin action must supply an explicit justification --
        // "require an explicit elevated permission AND audit reason" per
        // the finding's own remediation plan.
        when(tenantRepository.findActiveById(any())).thenReturn(Optional.of(tenant));
        com.usora.identity.security.TenantContext.clear();

        var authorities = java.util.List.<org.springframework.security.core.GrantedAuthority>of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_platform:admin"));
        var authentication = new org.springframework.security.authentication.TestingAuthenticationToken(
                "platform-svc", null, authorities);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            var request = RequestDto.UserCreateRequest.builder()
                    .tenantId(tenant.getId().toString())
                    .username("newuser")
                    .email("new@example.com")
                    .build();

            assertThatThrownBy(() -> domainService.createUser(request))
                    .hasMessageContaining("platformAdminReason is required");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void shouldAllowPlatformAdminActionWithScopeAndReason() {
        // F-017 regression: the positive case -- a caller with no tenant
        // context, the elevated scope, AND a reason succeeds, and the
        // resulting audit event is marked as a platform-admin action.
        when(tenantRepository.findActiveById(any())).thenReturn(Optional.of(tenant));
        when(userRepository.existsByUsernameAndTenantId(anyString(), any())).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        com.usora.identity.security.TenantContext.clear();

        var authorities = java.util.List.<org.springframework.security.core.GrantedAuthority>of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_platform:admin"));
        var authentication = new org.springframework.security.authentication.TestingAuthenticationToken(
                "platform-svc", null, authorities);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            var request = RequestDto.UserCreateRequest.builder()
                    .tenantId(tenant.getId().toString())
                    .username("newuser")
                    .email("new@example.com")
                    .platformAdminReason("break-glass tenant setup, ticket OPS-1234")
                    .build();

            var response = domainService.createUser(request);

            assertThat(response).isNotNull();
            verify(eventPublisher).publishUserEvent(
                    eq("user.created"), anyString(), anyString(),
                    argThat(details -> Boolean.TRUE.equals(details.get("platform_admin_action"))));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void shouldRejectDuplicateUsername() {
        when(tenantRepository.findActiveById(any())).thenReturn(Optional.of(tenant));
        when(userRepository.existsByUsernameAndTenantId(eq("existing"), any())).thenReturn(true);

        com.usora.identity.security.TenantContext.getContext().setTenantId(tenant.getId().toString());
        try {
            var request = RequestDto.UserCreateRequest.builder()
                    .tenantId(tenant.getId().toString())
                    .username("existing")
                    .email("existing@example.com")
                    .build();

            assertThatThrownBy(() -> domainService.createUser(request))
                    .hasMessageContaining("Username already exists");
        } finally {
            com.usora.identity.security.TenantContext.clear();
        }
    }
}
