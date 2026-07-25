package com.usora.identity.security;

import java.util.HashMap;
import java.util.Map;

public final class TenantContext {

    private static final ThreadLocal<TenantContext> CONTEXT_HOLDER = ThreadLocal.withInitial(TenantContext::new);

    private String tenantId;
    private String clientId;
    private String userId;
    private final Map<String, Object> attributes = new HashMap<>();

    private TenantContext() {}

    public static TenantContext getContext() {
        return CONTEXT_HOLDER.get();
    }

    public static void setContext(TenantContext context) {
        CONTEXT_HOLDER.set(context);
    }

    public static void clear() {
        CONTEXT_HOLDER.remove();
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    public Map<String, Object> getAttributes() {
        return Map.copyOf(attributes);
    }
}
