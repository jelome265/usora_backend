package com.usora.compliance.security;

public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_JURISDICTION = new ThreadLocal<>();

    private TenantContext() {}

    public static void setCurrentTenant(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String getCurrentTenant() {
        return CURRENT_TENANT.get();
    }

    public static void setCurrentJurisdiction(String jurisdiction) {
        CURRENT_JURISDICTION.set(jurisdiction);
    }

    public static String getCurrentJurisdiction() {
        return CURRENT_JURISDICTION.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
        CURRENT_JURISDICTION.remove();
    }
}
