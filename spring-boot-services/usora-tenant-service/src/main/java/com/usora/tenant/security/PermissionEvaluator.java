package com.usora.tenant.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.UUID;

@Component
public class PermissionEvaluator implements org.springframework.security.access.PermissionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(PermissionEvaluator.class);

    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        TenantContext context = TenantContext.get();
        if (context == null) {
            return false;
        }

        String permissionStr = permission.toString();
        String targetType = targetDomainObject != null ? targetDomainObject.getClass().getSimpleName() : "";

        if ("TENANT".equals(targetType) && targetDomainObject instanceof String tenantId) {
            return hasTenantPermission(context, UUID.fromString(tenantId), permissionStr);
        }

        return context.getRoles().stream()
                .anyMatch(role -> role.equals("PLATFORM_ADMIN") || role.equals("TENANT_" + permissionStr));
    }

    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType, Object permission) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        TenantContext context = TenantContext.get();
        if (context == null) {
            return false;
        }

        if ("TENANT".equals(targetType) && targetId instanceof String tenantId) {
            return hasTenantPermission(context, UUID.fromString(tenantId), permission.toString());
        }

        return context.getRoles().contains("PLATFORM_ADMIN");
    }

    private boolean hasTenantPermission(TenantContext context, UUID tenantId, String permission) {
        if (context.getRoles().contains("PLATFORM_ADMIN")) {
            return true;
        }

        boolean isTenantAdmin = context.getRoles().contains("TENANT_ADMIN");
        boolean ownsTenant = tenantId.equals(context.getCurrentTenantId());

        if (isTenantAdmin && ownsTenant) {
            return switch (permission) {
                case "READ", "UPDATE" -> true;
                case "DELETE", "SUSPEND", "RESUME", "CONFIG" -> false;
                default -> false;
            };
        }

        return false;
    }
}
