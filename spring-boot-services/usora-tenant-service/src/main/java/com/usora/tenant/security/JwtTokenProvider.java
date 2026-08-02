package com.usora.tenant.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SecurityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Map<String, PublicKey> jwksCache = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${jwt.jwks-url}")
    private String jwksUrl;

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (SecurityException | MalformedJwtException | ExpiredJwtException |
                 UnsupportedJwtException | IllegalArgumentException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    public Authentication getAuthentication(String token) {
        Claims claims = parseClaims(token);
        String subject = claims.getSubject();
        String tenantId = claims.get("tid", String.class);
        List<String> roles = claims.get("roles", List.class);

        List<SimpleGrantedAuthority> authorities = roles != null
                ? roles.stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList()
                : Collections.emptyList();

        TenantContext context = TenantContext.builder()
                .userId(subject)
                .currentTenantId(tenantId != null ? UUID.fromString(tenantId) : null)
                .roles(roles != null ? roles : Collections.emptyList())
                .build();

        TenantContext.set(context);

        return new UsernamePasswordAuthenticationToken(subject, token, authorities);
    }

    public String extractTenantId(String token) {
        Claims claims = parseClaims(token);
        return claims.get("tid", String.class);
    }

    public String extractUserId(String token) {
        Claims claims = parseClaims(token);
        return claims.getSubject();
    }

    public List<String> extractRoles(String token) {
        Claims claims = parseClaims(token);
        List<String> roles = claims.get("roles", List.class);
        return roles != null ? roles : Collections.emptyList();
    }

    private Claims parseClaims(String token) {
        String kid = extractKidFromHeader(token);
        PublicKey publicKey = resolvePublicKey(kid);
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private String extractKidFromHeader(String token) {
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            throw new MalformedJwtException("Token does not contain a valid header/payload structure");
        }
        try {
            byte[] headerBytes = Base64.getUrlDecoder().decode(parts[0]);
            Map<String, Object> header = OBJECT_MAPPER.readValue(headerBytes, Map.class);
            Object kid = header.get("kid");
            if (kid == null) {
                throw new MalformedJwtException("Token header does not contain a 'kid' claim");
            }
            return kid.toString();
        } catch (java.io.IOException e) {
            throw new MalformedJwtException("Token header is not valid JSON", e);
        }
    }

    private PublicKey resolvePublicKey(String kid) {
        return jwksCache.computeIfAbsent(kid, this::fetchPublicKey);
    }

    private PublicKey fetchPublicKey(String kid) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(jwksUrl))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            Map<String, Object> jwks = OBJECT_MAPPER.readValue(response.body(), Map.class);
            List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");
            if (keys == null) {
                throw new IllegalStateException("JWKS response did not contain a 'keys' array");
            }

            Map<String, Object> matchingKey = keys.stream()
                    .filter(k -> kid.equals(k.get("kid")))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No JWKS key found matching kid: " + kid));

            @SuppressWarnings("unchecked")
            List<String> x5c = (List<String>) matchingKey.get("x5c");
            if (x5c == null || x5c.isEmpty()) {
                throw new IllegalStateException("JWKS key for kid " + kid + " has no x5c certificate");
            }

            byte[] keyBytes = Base64.getDecoder().decode(x5c.get(0));
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePublic(spec);
        } catch (Exception e) {
            log.error("Failed to fetch JWKS key for kid {}: {}", kid, e.getMessage());
            throw new RuntimeException("Failed to resolve JWT signing key", e);
        }
    }
}
