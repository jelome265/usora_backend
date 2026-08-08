package com.usora.identity.repository;

import com.usora.identity.entity.TenantEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.data.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class TenantRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private TenantRepository tenantRepository;

    private TenantEntity tenant;

    @BeforeEach
    void setUp() {
        tenant = TenantEntity.builder()
                .id(UUID.randomUUID())
                .tenantName("test-tenant-" + System.currentTimeMillis())
                .domain("test.usora.io")
                .enabled(true)
                .keyAlgorithm("RS256")
                .build();
        entityManager.persistAndFlush(tenant);
    }

    @Test
    void shouldFindTenantByTenantName() {
        var found = tenantRepository.findByTenantName(tenant.getTenantName());
        assertThat(found).isPresent();
        assertThat(found.get().getTenantName()).isEqualTo(tenant.getTenantName());
    }

    @Test
    void shouldFindTenantByDomain() {
        var found = tenantRepository.findByDomain(tenant.getDomain());
        assertThat(found).isPresent();
        assertThat(found.get().getDomain()).isEqualTo(tenant.getDomain());
    }

    @Test
    void shouldFindActiveTenantById() {
        var found = tenantRepository.findActiveById(tenant.getId());
        assertThat(found).isPresent();
        assertThat(found.get().isEnabled()).isTrue();
    }

    @Test
    void shouldNotFindDisabledTenant() {
        tenant.setEnabled(false);
        entityManager.persistAndFlush(tenant);

        var found = tenantRepository.findActiveById(tenant.getId());
        assertThat(found).isEmpty();
    }

    @Test
    void shouldReturnEmptyForNonExistentTenant() {
        var found = tenantRepository.findByTenantName("non-existent");
        assertThat(found).isEmpty();
    }
}
