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
     *
     * F-002 follow-up: previously an authenticated JWT lacking "tid" fell
     * through with no tenant context set and the request proceeded
     * regardless. Unlike core/audit-service, this interceptor is
     * registered with no path restriction (see TenantConfig#addInterceptors),
     * so it also sees /actuator/health, /actuator/health/**, and
     * /actuator/info -- the three paths SecurityConfig permits without
     * authentication at all -- and those must stay exempt from a tenant
     * requirement. Every other path requires an authenticated principal
     * (SecurityConfig#anyRequest().authenticated()), so a missing tenant
     * claim there now fails closed with 403 instead of silently
     * continuing without tenant context.
     */
    private static final java.util.Set<String> TENANT_EXEMPT_PATHS = java.util.Set.of(
            "/actuator/health", "/actuator/info");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws java.io.IOException {
        String path = request.getRequestURI();
        boolean exempt = TENANT_EXEMPT_PATHS.contains(path) || path.startsWith("/actuator/health/");

        var auth = SecurityContextHolder.getContext().getAuthentication();
        String tenantId = null;
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            tenantId = jwt.getClaimAsString("tid");
        }

        if (!exempt && (tenantId == null || tenantId.isBlank())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Tenant identity required");
            return false;
        }

        if (tenantId != null && !tenantId.isBlank()) {
            TenantContext.setCurrentTenant(tenantId);
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
