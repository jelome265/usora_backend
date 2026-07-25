package com.usora.audit.service;

import com.usora.audit.security.TenantContext;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class TenantAwareService {

    public String getCurrentTenantId() {
        return Optional.ofNullable(TenantContext.getTenantId()).orElse("default");
    }

    public String getCurrentUserId() {
        return Optional.ofNullable(TenantContext.getUserId()).orElse("system");
    }

    public boolean isCrossTenantAccess(String targetTenantId) {
        String currentTenant = getCurrentTenantId();
        return !"default".equals(currentTenant) && !currentTenant.equals(targetTenantId);
    }

    public void validateTenantAccess(String targetTenantId) {
        if (isCrossTenantAccess(targetTenantId)) {
            throw new SecurityException("Cross-tenant access denied: " + getCurrentTenantId() + " -> " + targetTenantId);
        }
    }
}
