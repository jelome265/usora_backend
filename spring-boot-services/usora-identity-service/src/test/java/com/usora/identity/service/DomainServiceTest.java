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
        entityMapper = org.mapstruct.factory.Mappers.getMapper(EntityMapper.class);
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
        verify(eventPublisher).publishUserEvent(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void shouldRejectDuplicateUsername() {
        when(tenantRepository.findActiveById(any())).thenReturn(Optional.of(tenant));
        when(userRepository.existsByUsernameAndTenantId(eq("existing"), any())).thenReturn(true);

        var request = RequestDto.UserCreateRequest.builder()
                .tenantId(tenant.getId().toString())
                .username("existing")
                .email("existing@example.com")
                .build();

        assertThatThrownBy(() -> domainService.createUser(request))
                .hasMessageContaining("Username already exists");
    }
}
