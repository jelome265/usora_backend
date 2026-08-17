package com.usora.identity.security;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.usora.identity.config.TenantConfig;
import com.usora.identity.entity.TenantEntity;
import com.usora.identity.repository.TenantRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class JwtTokenProvider {

    private final TenantConfig tenantConfig;
    private final TenantRepository tenantRepository;

    private final Map<String, KeyPair> tenantKeyPairs = new ConcurrentHashMap<>();
    private final Map<String, RSAKey> tenantJWKs = new ConcurrentHashMap<>();
    private final Map<String, JWKSource<SecurityContext>> tenantJWKSources = new ConcurrentHashMap<>();
    private KeyPair defaultKeyPair;
    private RSAKey defaultJWK;
    private JWKSet defaultJWKSet;

    public JwtTokenProvider(TenantConfig tenantConfig, TenantRepository tenantRepository) {
        this.tenantConfig = tenantConfig;
        this.tenantRepository = tenantRepository;
    }

    @PostConstruct
    public void init() {
        try {
            generateDefaultKey();
            log.info("Initialized JWT token provider with default RS256 key pair");
        } catch (Exception e) {
            log.error("Failed to initialize JWT token provider", e);
            throw new RuntimeException("Failed to initialize JWT token provider", e);
        }
    }

    private void generateDefaultKey() throws Exception {
        var keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(tenantConfig.getJwt().getKeySize());
        defaultKeyPair = keyGen.generateKeyPair();

        var publicKey = (RSAPublicKey) defaultKeyPair.getPublic();
        var privateKey = (RSAPrivateKey) defaultKeyPair.getPrivate();

        defaultJWK = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(UUID.randomUUID().toString())
                .algorithm(JWSAlgorithm.RS256)
                .issueTime(new Date())
                .build();

        defaultJWKSet = new JWKSet(defaultJWK);
    }

    public RSAKey getKeyForTenant(String tenantId) {
        if (tenantId != null && tenantJWKs.containsKey(tenantId)) {
            return tenantJWKs.get(tenantId);
        }

        if (tenantId != null) {
            try {
                var tenantOpt = tenantRepository.findById(UUID.fromString(tenantId));
                if (tenantOpt.isPresent()) {
                    var tenant = tenantOpt.get();
                    if (tenant.getPublicKey() != null && tenant.getPrivateKeyEncrypted() != null) {
                        var keyPair = loadTenantKeyPair(tenant);
                        tenantKeyPairs.put(tenantId, keyPair);
                        var rsaKey = buildRSAKey(keyPair, tenant.getKeyId());
                        tenantJWKs.put(tenantId, rsaKey);
                        return rsaKey;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to load key for tenant {}, using default", tenantId, e);
            }
        }

        return defaultJWK;
    }

    private KeyPair loadTenantKeyPair(TenantEntity tenant) {
        try {
            var keyFactory = java.security.KeyFactory.getInstance("RSA");
            var pubKeyBytes = Base64.getDecoder().decode(tenant.getPublicKey());
            var privKeyBytes = Base64.getDecoder().decode(tenant.getPrivateKeyEncrypted());

            var pubKeySpec = new java.security.spec.X509EncodedKeySpec(pubKeyBytes);
            var privKeySpec = new java.security.spec.PKCS8EncodedKeySpec(privKeyBytes);

            var publicKey = keyFactory.generatePublic(pubKeySpec);
            var privateKey = keyFactory.generatePrivate(privKeySpec);

            return new KeyPair(publicKey, privateKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load tenant key pair", e);
        }
    }

    private RSAKey buildRSAKey(KeyPair keyPair, String keyId) {
        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(keyId != null ? keyId : UUID.randomUUID().toString())
                .algorithm(JWSAlgorithm.RS256)
                .issueTime(new Date())
                .build();
    }

    public String signToken(JWTClaimsSet claims, String tenantId) throws JOSEException {
        var rsaKey = getKeyForTenant(tenantId);
        var signer = new RSASSASigner(rsaKey);

        var jwsHeader = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(rsaKey.getKeyID())
                .type(JOSEObjectType.JWT)
                .build();

        var signedJWT = new SignedJWT(jwsHeader, claims);
        signedJWT.sign(signer);
        return signedJWT.serialize();
    }

    public boolean validateToken(String token, String tenantId) {
        try {
            var rsaKey = getKeyForTenant(tenantId);
            var verifier = new RSASSAVerifier(rsaKey);
            var signedJWT = SignedJWT.parse(token);
            return signedJWT.verify(verifier);
        } catch (Exception e) {
            log.warn("Token validation failed: {}", e.getMessage());
            return false;
        }
    }

    public JWTClaimsSet parseToken(String token) {
        try {
            var signedJWT = SignedJWT.parse(token);
            return signedJWT.getJWTClaimsSet();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse token", e);
        }
    }

    public JWKSource<SecurityContext> getJwkSource() {
        return (jwkSelector, context) -> {
            var jwkSet = getJwkSet();
            return jwkSelector.select(jwkSet);
        };
    }

    public JWKSet getJwkSet() {
        var keys = new ArrayList<com.nimbusds.jose.jwk.JWK>();
        keys.add(defaultJWK);
        for (var entry : tenantJWKs.entrySet()) {
            keys.add(entry.getValue());
        }
        return new JWKSet(keys);
    }

    public void rotateKeys() {
        try {
            generateDefaultKey();
            log.info("Default key pair rotated successfully");
        } catch (Exception e) {
            log.error("Failed to rotate keys", e);
        }
    }

    public void customizeToken(JwtEncodingContext context) {
        var claims = context.getClaims();
        var principal = context.getPrincipal();

        if (principal != null) {
            var authorities = principal.getAuthorities();
            if (authorities != null && !authorities.isEmpty()) {
                var roles = authorities.stream()
                        .filter(a -> a.getAuthority().startsWith("ROLE_"))
                        .map(a -> a.getAuthority().substring(5))
                        .toList();
                if (!roles.isEmpty()) {
                    claims.claim("roles", roles);
                }

                var permissions = authorities.stream()
                        .filter(a -> a.getAuthority().startsWith("PERMISSION_"))
                        .map(a -> a.getAuthority().substring(11))
                        .toList();
                if (!permissions.isEmpty()) {
                    claims.claim("permissions", permissions);
                }
            }
        }

        var tenantContext = TenantContext.getContext();
        if (tenantContext.getTenantId() != null) {
            claims.claim("tid", tenantContext.getTenantId());
        }
    }
}
