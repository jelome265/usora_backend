package com.usora.identity.util;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
public final class HashingUtil {

    private static final int SALT_LENGTH = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

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

    public static String sha256WithSalt(String input, String salt) {
        return sha256(salt + input);
    }

    public static String generateSalt() {
        var salt = new byte[SALT_LENGTH];
        SECURE_RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public static String hashPassword(String password, String salt) {
        return sha256WithSalt(password, salt);
    }

    public static boolean verifyPassword(String password, String hash, String salt) {
        var computedHash = hashPassword(password, salt);
        return MessageDigest.isEqual(computedHash.getBytes(StandardCharsets.UTF_8), hash.getBytes(StandardCharsets.UTF_8));
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
