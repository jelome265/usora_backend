package com.usora.notification.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * SECURITY BUG, found and fixed while implementing Postgres row-level
 * security for this service: this interceptor previously read the
 * client-supplied X-Tenant-Id header FIRST, only falling back to
 * {@code auth.getDetails()} (which does not even hold a tenant claim in
 * this service's actual Authentication implementation — see
 * JwtTokenProvider.getAuthentication, which returns a
 * UsernamePasswordAuthenticationToken with no custom details object at
 * all) if the header was blank. In practice this meant tenant context
 * came from the header unconditionally whenever it was present.
 *
 * JwtTokenProvider.getAuthentication already sets TenantContext directly
 * from the verified token's claim during authentication (see that class)
 * — this interceptor's only remaining job is to NOT overwrite that with
 * an unverified header afterward, so it no longer touches tenant
 * resolution at all.
 *
 * SEPARATE FINDING, flagged but NOT resolved here: this service verifies
 * tokens with an HMAC secret (security.jwt.secret) entirely distinct
 * from usora-identity-service's actual RS256/JWKS-issued tokens — a real
 * token issued by identity-service cannot be validated here at all
 * (wrong algorithm, wrong key material). This may be an intentional
 * internal-service-to-service auth pattern (similar to what
 * usora-document-processor's REST API now uses), or it may be a genuine
 * integration gap where end-user-facing calls to this service's
 * protected endpoints can never actually succeed. Resolving which one is
 * true needs a decision from whoever owns this service's calling
 * pattern, not a guess made while fixing tenant-header spoofing.
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear();
    }
}
