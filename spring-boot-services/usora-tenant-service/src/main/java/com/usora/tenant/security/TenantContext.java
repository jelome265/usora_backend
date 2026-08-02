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

    /** Binds context for the duration of action, then always clears it (even on exception). */
    public static void runWith(TenantContext context, Runnable action) {
        TenantContext previous = CONTEXT_HOLDER.get();
        CONTEXT_HOLDER.set(context);
        try {
            action.run();
        } finally {
            if (previous != null) {
                CONTEXT_HOLDER.set(previous);
            } else {
                CONTEXT_HOLDER.remove();
            }
        }
    }

    public static <T> T callWith(TenantContext context, java.util.concurrent.Callable<T> action) throws Exception {
        TenantContext previous = CONTEXT_HOLDER.get();
        CONTEXT_HOLDER.set(context);
        try {
            return action.call();
        } finally {
            if (previous != null) {
                CONTEXT_HOLDER.set(previous);
            } else {
                CONTEXT_HOLDER.remove();
            }
        }
    }

    /** Sets context for the current thread directly (used by request filters). Caller must clear it when done. */
    public static void set(TenantContext context) {
        CONTEXT_HOLDER.set(context);
    }

    /** Clears any context bound to the current thread. Must be called at the end of request processing to avoid leaking context across pooled threads. */
    public static void clear() {
        CONTEXT_HOLDER.remove();
    }

    public static TenantContext get() {
        return CONTEXT_HOLDER.get();
    }

    public static boolean isBound() {
        return CONTEXT_HOLDER.get() != null;
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
