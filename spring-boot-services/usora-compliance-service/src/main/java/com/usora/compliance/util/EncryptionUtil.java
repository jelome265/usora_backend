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
        if (keyEnv == null || keyEnv.isBlank()) {
            // SECURITY: this used to silently fall back to a 32-byte
            // all-zero key ("default only for dev") if COMPLIANCE_ENCRYPTION_KEY
            // was unset. AES-GCM's math is correct either way, but an
            // all-zero key is a known constant anyone can derive from this
            // source file, so "encrypted" evidence records were only as
            // confidential as if they were stored in plaintext. Fail fast
            // instead of ever encrypting real KYC evidence under a public key.
            throw new IllegalStateException(
                    "COMPLIANCE_ENCRYPTION_KEY is not set -- refusing to encrypt/decrypt evidence records " +
                    "with a default key. Generate one with e.g. `openssl rand -base64 32` and set it via " +
                    "a secret store, never a plaintext default.");
        }
        var keyBytes = Base64.getDecoder().decode(keyEnv);
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
