package com.usora.notification.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

@Component
public class JwtTokenProvider {

    private final SecretKey secretKey;

    public JwtTokenProvider(@Value("${security.jwt.secret:}") String secret) {
        // C-02 remediation: there is no development fallback secret. A missing
        // configuration value must fail application startup, not silently sign
        // tokens with a well-known key that anyone can read from this file.
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "security.jwt.secret is not configured. Refusing to start with no signing key. " +
                    "Set the JWT_SECRET environment variable to a securely generated secret.");
        }
        var keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            var padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, Math.min(keyBytes.length, 32));
            this.secretKey = Keys.hmacShaKeyFor(padded);
        } else {
            this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        }
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Authentication getAuthentication(String token) {
        var claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        var userId = claims.getSubject();
        var tenantId = claims.get("tenantId", String.class);
        var roles = claims.get("roles", List.class);

        if (tenantId != null) {
            TenantContext.setCurrentTenantId(tenantId);
        }

        var authorities = roles != null
                ? ((List<String>) roles).stream().map(SimpleGrantedAuthority::new).toList()
                : List.<SimpleGrantedAuthority>of();

        return new UsernamePasswordAuthenticationToken(userId, token, authorities);
    }
}
