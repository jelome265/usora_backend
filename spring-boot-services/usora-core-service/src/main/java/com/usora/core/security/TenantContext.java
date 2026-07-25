package com.usora.core.security;

public final class TenantContext {

    private static final ThreadLocal<String> currentTenant = new ThreadLocal<>();

    private TenantContext() {}

    public static void setCurrentTenantId(String tenantId) {
        currentTenant.set(tenantId);
    }

    public static String getCurrentTenantId() {
        return currentTenant.get();
    }

    public static void clear() {
        currentTenant.remove();
    }
}
