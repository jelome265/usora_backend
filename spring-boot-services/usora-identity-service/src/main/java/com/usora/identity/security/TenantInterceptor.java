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
     *
     * F-002 follow-up: the JWT-only fix above closed the header-spoofing
     * hole, but left a fail-open gap identical to the one fixed in
     * core/audit/compliance-service -- an authenticated JWT with a
     * missing/blank "tid" claim fell through with no tenant context set,
     * and this still returned true, letting the request proceed.
     *
     * This service is different from the other three: WebMvcConfig
     * registers this interceptor on "/**" minus "/health" and
     * "/actuator/**", which also covers every OAuth2 authorization-server
     * and OIDC endpoint this service exposes (see SecurityConfig):
     * /oauth2/authorize, /oauth2/token, /oauth2/introspect,
     * /oauth2/revoke, /oauth2/jwks, /oidc/userinfo, /oidc/register, the
     * /login entry point, and /.well-known/** discovery metadata. Those
     * are how a caller obtains a JWT (or introspects/revokes/discovers
     * one) in the first place -- by definition pre-tenant-context, and
     * some (JWKS, discovery, client-credentials token requests) are never
     * expected to carry a "tid" at all. Copying the plain "reject if no
     * tenant" rule from the other services here would break token
     * issuance itself, so those paths are explicitly exempted below;
     * every other path now fails closed with 403 when the authenticated
     * JWT has no tenant claim, matching the other three services.
     */
    private static final String[] TENANT_EXEMPT_PREFIXES = {
            "/oauth2/", "/oidc/", "/.well-known/", "/login"
    };

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws java.io.IOException {
        String path = request.getRequestURI();
        for (String prefix : TENANT_EXEMPT_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }

        var auth = SecurityContextHolder.getContext().getAuthentication();
        String tenantId = null;
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            tenantId = jwt.getClaimAsString("tid");
        }

        if (tenantId == null || tenantId.isBlank()) {
            log.warn("Rejecting request to {} {}: authenticated principal has no tenant (tid) claim",
                    request.getMethod(), path);
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Tenant identity required");
            return false;
        }

        TenantContext.getContext().setTenantId(tenantId);
        log.debug("Tenant context set from verified JWT: {}", tenantId);
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
