package com.usora.compliance.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

public final class EncryptionUtil {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;
    private static final String SECRET_KEY_ENV = "COMPLIANCE_ENCRYPTION_KEY";

    private EncryptionUtil() {}

    private static SecretKey getSecretKey() {
        var keyEnv = System.getenv(SECRET_KEY_ENV);
        var keyBytes = keyEnv != null && !keyEnv.isBlank()
                ? Base64.getDecoder().decode(keyEnv)
                : new byte[32]; // default only for dev
        return new SecretKeySpec(keyBytes, "AES");
    }

    public static byte[] encrypt(byte[] plaintext) {
        try {
            var cipher = Cipher.getInstance(ALGORITHM);
            var iv = new byte[IV_LENGTH];
            SecureRandom.getInstanceStrong().nextBytes(iv);
            var spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey(), spec);
            var ciphertext = cipher.doFinal(plaintext);
            var combined = new byte[IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, IV_LENGTH, ciphertext.length);
            return combined;
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public static byte[] decrypt(byte[] ciphertextWithIv) {
        try {
            var cipher = Cipher.getInstance(ALGORITHM);
            var iv = new byte[IV_LENGTH];
            System.arraycopy(ciphertextWithIv, 0, iv, 0, IV_LENGTH);
            var spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec);
            return cipher.doFinal(ciphertextWithIv, IV_LENGTH, ciphertextWithIv.length - IV_LENGTH);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    public static String encryptToBase64(byte[] plaintext) {
        return Base64.getEncoder().encodeToString(encrypt(plaintext));
    }

    public static byte[] decryptFromBase64(String base64Ciphertext) {
        return decrypt(Base64.getDecoder().decode(base64Ciphertext));
    }
}
