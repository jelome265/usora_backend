package com.usora.notification.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Component
public class EncryptionUtil {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final SecretKey secretKey;

    public EncryptionUtil(@Value("${security.encryption.secret-key:}") String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            var keyGen = new SecureRandom();
            var keyBytes = new byte[32];
            keyGen.nextBytes(keyBytes);
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
            log.warn("Using auto-generated encryption key. Set security.encryption.secret-key in production.");
        } else {
            var keyBytes = Base64.getDecoder().decode(encodedKey);
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
        }
    }

    public String encrypt(String plaintext) {
        try {
            var cipher = Cipher.getInstance(ALGORITHM);
            var iv = new byte[GCM_IV_LENGTH];
            SecureRandom.getInstanceStrong().nextBytes(iv);
            var spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

            var ciphertext = cipher.doFinal(plaintext.getBytes());
            var combined = ByteBuffer.allocate(iv.length + ciphertext.length);
            combined.put(iv);
            combined.put(ciphertext);

            return Base64.getEncoder().encodeToString(combined.array());
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public String decrypt(String encryptedData) {
        try {
            var decoded = Base64.getDecoder().decode(encryptedData);
            var buffer = ByteBuffer.wrap(decoded);
            var iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            var ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            var cipher = Cipher.getInstance(ALGORITHM);
            var spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            return new String(cipher.doFinal(ciphertext));
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
