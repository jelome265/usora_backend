package com.usora.audit.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component("permissionEvaluator")
public class PermissionEvaluator {

    public boolean hasPermission(Authentication authentication, String permission) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        return authorities.contains("SCOPE_" + permission) || authorities.contains("SCOPE_audit:admin");
    }

    public boolean hasAuditRead(Authentication authentication) {
        return hasPermission(authentication, "audit:read");
    }

    public boolean hasAuditWrite(Authentication authentication) {
        return hasPermission(authentication, "audit:write");
    }

    public boolean hasAuditAdmin(Authentication authentication) {
        return hasPermission(authentication, "audit:admin");
    }

    public boolean hasTenantAccess(Authentication authentication, String tenantId) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (hasAuditAdmin(authentication)) {
            return true;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("SCOPE_tenant:" + tenantId));
    }
}
