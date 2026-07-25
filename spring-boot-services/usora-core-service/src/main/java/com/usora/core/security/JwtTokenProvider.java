package com.usora.core.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class JwtTokenProvider {

    private final JwtEncoder jwtEncoder;

    public JwtTokenProvider(JwtEncoder jwtEncoder) {
        this.jwtEncoder = jwtEncoder;
    }

    public String createToken(String subject, String tenantId, List<String> roles) {
        var claims = JwtClaimsSet.builder()
                .subject(subject)
                .issuer("usora-core")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("tenant_id", tenantId)
                .claim("roles", roles)
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    public String extractTenantId(Jwt jwt) {
        return jwt.getClaimAsString("tenant_id");
    }

    public List<String> extractRoles(Jwt jwt) {
        return jwt.getClaimAsStringList("roles");
    }
}
