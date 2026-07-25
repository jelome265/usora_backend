package com.usora.identity.mapper;

import com.usora.identity.dto.RequestDto;
import com.usora.identity.entity.TenantEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {EntityMapperImpl.class})
class EntityMapperTest {

    @Autowired
    private EntityMapper entityMapper;

    private TenantEntity tenant;
    private RequestDto.UserCreateRequest createRequest;

    @BeforeEach
    void setUp() {
        tenant = TenantEntity.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .tenantName("test-tenant")
                .enabled(true)
                .build();

        createRequest = RequestDto.UserCreateRequest.builder()
                .tenantId("00000000-0000-0000-0000-000000000001")
                .username("testuser")
                .email("test@example.com")
                .displayName("Test User")
                .roles(Set.of("user", "reviewer"))
                .attributes(Map.of("department", "engineering"))
                .build();
    }

    @Test
    void shouldMapUserCreateRequestToEntity() {
        var entity = entityMapper.toUserEntity(createRequest, tenant);

        assertThat(entity).isNotNull();
        assertThat(entity.getId()).isNotNull();
        assertThat(entity.getUsername()).isEqualTo("testuser");
        assertThat(entity.getEmail()).isEqualTo("test@example.com");
        assertThat(entity.getDisplayName()).isEqualTo("Test User");
        assertThat(entity.isEnabled()).isTrue();
        assertThat(entity.getTenant().getId()).isEqualTo(tenant.getId());
    }

    @Test
    void shouldMapUserEntityToResponse() {
        var entity = TenantEntity.UserEntity.builder()
                .id(UUID.randomUUID())
                .username("testuser")
                .email("test@example.com")
                .displayName("Test User")
                .enabled(true)
                .roles(Set.of("user"))
                .tenant(tenant)
                .build();

        var response = entityMapper.toUserResponse(entity);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(entity.getId().toString());
        assertThat(response.getUsername()).isEqualTo("testuser");
        assertThat(response.getEmail()).isEqualTo("test@example.com");
        assertThat(response.getDisplayName()).isEqualTo("Test User");
        assertThat(response.getTenantId()).isEqualTo(tenant.getId().toString());
        assertThat(response.getRoles()).contains("user");
    }
}
