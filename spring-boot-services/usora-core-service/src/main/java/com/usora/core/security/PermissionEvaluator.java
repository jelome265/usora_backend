package com.usora.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PermissionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(PermissionEvaluator.class);

    public boolean hasPermission(Authentication authentication, String requiredPermission) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        if (authentication.getPrincipal() instanceof Jwt jwt) {
            var roles = jwt.getClaimAsStringList("roles");
            if (roles != null && roles.contains(requiredPermission)) {
                return true;
            }
        }

        var authorities = authentication.getAuthorities();
        return authorities.stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + requiredPermission)
                        || a.getAuthority().equals("SCOPE_" + requiredPermission));
    }

    public boolean hasAnyPermission(Authentication authentication, List<String> permissions) {
        return permissions.stream().anyMatch(p -> hasPermission(authentication, p));
    }

    public boolean hasTenantAccess(Authentication authentication, String tenantId) {
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            var tokenTenant = jwt.getClaimAsString("tenant_id");
            return tenantId.equals(tokenTenant);
        }
        return false;
    }
}
