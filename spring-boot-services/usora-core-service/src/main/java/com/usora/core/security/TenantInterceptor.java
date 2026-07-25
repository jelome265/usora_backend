package com.usora.core.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class TenantInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TenantInterceptor.class);

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        var tenantId = extractTenantId(request);
        if (tenantId != null) {
            TenantContext.setCurrentTenantId(tenantId);
            MDC.put("tenantId", tenantId);
            log.debug("Tenant context set: {}", tenantId);
        }
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler,
                                Exception ex) {
        TenantContext.clear();
        MDC.remove("tenantId");
    }

    private String extractTenantId(HttpServletRequest request) {
        var headerTenant = request.getHeader("X-Tenant-Id");
        if (headerTenant != null && !headerTenant.isBlank()) {
            return headerTenant;
        }

        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getClaimAsString("tenant_id");
        }

        return null;
    }
}
