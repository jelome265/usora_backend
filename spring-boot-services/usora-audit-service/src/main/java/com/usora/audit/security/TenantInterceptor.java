package com.usora.audit.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class TenantInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        TenantContext.clear();

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Jwt jwt) {

            String tenantId = jwt.getClaimAsString("tenant_id");
            if (tenantId != null) {
                TenantContext.setTenantId(tenantId);
            }

            String userId = jwt.getClaimAsString("sub");
            if (userId != null) {
                TenantContext.setUserId(userId);
            }
        }

        String headerTenantId = request.getHeader("X-Tenant-ID");
        if (headerTenantId != null) {
            TenantContext.setTenantId(headerTenantId);
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContext.clear();
    }
}
