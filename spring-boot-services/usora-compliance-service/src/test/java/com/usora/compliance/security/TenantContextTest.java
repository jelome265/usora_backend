package com.usora.compliance.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shouldSetAndGetTenant() {
        TenantContext.setCurrentTenant("tenant1");
        assertEquals("tenant1", TenantContext.getCurrentTenant());
    }

    @Test
    void shouldSetAndGetJurisdiction() {
        TenantContext.setCurrentJurisdiction("eu_gdpr");
        assertEquals("eu_gdpr", TenantContext.getCurrentJurisdiction());
    }

    @Test
    void shouldClearContext() {
        TenantContext.setCurrentTenant("tenant1");
        TenantContext.setCurrentJurisdiction("eu_gdpr");
        TenantContext.clear();
        assertNull(TenantContext.getCurrentTenant());
        assertNull(TenantContext.getCurrentJurisdiction());
    }

    @Test
    void shouldReturnNullWhenNotSet() {
        assertNull(TenantContext.getCurrentTenant());
        assertNull(TenantContext.getCurrentJurisdiction());
    }
}
