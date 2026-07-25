package com.usora.tenant.security;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Builder
public class TenantContext {

    private static final ScopedValue<TenantContext> CONTEXT_HOLDER = ScopedValue.newInstance();

    private String userId;
    private UUID currentTenantId;
    private List<String> roles;

    public static void runWith(TenantContext context, Runnable action) {
        ScopedValue.where(CONTEXT_HOLDER, context).run(action);
    }

    public static <T> T callWith(TenantContext context, java.util.concurrent.Callable<T> action) throws Exception {
        return ScopedValue.where(CONTEXT_HOLDER, context).call(action);
    }

    public static TenantContext get() {
        return CONTEXT_HOLDER.get();
    }

    public static boolean isBound() {
        return CONTEXT_HOLDER.isBound();
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
