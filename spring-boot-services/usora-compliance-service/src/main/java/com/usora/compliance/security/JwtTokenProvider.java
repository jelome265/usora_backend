package com.usora.compliance.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

@Component
public class JwtTokenProvider {

    private final SecretKey secretKey;

    public JwtTokenProvider(@Value("${compliance.security.jwt-secret:}") String secret) {
        // C-02 remediation: no development fallback secret. A missing
        // configuration value must fail application startup.
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "compliance.security.jwt-secret is not configured. Refusing to start with no signing key. " +
                    "Set the COMPLIANCE_JWT_SECRET environment variable to a securely generated secret.");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Signature-verified claim extraction for callers that need to make an
     * authorization decision based on the token's content (subject, roles),
     * not just "is this a syntactically valid JWT". Returns empty on any
     * validation failure (bad signature, expired, malformed) — callers MUST
     * treat an empty result as "not authorized", never as "skip the check".
     */
    public Optional<Claims> parseVerifiedClaims(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Convenience check: does a signature-verified token carry the given
     * role in its `roles` claim? Used for endpoints that require a specific
     * role beyond "any authenticated caller" (e.g. dual-authorization
     * approvals).
     */
    public boolean hasVerifiedRole(String token, String requiredRole) {
        return parseVerifiedClaims(token)
                .map(claims -> {
                    var roles = claims.get("roles", List.class);
                    return roles != null && roles.stream()
                            .anyMatch(r -> requiredRole.equalsIgnoreCase(String.valueOf(r)));
                })
                .orElse(false);
    }

    public Authentication getAuthentication(String token) {
        try {
            var claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            var userId = claims.getSubject();
            var roles = claims.get("roles", List.class);
            var tenantId = claims.get("tenantId", String.class);

            if (userId == null) return null;

            if (tenantId != null) {
                TenantContext.setCurrentTenant(tenantId);
            }

            var authorities = roles != null
                    ? roles.stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r.toString())).toList()
                    : List.<SimpleGrantedAuthority>of();

            return new UsernamePasswordAuthenticationToken(userId, token, authorities);
        } catch (Exception e) {
            return null;
        }
    }
}
