package com.usora.core.unit;

import com.usora.core.entity.TenantEntity;
import com.usora.core.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
class RepositoryUnitTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Test
    void shouldSaveAndFindTenant() {
        var entity = new TenantEntity("test-tenant", Map.of("feature_x", true));
        tenantRepository.save(entity);

        var found = tenantRepository.findByTenantId("test-tenant");

        assertTrue(found.isPresent());
        assertEquals("test-tenant", found.get().getTenantId());
    }

    @Test
    void shouldCheckTenantExistence() {
        var entity = new TenantEntity("exists-tenant", Map.of());
        tenantRepository.save(entity);

        assertTrue(tenantRepository.existsByTenantId("exists-tenant"));
        assertFalse(tenantRepository.existsByTenantId("non-existent"));
    }
}
