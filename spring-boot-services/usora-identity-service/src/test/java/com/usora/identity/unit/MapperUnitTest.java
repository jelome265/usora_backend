package com.usora.identity.unit;

import com.usora.identity.dto.RequestDto;
import com.usora.identity.dto.ResponseDto;
import com.usora.identity.entity.TenantEntity;
import com.usora.identity.mapper.EntityMapper;
import com.usora.identity.mapper.EntityMapperImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MapperUnitTest {

    private EntityMapper mapper;
    private TenantEntity tenant;

    @BeforeEach
    void setUp() {
        mapper = new EntityMapperImpl();
        tenant = TenantEntity.builder()
                .id(UUID.randomUUID())
                .tenantName("mapper-test-tenant")
                .enabled(true)
                .build();
    }

    @Test
    void shouldMapCreateRequestToEntity() {
        var request = RequestDto.UserCreateRequest.builder()
                .tenantId(tenant.getId().toString())
                .username("mapperuser")
                .email("mapper@example.com")
                .displayName("Mapper User")
                .password("Password1!")
                .roles(Set.of("user", "editor"))
                .attributes(Map.of("department", "engineering", "level", "senior"))
                .build();

        var entity = mapper.toUserEntity(request, tenant);

        assertThat(entity.getId()).isNotNull();
        assertThat(entity.getUsername()).isEqualTo("mapperuser");
        assertThat(entity.getEmail()).isEqualTo("mapper@example.com");
        assertThat(entity.getDisplayName()).isEqualTo("Mapper User");
        assertThat(entity.isEnabled()).isTrue();
        assertThat(entity.getRoles()).containsExactlyInAnyOrder("user", "editor");
        assertThat(entity.getTenant().getId()).isEqualTo(tenant.getId());
        assertThat(entity.getAttributes()).isNotNull();
    }

    @Test
    void shouldMapEntityToResponse() {
        var entity = TenantEntity.UserEntity.builder()
                .id(UUID.randomUUID())
                .username("responseuser")
                .email("response@example.com")
                .displayName("Response User")
                .enabled(true)
                .roles(Set.of("user", "admin"))
                .tenant(tenant)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        var response = mapper.toUserResponse(entity);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(entity.getId().toString());
        assertThat(response.getUsername()).isEqualTo("responseuser");
        assertThat(response.getEmail()).isEqualTo("response@example.com");
        assertThat(response.getDisplayName()).isEqualTo("Response User");
        assertThat(response.isEnabled()).isTrue();
        assertThat(response.getTenantId()).isEqualTo(tenant.getId().toString());
        assertThat(response.getRoles()).contains("admin");
        assertThat(response.getCreatedAt()).isNotNull();
    }

    @Test
    void shouldHandleNullAttributes() {
        var request = RequestDto.UserCreateRequest.builder()
                .tenantId(tenant.getId().toString())
                .username("nouser")
                .email("no@example.com")
                .build();

        var entity = mapper.toUserEntity(request, tenant);
        assertThat(entity.getAttributes()).isEqualTo("{}");
    }

    @Test
    void shouldHandleNullEntityAttributes() {
        var entity = TenantEntity.UserEntity.builder()
                .id(UUID.randomUUID())
                .username("nullattr")
                .email("null@example.com")
                .tenant(tenant)
                .enabled(true)
                .build();

        var response = mapper.toUserResponse(entity);
        assertThat(response.getAttributes()).isNotNull().isEmpty();
    }
}
