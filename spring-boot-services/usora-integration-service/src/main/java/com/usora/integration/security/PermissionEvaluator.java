package com.usora.integration.security;

import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.io.Serializable;

@Component
public class IntegrationPermissionEvaluator implements PermissionEvaluator {

    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        String targetType = targetDomainObject != null ? targetDomainObject.toString() : "";
        String permissionStr = permission != null ? permission.toString() : "";

        return authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("SCOPE_" + targetType + ":" + permissionStr));
    }

    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType, Object permission) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        String permissionStr = permission != null ? permission.toString() : "";

        return authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("SCOPE_" + targetType + ":" + permissionStr));
    }

    public boolean hasTenantAccess(Authentication authentication, String tenantId) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        if (authentication.getPrincipal() instanceof JwtTokenProvider.UsoraPrincipal principal) {
            return principal.tenantId() == null || principal.tenantId().equals(tenantId);
        }

        return authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("SCOPE_integration:admin"));
    }
}
