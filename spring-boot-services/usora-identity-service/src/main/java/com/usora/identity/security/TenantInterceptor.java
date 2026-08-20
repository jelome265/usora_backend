package com.usora.identity.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

@Slf4j
@Component
public class TenantInterceptor implements HandlerInterceptor {

    /**
     * SECURITY BUG, found and fixed while implementing Postgres row-level
     * security for this service: this interceptor previously trusted the
     * client-supplied X-Tenant-Id header — or even a plain ?tenant_id=
     * query parameter — UNCONDITIONALLY, with no JWT check at all. Any
     * caller could set either to any value and operate under a different
     * tenant's context for the rest of the request, including on this
     * service itself (the platform's root of trust for authentication).
     * Tenant now comes exclusively from the verified JWT's "tid" claim —
     * see DomainService.java's token-issuance code for why "tid" and not
     * "tenant_id" is the correct claim name.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            var tenantId = jwt.getClaimAsString("tid");
            var context = TenantContext.getContext();
            if (tenantId != null && !tenantId.isBlank()) {
                context.setTenantId(tenantId);
                log.debug("Tenant context set from verified JWT: {}", tenantId);
            }
        }

        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           @Nullable ModelAndView modelAndView) {
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                @Nullable Exception ex) {
        TenantContext.clear();
    }
}
