package com.usora.identity.unit;

import com.usora.identity.config.TenantConfig;
import com.usora.identity.dto.RequestDto;
import com.usora.identity.dto.ResponseDto;
import com.usora.identity.entity.TenantEntity;
import com.usora.identity.event.DomainEventPublisher;
import com.usora.identity.mapper.EntityMapper;
import com.usora.identity.mapper.EntityMapperImpl;
import com.usora.identity.repository.OAuth2ClientRepository;
import com.usora.identity.repository.TenantRepository;
import com.usora.identity.repository.UserRepository;
import com.usora.identity.security.JwtTokenProvider;
import com.usora.identity.security.PermissionEvaluator;
import com.usora.identity.service.DomainService;
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
class ServiceUnitTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private UserRepository userRepository;
    @Mock private OAuth2ClientRepository oAuth2ClientRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private PermissionEvaluator permissionEvaluator;
    @Mock private DomainEventPublisher eventPublisher;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private PasswordEncoder passwordEncoder;
    private TenantConfig tenantConfig;
    private EntityMapper entityMapper;
    private SimpleMeterRegistry meterRegistry;
    private DomainService domainService;

    private TenantEntity tenant;

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
                .id(UUID.randomUUID())
                .tenantName("test-tenant")
                .enabled(true)
                .build();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void shouldReturnOpenIdConfiguration() {
        var config = domainService.getOpenIdConfiguration();
        assertThat(config).isNotEmpty();
        assertThat(config.get("issuer")).isEqualTo("http://localhost:8081");
        assertThat(config.get("grant_types_supported")).isNotNull();
    }

    @Test
    void shouldReturnUserinfoFromJwt() {
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", "user-1")
                .claim("tid", tenant.getId().toString())
                .claim("username", "testuser")
                .claim("email", "test@example.com")
                .claim("roles", Set.of("user"))
                .build();

        var userinfo = domainService.getUserinfo(jwt);
        assertThat(userinfo.get("sub")).isEqualTo("user-1");
        assertThat(userinfo.get("preferred_username")).isEqualTo("testuser");
    }

    @Test
    void shouldRevokeToken() {
        domainService.revoke("test-token");
        verify(redisTemplate).delete("token:refresh:test-token");
    }

    @Test
    void shouldHandleNullRevocationGracefully() {
        domainService.revoke(null);
        domainService.revoke("");
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void shouldFailWithInvalidGrantType() {
        when(oAuth2ClientRepository.findByClientId("client-1"))
                .thenReturn(Optional.of(TenantEntity.OAuth2ClientEntity.builder()
                        .clientId("client-1")
                        .tenant(tenant)
                        .build()));

        var request = RequestDto.TokenRequest.builder()
                .grantType("invalid_grant")
                .clientId("client-1")
                .build();

        assertThatThrownBy(() -> domainService.authenticate(request))
                .hasMessageContaining("Unsupported grant type");
    }
}
