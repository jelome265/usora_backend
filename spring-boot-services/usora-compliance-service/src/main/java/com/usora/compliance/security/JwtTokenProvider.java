package com.usora.compliance.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * F-011: previously verified tokens with its own HMAC secret
 * (compliance.security.jwt-secret), entirely distinct from
 * usora-identity-service's actual RS256/JWKS-issued tokens -- see the
 * migration note on SecurityConfig for the request-authentication half of
 * this fix (removal of the redundant JwtTokenFilter/HMAC-based primary
 * auth path).
 *
 * This class itself is NOT deleted the way notification-service's and
 * audit-service's equivalents were: DomainService's dual-authorization
 * approval flow (validateDualAuthorization/extractPrincipal) needs to
 * independently signature-verify two *additional* bearer tokens supplied
 * as business data (an officer's and a legal approver's own tokens),
 * separate from the calling principal's own request-level
 * authentication. That verification now delegates to the same
 * JWKS-backed JwtDecoder used for request authentication (see
 * SecurityConfig), rather than its own separate, independent HMAC key --
 * closing the same "parallel credential system" gap for this second use
 * of JWT verification in this service, not just the request-auth path.
 */
@Component
public class JwtTokenProvider {

    private final JwtDecoder jwtDecoder;

    public JwtTokenProvider(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    /**
     * Signature-verified claim extraction for callers that need to make an
     * authorization decision based on the token's content (subject, roles),
     * not just "is this a syntactically valid JWT". Returns empty on any
     * validation failure (bad signature, expired, malformed) — callers MUST
     * treat an empty result as "not authorized", never as "skip the check".
     */
    public Optional<Jwt> parseVerifiedClaims(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(jwtDecoder.decode(token));
        } catch (JwtException e) {
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
                .map(jwt -> {
                    var roles = jwt.getClaimAsStringList("roles");
                    return roles != null && roles.stream()
                            .anyMatch(r -> requiredRole.equalsIgnoreCase(r));
                })
                .orElse(false);
    }
}
