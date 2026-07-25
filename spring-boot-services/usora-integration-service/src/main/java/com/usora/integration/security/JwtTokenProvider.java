package com.usora.integration.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    @Value("${security.jwt.secret:defaultSecretKeyThatIsLongEnoughForHS256AlgorithmMinimumLength}")
    private String jwtSecret;

    @Value("${security.jwt.issuer:usora-integration-service}")
    private String issuer;

    private SecretKey key;

    @PostConstruct
    public void init() {
        byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);
        this.key = Keys.hmacShaKeyFor(keyBytes);
    }

    public Authentication getAuthentication(String token) {
        Claims claims = parseToken(token);
        if (claims == null) {
            return null;
        }

        String subject = claims.getSubject();
        String tenantId = claims.get("tenantId", String.class);
        List<String> roles = claims.get("roles", List.class);

        Collection<GrantedAuthority> authorities = roles != null
                ? roles.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList())
                : List.of();

        UsoraPrincipal principal = new UsoraPrincipal(subject, tenantId, claims);
        return new UsernamePasswordAuthenticationToken(principal, token, authorities);
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    public String getTenantId(String token) {
        Claims claims = parseToken(token);
        return claims != null ? claims.get("tenantId", String.class) : null;
    }

    public String getUserId(String token) {
        Claims claims = parseToken(token);
        return claims != null ? claims.getSubject() : null;
    }

    private Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public record UsoraPrincipal(String userId, String tenantId, Claims claims) {
        public String getName() {
            return userId;
        }
    }
}
