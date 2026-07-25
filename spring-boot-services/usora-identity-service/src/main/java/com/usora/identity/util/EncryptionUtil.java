package com.usora.identity.util;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
public final class EncryptionUtil {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private EncryptionUtil() {}

    public static String encrypt(String plaintext, byte[] keyBytes) {
        try {
            var key = new SecretKeySpec(keyBytes, "AES");
            var cipher = Cipher.getInstance(ALGORITHM);
            var iv = new byte[GCM_IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);
            var spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);
            var ciphertext = cipher.doFinal(plaintext.getBytes());
            var combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("Encryption failed", e);
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public static String decrypt(String encryptedData, byte[] keyBytes) {
        try {
            var key = new SecretKeySpec(keyBytes, "AES");
            var cipher = Cipher.getInstance(ALGORITHM);
            var combined = Base64.getDecoder().decode(encryptedData);
            var iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            var ciphertext = new byte[combined.length - iv.length];
            System.arraycopy(combined, iv.length, ciphertext, 0, ciphertext.length);
            var spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            return new String(cipher.doFinal(ciphertext));
        } catch (Exception e) {
            log.error("Decryption failed", e);
            throw new RuntimeException("Decryption failed", e);
        }
    }

    public static byte[] generateKey(int keySize) {
        var keyBytes = new byte[keySize / 8];
        SECURE_RANDOM.nextBytes(keyBytes);
        return keyBytes;
    }
}
