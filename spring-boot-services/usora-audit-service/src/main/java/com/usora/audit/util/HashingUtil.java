package com.usora.audit.util;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

@Component
public class HashingUtil {

    private static final String SHA256 = "SHA-256";
    private static final String HMAC_SHA256 = "HmacSHA256";

    public String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA256);
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    public String hmacSha256(String data, String key) {
        try {
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(secretKey);
            byte[] hmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hmac);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 computation failed", e);
        }
    }

    public String buildMerkleRoot(List<String> hashes) {
        if (hashes == null || hashes.isEmpty()) {
            return "0".repeat(64);
        }

        List<String> currentLevel = new ArrayList<>(hashes);

        while (currentLevel.size() > 1) {
            List<String> nextLevel = new ArrayList<>();

            for (int i = 0; i < currentLevel.size(); i += 2) {
                String left = currentLevel.get(i);
                String right = (i + 1 < currentLevel.size()) ? currentLevel.get(i + 1) : left;
                nextLevel.add(sha256(left + right));
            }

            currentLevel = nextLevel;
        }

        return currentLevel.getFirst();
    }

    public String computeChainHash(String previousHash, String eventData, long eventTimestamp) {
        return sha256(previousHash + eventData + eventTimestamp);
    }

    public boolean verifyChainIntegrity(List<String> expectedHashes, List<String> computedHashes) {
        if (expectedHashes.size() != computedHashes.size()) {
            return false;
        }
        for (int i = 0; i < expectedHashes.size(); i++) {
            if (!expectedHashes.get(i).equals(computedHashes.get(i))) {
                return false;
            }
        }
        return true;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
