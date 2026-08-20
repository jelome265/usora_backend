package com.usora.compliance.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

public class TenantInterceptor implements HandlerInterceptor {

    /**
     * SECURITY BUG, found and fixed while implementing Postgres row-level
     * security for this service: this interceptor previously trusted the
     * client-supplied X-Tenant-Id header with NO JWT check at all —
     * meaning every request's tenant context, feeding directly into
     * every tenant-scoped query fixed in DomainService.java (see findings
     * C4/C7), was fully attacker-controlled by design, not just as a
     * fallback. Tenant now comes exclusively from the verified JWT's
     * "tid" claim.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            var tenantId = jwt.getClaimAsString("tid");
            if (tenantId != null && !tenantId.isBlank()) {
                TenantContext.setCurrentTenant(tenantId);
            }
        }

        var jurisdiction = request.getHeader("X-Jurisdiction");
        if (jurisdiction != null && !jurisdiction.isBlank()) {
            TenantContext.setCurrentJurisdiction(jurisdiction);
        }

        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
        // no-op
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContext.clear();
    }
}
