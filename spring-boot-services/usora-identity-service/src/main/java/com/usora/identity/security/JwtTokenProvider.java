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
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.usora.identity.config.TenantConfig;
import com.usora.identity.entity.SystemSigningKey;
import com.usora.identity.entity.TenantEntity;
import com.usora.identity.repository.SystemSigningKeyRepository;
import com.usora.identity.repository.TenantRepository;
import com.usora.identity.util.EncryptionUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class JwtTokenProvider {

    private final TenantConfig tenantConfig;
    private final TenantRepository tenantRepository;
    private final SystemSigningKeyRepository systemSigningKeyRepository;
    private final byte[] encryptionKeyBytes;

    private final Map<String, KeyPair> tenantKeyPairs = new ConcurrentHashMap<>();
    private final Map<String, RSAKey> tenantJWKs = new ConcurrentHashMap<>();
    private final Map<String, JWKSource<SecurityContext>> tenantJWKSources = new ConcurrentHashMap<>();
    private KeyPair defaultKeyPair;
    private RSAKey defaultJWK;
    private JWKSet defaultJWKSet;

    public JwtTokenProvider(
            TenantConfig tenantConfig,
            TenantRepository tenantRepository,
            SystemSigningKeyRepository systemSigningKeyRepository,
            @Value("${identity.security.encryption-key:}") String encryptionKeyBase64) {
        this.tenantConfig = tenantConfig;
        this.tenantRepository = tenantRepository;
        this.systemSigningKeyRepository = systemSigningKeyRepository;
        // SECURITY: no insecure literal fallback — an empty/unset
        // encryption key fails startup below rather than silently storing
        // the default private key with a well-known or trivially-derived
        // key. See application.yml / this chart's values.yaml for
        // identity.security.encryption-key / IDENTITY_ENCRYPTION_KEY.
        this.encryptionKeyBytes = encryptionKeyBase64 == null || encryptionKeyBase64.isBlank()
                ? null
                : Base64.getDecoder().decode(encryptionKeyBase64);
    }

    @PostConstruct
    public void init() {
        try {
            if (encryptionKeyBytes == null) {
                throw new IllegalStateException(
                        "identity.security.encryption-key (IDENTITY_ENCRYPTION_KEY) must be set — " +
                        "it protects the persisted default JWT signing key at rest.");
            }
            loadOrGenerateDefaultKey();
            log.info("Initialized JWT token provider with default RS256 key pair (key id: {})",
                    defaultJWK.getKeyID());
        } catch (Exception e) {
            log.error("Failed to initialize JWT token provider", e);
            throw new RuntimeException("Failed to initialize JWT token provider", e);
        }
    }

    /**
     * PRE-EXISTING BUG, found and fixed while writing this service's Helm
     * chart: this method previously generated a brand-new RSA key pair on
     * every single application startup with no persistence anywhere (see
     * db/migration/V3__system_signing_keys.sql for the full finding). With
     * more than one replica — which any real deployment of this service
     * needs — every pod ended up signing tokens with a DIFFERENT key
     * simultaneously, so a token issued by one pod would fail verification
     * against another pod's JWKS. This now loads the existing persisted
     * key if one exists, and only generates + persists a new one the
     * first time this service is ever started against a fresh database.
     */
    private void loadOrGenerateDefaultKey() throws Exception {
        var existing = systemSigningKeyRepository.findByActiveTrue();
        if (existing.isPresent()) {
            var row = existing.get();
            var keyFactory = KeyFactory.getInstance("RSA");
            var publicKey = (RSAPublicKey) keyFactory.generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(row.getPublicKey())));
            var decryptedPrivateKeyB64 = EncryptionUtil.decrypt(row.getPrivateKeyEncrypted(), encryptionKeyBytes);
            var privateKey = (RSAPrivateKey) keyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(decryptedPrivateKeyB64)));

            defaultKeyPair = new KeyPair(publicKey, privateKey);
            defaultJWK = new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(row.getKeyId())
                    .algorithm(JWSAlgorithm.RS256)
                    .issueTime(Date.from(row.getCreatedAt()))
                    .build();
            defaultJWKSet = new JWKSet(defaultJWK);
            log.info("Loaded existing persisted default signing key {}", row.getKeyId());
            return;
        }

        log.warn("No persisted default signing key found — generating and persisting a new one. " +
                "This should only happen once, on first startup against a fresh database.");

        var keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(tenantConfig.getJwt().getKeySize());
        defaultKeyPair = keyGen.generateKeyPair();

        var publicKey = (RSAPublicKey) defaultKeyPair.getPublic();
        var privateKey = (RSAPrivateKey) defaultKeyPair.getPrivate();
        var keyId = UUID.randomUUID().toString();

        defaultJWK = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(keyId)
                .algorithm(JWSAlgorithm.RS256)
                .issueTime(new Date())
                .build();
        defaultJWKSet = new JWKSet(defaultJWK);

        var privateKeyB64 = Base64.getEncoder().encodeToString(privateKey.getEncoded());
        var encryptedPrivateKey = EncryptionUtil.encrypt(privateKeyB64, encryptionKeyBytes);

        var row = SystemSigningKey.builder()
                .keyId(keyId)
                .publicKey(Base64.getEncoder().encodeToString(publicKey.getEncoded()))
                .privateKeyEncrypted(encryptedPrivateKey)
                .algorithm("RS256")
                .active(true)
                .createdAt(Instant.now())
                .build();
        systemSigningKeyRepository.save(row);
        log.info("Persisted new default signing key {}", keyId);
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
        // FLAGGED, NOT FIXED, found while writing this service's Helm
        // chart: this reads private_key_encrypted as if it's already a
        // raw PKCS8 key spec — no decryption step at all, despite the
        // field name. That's a real bug (see SystemSigningKey.java's
        // javadoc for the correct pattern, now used for the default key),
        // but nothing anywhere in this codebase ever WRITES
        // tenant.publicKey/privateKeyEncrypted — this path is only
        // reachable if those columns were populated by some mechanism
        // outside this repository entirely. Left unfixed rather than
        // guessing at a decryption scheme for a write path that doesn't
        // exist here; whoever adds tenant key provisioning should follow
        // SystemSigningKey's EncryptionUtil-based pattern, not this one.
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
