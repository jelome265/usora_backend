package com.usora.tenant.service;

import com.usora.tenant.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.UUID;

public abstract class TenantAwareService {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected UUID getCurrentTenantId() {
        return TenantContext.getCurrentTenantId();
    }

    protected String getCurrentUserId() {
        return TenantContext.getCurrentUserId();
    }

    protected boolean hasRole(String role) {
        return TenantContext.hasRole(role);
    }

    protected boolean isPlatformAdmin() {
        return TenantContext.isPlatformAdmin();
    }

    protected void validateTenantAccess(UUID tenantId) {
        if (!isPlatformAdmin()) {
            UUID currentTenant = getCurrentTenantId();
            if (currentTenant == null || !currentTenant.equals(tenantId)) {
                throw new SecurityException("Access denied to tenant: " + tenantId);
            }
        }
    }

    protected void setTenantMdc(UUID tenantId) {
        if (tenantId != null) {
            MDC.put("tenantId", tenantId.toString());
        }
    }

    protected void clearMdc() {
        MDC.remove("tenantId");
    }
}
