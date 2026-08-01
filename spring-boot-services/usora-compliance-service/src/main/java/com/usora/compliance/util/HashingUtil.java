package com.usora.compliance.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class HashingUtil {

    private static final String ALGORITHM = "SHA-256";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private HashingUtil() {}

    /**
     * Computes a keyed HMAC-SHA256 over the given content using the supplied
     * secret key. Unlike a bare SHA-256 digest, this cannot be recomputed by
     * anyone who only knows the (public) content — it requires the secret
     * key, which is what makes it usable as evidence that a specific,
     * key-holding party produced/approved the content. Use this (not
     * {@link #sha256(String)}) anywhere a "signature" over content is
     * being asserted, e.g. dual-authorization rule signing.
     *
     * @param content   the data being signed
     * @param secretKey the HMAC secret, sourced from Vault/KMS — never
     *                  hardcoded or derived from public data
     */
    public static String hmacSha256(String content, String secretKey) {
        try {
            var mac = Mac.getInstance(HMAC_ALGORITHM);
            var keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            var hmacBytes = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            var hexString = new StringBuilder();
            for (var b : hmacBytes) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 signing failed", e);
        }
    }

    /**
     * Verifies a previously-computed HMAC-SHA256 signature using a
     * constant-time comparison to avoid leaking timing information about
     * how much of the signature matched.
     */
    public static boolean verifyHmacSha256(String content, String secretKey, String signature) {
        var expected = hmacSha256(content, secretKey);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(String input) {
        try {
            var digest = MessageDigest.getInstance(ALGORITHM);
            var hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            var hexString = new StringBuilder();
            for (var b : hashBytes) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 hashing failed", e);
        }
    }

    public static String sha256(byte[] input) {
        try {
            var digest = MessageDigest.getInstance(ALGORITHM);
            var hashBytes = digest.digest(input);
            var hexString = new StringBuilder();
            for (var b : hashBytes) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 hashing failed", e);
        }
    }

    public static String doubleSha256(String input) {
        return sha256(sha256(input));
    }
}
