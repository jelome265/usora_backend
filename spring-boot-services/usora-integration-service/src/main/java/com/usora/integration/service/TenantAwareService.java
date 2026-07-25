package com.usora.integration.service;

import com.usora.integration.security.TenantContext;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class TenantAwareService {

    public String getCurrentTenant() {
        return Optional.ofNullable(TenantContext.getCurrentTenant())
                .orElseThrow(() -> new IllegalStateException("No tenant context available"));
    }

    public String getCurrentUserId() {
        return Optional.ofNullable(TenantContext.getCurrentUserId()).orElse("system");
    }

    public boolean hasTenantContext() {
        return TenantContext.getCurrentTenant() != null;
    }

    public void validateTenantAccess(String expectedTenantId) {
        String currentTenant = getCurrentTenant();
        if (!currentTenant.equals(expectedTenantId)) {
            throw new SecurityException("Tenant access denied: " + currentTenant + " != " + expectedTenantId);
        }
    }

    public <T> T withTenantContext(String tenantId, TenantAwareCallback<T> callback) {
        String previousTenant = TenantContext.getCurrentTenant();
        try {
            TenantContext.setCurrentTenant(tenantId);
            return callback.execute();
        } finally {
            if (previousTenant != null) {
                TenantContext.setCurrentTenant(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    @FunctionalInterface
    public interface TenantAwareCallback<T> {
        T execute();
    }
}
