package com.usora.tenant.unit;

import com.usora.tenant.entity.TenantEntity;
import com.usora.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
class RepositoryUnitTest {

    @Autowired
    private TenantRepository tenantRepository;

    private TenantEntity activeTenant;
    private TenantEntity suspendedTenant;

    @BeforeEach
    void setUp() {
        activeTenant = new TenantEntity();
        activeTenant.setName("Active Tenant");
        activeTenant.setDomain("active.example.com");
        activeTenant.setPlan("business");
        activeTenant.setRegion("us-east");
        activeTenant.setStatus(TenantEntity.TenantStatus.ACTIVE);
        activeTenant.setAdminEmail("active@example.com");
        activeTenant = tenantRepository.save(activeTenant);

        suspendedTenant = new TenantEntity();
        suspendedTenant.setName("Suspended Tenant");
        suspendedTenant.setDomain("suspended.example.com");
        suspendedTenant.setPlan("free");
        suspendedTenant.setRegion("eu-west");
        suspendedTenant.setStatus(TenantEntity.TenantStatus.SUSPENDED);
        suspendedTenant.setAdminEmail("suspended@example.com");
        suspendedTenant = tenantRepository.save(suspendedTenant);
    }

    @Test
    void shouldFindByDomain() {
        Optional<TenantEntity> found = tenantRepository.findByDomain("active.example.com");
        assertTrue(found.isPresent());
        assertEquals("Active Tenant", found.get().getName());
    }

    @Test
    void shouldReturnEmptyForNonExistentDomain() {
        Optional<TenantEntity> found = tenantRepository.findByDomain("nonexistent.example.com");
        assertTrue(found.isEmpty());
    }

    @Test
    void shouldFindByStatus() {
        List<TenantEntity> activeTenants = tenantRepository.findByStatus(TenantEntity.TenantStatus.ACTIVE);
        assertEquals(1, activeTenants.size());
        assertEquals("Active Tenant", activeTenants.get(0).getName());

        List<TenantEntity> suspendedTenants = tenantRepository.findByStatus(TenantEntity.TenantStatus.SUSPENDED);
        assertEquals(1, suspendedTenants.size());
        assertEquals("Suspended Tenant", suspendedTenants.get(0).getName());
    }

    @Test
    void shouldFindByStatusWithPagination() {
        Page<TenantEntity> activePage = tenantRepository.findByStatus(
                TenantEntity.TenantStatus.ACTIVE, PageRequest.of(0, 10));
        assertEquals(1, activePage.getTotalElements());
    }

    @Test
    void shouldCheckDomainExists() {
        assertTrue(tenantRepository.existsByDomain("active.example.com"));
        assertFalse(tenantRepository.existsByDomain("missing.example.com"));
    }

    @Test
    void shouldCountByStatus() {
        assertEquals(1, tenantRepository.countByStatus(TenantEntity.TenantStatus.ACTIVE));
        assertEquals(1, tenantRepository.countByStatus(TenantEntity.TenantStatus.SUSPENDED));
        assertEquals(0, tenantRepository.countByStatus(TenantEntity.TenantStatus.PROVISIONING));
    }

    @Test
    void shouldFindByPlan() {
        Page<TenantEntity> businessTenants = tenantRepository.findByPlan("business", PageRequest.of(0, 10));
        assertEquals(1, businessTenants.getTotalElements());

        Page<TenantEntity> freeTenants = tenantRepository.findByPlan("free", PageRequest.of(0, 10));
        assertEquals(1, freeTenants.getTotalElements());
    }

    @Test
    void shouldSaveAndFindById() {
        TenantEntity newTenant = new TenantEntity();
        newTenant.setName("New Tenant");
        newTenant.setDomain("new.example.com");
        newTenant.setPlan("starter");
        newTenant.setRegion("ap-southeast");
        newTenant.setStatus(TenantEntity.TenantStatus.PROVISIONING);
        newTenant.setAdminEmail("new@example.com");

        TenantEntity saved = tenantRepository.save(newTenant);
        assertNotNull(saved.getId());

        Optional<TenantEntity> found = tenantRepository.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("New Tenant", found.get().getName());
    }

    @Test
    void shouldDeleteTenant() {
        tenantRepository.delete(activeTenant);
        assertFalse(tenantRepository.findById(activeTenant.getId()).isPresent());
    }
}
