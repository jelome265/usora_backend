package com.usora.identity.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCrypt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
public final class HashingUtil {

    private static final int SALT_LENGTH = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // Work factor 12 is the OWASP-recommended floor for BCrypt as of 2024/2025
    // guidance; tune upward as hardware improves, but never down.
    private static final int BCRYPT_WORK_FACTOR = 12;

    private HashingUtil() {}

    public static String sha256(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            log.error("SHA-256 hashing failed", e);
            throw new RuntimeException("Hashing failed", e);
        }
    }

    /**
     * @deprecated SHA-256 (even salted) is a fast, non-adaptive hash and is
     * NOT suitable for password storage -- it makes brute-force/GPU/ASIC
     * cracking of stolen hashes practical at scale. Use
     * {@link #hashPassword(String)} (BCrypt) for anything credential-related.
     * Retained only for any non-password use of a keyed/salted digest that
     * may exist elsewhere; do not use for new password code.
     */
    @Deprecated
    public static String sha256WithSalt(String input, String salt) {
        return sha256(salt + input);
    }

    /**
     * @deprecated no longer needed for password hashing -- BCrypt generates
     * and embeds its own per-hash salt in the encoded output, so a
     * separately-managed salt should not be threaded through
     * hashPassword/verifyPassword anymore. Retained only in case another
     * caller still depends on it for a non-password use.
     */
    @Deprecated
    public static String generateSalt() {
        var salt = new byte[SALT_LENGTH];
        SECURE_RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /**
     * Hashes a password with BCrypt. BCrypt is deliberately slow (tunable via
     * {@link #BCRYPT_WORK_FACTOR}) and embeds a unique random salt in its
     * output, unlike the previous SHA-256-based implementation which was
     * fast enough to make offline cracking of a stolen hash database
     * practical.
     */
    public static String hashPassword(String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt(BCRYPT_WORK_FACTOR));
    }

    /**
     * Verifies a password against a BCrypt hash. {@link BCrypt#checkpw} is
     * already constant-time with respect to the comparison itself.
     */
    public static boolean verifyPassword(String password, String bcryptHash) {
        return BCrypt.checkpw(password, bcryptHash);
    }

    public static String base64Encode(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    public static byte[] base64Decode(String encoded) {
        return Base64.getDecoder().decode(encoded);
    }

    private static String bytesToHex(byte[] bytes) {
        var sb = new StringBuilder();
        for (var b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
