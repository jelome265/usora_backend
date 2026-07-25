package com.usora.tenant.security;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Builder
public class TenantContext {

    private static final ThreadLocal<TenantContext> CONTEXT_HOLDER = new ThreadLocal<>();

    private String userId;
    private UUID currentTenantId;
    private List<String> roles;

    public static void set(TenantContext context) {
        CONTEXT_HOLDER.set(context);
    }

    public static TenantContext get() {
        return CONTEXT_HOLDER.get();
    }

    public static void clear() {
        CONTEXT_HOLDER.remove();
    }

    public static String getCurrentUserId() {
        TenantContext context = get();
        return context != null ? context.getUserId() : null;
    }

    public static UUID getCurrentTenantId() {
        TenantContext context = get();
        return context != null ? context.getCurrentTenantId() : null;
    }

    public static boolean hasRole(String role) {
        TenantContext context = get();
        return context != null && context.getRoles() != null && context.getRoles().contains(role);
    }

    public static boolean isPlatformAdmin() {
        return hasRole("PLATFORM_ADMIN");
    }
}
