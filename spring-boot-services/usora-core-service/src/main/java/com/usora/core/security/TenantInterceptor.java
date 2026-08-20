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

    /**
     * SECURITY BUG, found and fixed while implementing Postgres row-level
     * security (RLS) for this service: this interceptor previously read
     * the client-supplied X-Tenant-Id header UNCONDITIONALLY OVER the
     * verified JWT claim — any authenticated caller could set that header
     * to any value and operate under a different tenant's context for
     * the rest of the request. It also read a claim named "tenant_id",
     * which usora-identity-service never actually issues (the real claim
     * is "tid" — see DomainService.java's token-issuance code there) —
     * so even the JWT path, when it did run, silently returned null and
     * fell through to the spoofable header anyway.
     *
     * The tenant for a request now comes EXCLUSIVELY from the verified
     * JWT's "tid" claim. A request with no valid JWT has no tenant
     * context at all, full stop — there is no header fallback of any
     * kind. Anything downstream (including the RLS session-variable hook
     * in TenantAwareDataSourceInterceptor) depends on this being correct;
     * this is the single point where a spoofed tenant would have defeated
     * database-level row isolation too, no matter how carefully that was
     * implemented.
     */
    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            var tenantId = jwt.getClaimAsString("tid");
            if (tenantId != null && !tenantId.isBlank()) {
                TenantContext.setCurrentTenantId(tenantId);
                MDC.put("tenantId", tenantId);
                log.debug("Tenant context set from verified JWT: {}", tenantId);
            }
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
}
