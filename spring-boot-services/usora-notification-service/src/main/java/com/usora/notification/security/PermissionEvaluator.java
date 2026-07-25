package com.usora.notification.security;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PermissionEvaluator {

    private static final Set<String> VALID_PERMISSIONS = ConcurrentHashMap.newKeySet();

    static {
        VALID_PERMISSIONS.add("notification:send");
        VALID_PERMISSIONS.add("notification:read");
        VALID_PERMISSIONS.add("notification:acknowledge");
        VALID_PERMISSIONS.add("notification:admin");
    }

    public boolean hasPermission(String requiredPermission) {
        if (!VALID_PERMISSIONS.contains(requiredPermission)) {
            return false;
        }
        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        return authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(requiredPermission)
                        || a.getAuthority().equals("notification:admin")
                        || a.getAuthority().equals("ROLE_ADMIN"));
    }

    public boolean hasAnyPermission(String... permissions) {
        for (var permission : permissions) {
            if (hasPermission(permission)) {
                return true;
            }
        }
        return false;
    }
}
