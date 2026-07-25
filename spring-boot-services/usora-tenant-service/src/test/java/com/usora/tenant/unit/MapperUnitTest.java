package com.usora.tenant.unit;

import com.usora.tenant.dto.OnboardRequest;
import com.usora.tenant.dto.TenantResponse;
import com.usora.tenant.entity.TenantEntity;
import com.usora.tenant.mapper.EntityMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class MapperUnitTest {

    @Autowired
    private EntityMapper entityMapper;

    @Test
    void shouldMapOnboardRequestToEntity() {
        OnboardRequest request = OnboardRequest.builder()
                .name("Test Tenant")
                .domain("test.example.com")
                .plan("business")
                .region("us-east")
                .adminEmail("admin@test.com")
                .features(Map.of("feature_x", true, "max_users", 500))
                .build();

        TenantEntity entity = entityMapper.toEntity(request);

        assertNotNull(entity);
        assertNull(entity.getId());
        assertEquals("Test Tenant", entity.getName());
        assertEquals("test.example.com", entity.getDomain());
        assertEquals("business", entity.getPlan());
        assertEquals("us-east", entity.getRegion());
        assertEquals("admin@test.com", entity.getAdminEmail());
        assertEquals("PROVISIONING", entity.getStatus().name());
        assertEquals(100, entity.getMaxUsers());
        assertEquals(107374182400L, entity.getStorageQuotaBytes());
        assertNotNull(entity.getFeatures());
    }

    @Test
    void shouldMapEntityToResponse() {
        TenantEntity entity = new TenantEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("Test Tenant");
        entity.setDomain("test.example.com");
        entity.setPlan("enterprise");
        entity.setRegion("eu-west");
        entity.setStatus(TenantEntity.TenantStatus.ACTIVE);
        entity.setAdminEmail("admin@test.com");
        entity.setMaxUsers(1000);
        entity.setStorageQuotaBytes(1099511627776L);
        entity.setFeatures("{\"feature_x\":true}");
        entity.setConfig("{\"theme\":\"dark\"}");
        entity.setStripeCustomerId("cus_test123");
        entity.setProvisioningStatus("COMPLETED");

        TenantResponse response = entityMapper.toResponse(entity);

        assertNotNull(response);
        assertEquals(entity.getId().toString(), response.getId());
        assertEquals("Test Tenant", response.getName());
        assertEquals("test.example.com", response.getDomain());
        assertEquals("enterprise", response.getPlan());
        assertEquals("eu-west", response.getRegion());
        assertEquals("ACTIVE", response.getStatus());
        assertEquals("admin@test.com", response.getAdminEmail());
        assertEquals(1000, response.getMaxUsers());
        assertEquals(1099511627776L, response.getStorageQuotaBytes());
        assertEquals("cus_test123", response.getStripeCustomerId());
        assertEquals("COMPLETED", response.getProvisioningStatus());
        assertTrue(response.getFeatures().containsKey("feature_x"));
        assertTrue(response.getConfig().containsKey("theme"));
    }

    @Test
    void shouldHandleNullFeatures() {
        OnboardRequest request = OnboardRequest.builder()
                .name("Minimal Tenant")
                .domain("minimal.example.com")
                .plan("free")
                .region("us-west")
                .adminEmail("minimal@example.com")
                .build();

        TenantEntity entity = entityMapper.toEntity(request);
        assertNull(entity.getFeatures());
    }
}
