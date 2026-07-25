package com.usora.compliance.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class EncryptionUtilTest {

    @Test
    void shouldEncryptAndDecrypt() {
        var original = "sensitive-content".getBytes(StandardCharsets.UTF_8);
        var encrypted = EncryptionUtil.encrypt(original);
        var decrypted = EncryptionUtil.decrypt(encrypted);
        assertArrayEquals(original, decrypted);
    }

    @Test
    void shouldEncryptToBase64AndDecrypt() {
        var original = "confidential".getBytes(StandardCharsets.UTF_8);
        var encryptedBase64 = EncryptionUtil.encryptToBase64(original);
        var decrypted = EncryptionUtil.decryptFromBase64(encryptedBase64);
        assertArrayEquals(original, decrypted);
    }

    @Test
    void encryptedOutputShouldDifferFromInput() {
        var original = "test".getBytes(StandardCharsets.UTF_8);
        var encrypted = EncryptionUtil.encrypt(original);
        assertFalse(new String(encrypted).equals(new String(original)));
    }
}
