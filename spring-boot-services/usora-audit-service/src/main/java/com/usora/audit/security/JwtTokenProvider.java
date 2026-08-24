package com.usora.audit.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

@Component
public class JwtTokenProvider {

    @Value("${audit.security.jwt.secret:}")
    private String jwtSecret;

    @Value("${audit.security.jwt.issuer:usora-audit}")
    private String jwtIssuer;

    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        // C-02 remediation: no development fallback secret. A missing
        // configuration value must fail application startup.
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "audit.security.jwt.secret is not configured. Refusing to start with no signing key. " +
                    "Set the JWT_SECRET environment variable to a securely generated secret.");
        }
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, Math.min(keyBytes.length, 32));
            keyBytes = padded;
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public SecretKey getSigningKey() {
        return secretKey;
    }

    public String getIssuer() {
        return jwtIssuer;
    }

    public Claims validateToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(jwtIssuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String getTenantIdFromToken(String token) {
        Claims claims = validateToken(token);
        return claims.get("tenant_id", String.class);
    }

    public List<String> getPermissionsFromToken(String token) {
        Claims claims = validateToken(token);
        @SuppressWarnings("unchecked")
        List<String> permissions = claims.get("permissions", List.class);
        return permissions;
    }
}
