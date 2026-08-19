package com.usora.identity.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * PRE-EXISTING BUG, fixed while writing this service's Helm chart: see
 * db/migration/V3__system_signing_keys.sql for the full finding. This
 * entity persists the default RSA signing key so every replica of this
 * service shares the same key instead of each pod generating its own on
 * startup.
 */
@Entity
@Table(name = "system_signing_keys", schema = "identity")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemSigningKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "key_id", nullable = false, unique = true)
    private String keyId;

    @Column(name = "public_key", nullable = false, columnDefinition = "TEXT")
    private String publicKey;

    /**
     * AES-GCM encrypted (via EncryptionUtil, keyed by
     * identity.security.encryption-key) PKCS8-encoded private key bytes,
     * base64. NOT plaintext — unlike the pre-existing tenant key-loading
     * path (loadTenantKeyPair), which reads private_key_encrypted as if
     * it were already a raw PKCS8 spec with no decryption step at all.
     * That path is currently unreachable (nothing in this codebase ever
     * writes a tenant's public_key/private_key_encrypted columns), so it
     * was left as a flagged, not-fixed finding rather than fixed
     * speculatively — but any code that starts writing to that table
     * should follow this class's pattern, not the old one.
     */
    @Column(name = "private_key_encrypted", nullable = false, columnDefinition = "TEXT")
    private String privateKeyEncrypted;

    @Column(name = "algorithm", nullable = false)
    private String algorithm;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;
}
