package com.usora.integration.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * SECURITY BUG, found and fixed while implementing Postgres row-level
 * security for this service: this filter previously trusted the
 * client-supplied X-Tenant-Id header, falling back to parsing a tenant
 * id out of the URL path (e.g. /webhooks/{tenantId}/...) — both fully
 * attacker-controlled, with no reference to the verified JWT at all,
 * even though JwtTokenProvider.getAuthentication already extracts a
 * verified tenantId into UsoraPrincipal during authentication. Tenant
 * now comes exclusively from that verified principal.
 *
 * SEPARATE FINDING, flagged but NOT resolved here: like
 * usora-notification-service, this service verifies tokens with an HMAC
 * secret entirely distinct from usora-identity-service's actual
 * RS256/JWKS-issued tokens — see JwtTokenProvider.java. Whether that's
 * an intentional internal-service auth pattern or a genuine integration
 * gap needs a decision from whoever owns this service's calling pattern.
 */
@Component
@Order(2)
public class TenantInterceptor implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof JwtTokenProvider.UsoraPrincipal principal) {
                if (principal.tenantId() != null && !principal.tenantId().isBlank()) {
                    TenantContext.setCurrentTenant(principal.tenantId());
                }
                if (principal.userId() != null && !principal.userId().isBlank()) {
                    TenantContext.setCurrentUserId(principal.userId());
                }
            }

            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
