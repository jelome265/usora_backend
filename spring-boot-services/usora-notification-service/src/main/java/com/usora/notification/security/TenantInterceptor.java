package com.usora.notification.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * F-011: previously a near-total no-op -- tenant context used to be set
 * by JwtTokenProvider.getAuthentication() during the (HMAC-based)
 * authentication step itself, before this interceptor ever ran, so this
 * class's only real job was to avoid overwriting that with an unverified
 * X-Tenant-Id header (see git history for that fix). Now that
 * authentication goes through Spring's standard oauth2ResourceServer/JWKS
 * flow (see SecurityConfig), the authenticated principal is a plain
 * org.springframework.security.oauth2.jwt.Jwt with no notification-
 * service-specific side effect that sets TenantContext -- so this
 * interceptor now does that job directly, the same fail-closed pattern
 * already used in core/audit/compliance/identity-service's
 * TenantInterceptors: an authenticated request with no "tid" claim is
 * rejected outright rather than allowed to proceed with no tenant
 * context, and only the JWT's own verified claim is trusted, never a
 * client-supplied header.
 */
@Slf4j
@Component
public class TenantInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws java.io.IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String tenantId = null;
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            tenantId = jwt.getClaimAsString("tid");
        }

        if (tenantId == null || tenantId.isBlank()) {
            log.warn("Rejecting request to {} {}: authenticated principal has no tenant (tid) claim",
                    request.getMethod(), request.getRequestURI());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Tenant identity required");
            return false;
        }

        TenantContext.setCurrentTenantId(tenantId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear();
    }
}
