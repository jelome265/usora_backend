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

    /**
     * SECURITY BUG, found and fixed while implementing Postgres row-level
     * security for this service: this interceptor DID read the tenant
     * from the verified JWT first — but then unconditionally overwrote it
     * with the client-supplied X-Tenant-ID header immediately afterward
     * if that header was present at all, silently discarding the
     * verified value. It also read a claim named "tenant_id", which
     * usora-identity-service never actually issues (the real claim is
     * "tid") — so the JWT path was already a no-op in practice even
     * before the header overwrite. Tenant now comes exclusively from the
     * verified JWT's "tid" claim, full stop.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        TenantContext.clear();

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Jwt jwt) {

            String tenantId = jwt.getClaimAsString("tid");
            if (tenantId != null) {
                TenantContext.setTenantId(tenantId);
            }

            String userId = jwt.getClaimAsString("sub");
            if (userId != null) {
                TenantContext.setUserId(userId);
            }
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContext.clear();
    }
}
