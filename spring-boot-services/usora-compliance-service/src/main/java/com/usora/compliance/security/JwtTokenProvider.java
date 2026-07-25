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

@Component
public class JwtTokenProvider {

    private final SecretKey secretKey;

    public JwtTokenProvider(@Value("${compliance.security.jwt-secret:default-secret-key-change-in-production}") String secret) {
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
