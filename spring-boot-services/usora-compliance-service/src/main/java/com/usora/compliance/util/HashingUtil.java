package com.usora.compliance.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class HashingUtil {

    private static final String ALGORITHM = "SHA-256";

    private HashingUtil() {}

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
