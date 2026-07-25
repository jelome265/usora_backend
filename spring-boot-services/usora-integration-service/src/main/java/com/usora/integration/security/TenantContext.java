package com.usora.integration.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TenantContext {

    private static final Logger log = LoggerFactory.getLogger(TenantContext.class);

    private static final ThreadLocal<String> currentTenant = new InheritableThreadLocal<>();
    private static final ThreadLocal<String> currentUserId = new InheritableThreadLocal<>();

    private TenantContext() {}

    public static void setCurrentTenant(String tenantId) {
        log.debug("Setting tenant: {}", tenantId);
        currentTenant.set(tenantId);
    }

    public static String getCurrentTenant() {
        return currentTenant.get();
    }

    public static void setCurrentUserId(String userId) {
        currentUserId.set(userId);
    }

    public static String getCurrentUserId() {
        return currentUserId.get();
    }

    public static void clear() {
        currentTenant.remove();
        currentUserId.remove();
    }

    public static String getRequiredCurrentTenant() {
        String tenantId = currentTenant.get();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant context available");
        }
        return tenantId;
    }
}
