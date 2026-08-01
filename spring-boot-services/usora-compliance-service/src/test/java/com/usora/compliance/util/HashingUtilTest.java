package com.usora.compliance.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HashingUtilTest {

    @Test
    void shouldProduceConsistentHash() {
        var input = "test-content";
        var hash1 = HashingUtil.sha256(input);
        var hash2 = HashingUtil.sha256(input);
        assertEquals(hash1, hash2);
    }

    @Test
    void shouldProduce64CharHexHash() {
        var hash = HashingUtil.sha256("anything");
        assertEquals(64, hash.length());
    }

    @Test
    void shouldProduceDifferentHashesForDifferentInputs() {
        var hash1 = HashingUtil.sha256("input1");
        var hash2 = HashingUtil.sha256("input2");
        assertNotEquals(hash1, hash2);
    }

    @Test
    void shouldHandleEmptyString() {
        var hash = HashingUtil.sha256("");
        assertNotNull(hash);
        assertEquals(64, hash.length());
    }

    @Test
    void shouldHandleByteArrayInput() {
        var hash = HashingUtil.sha256("test".getBytes());
        assertNotNull(hash);
        assertEquals(64, hash.length());
    }

    @Test
    void doubleSha256ShouldBeDifferentFromSingle() {
        var single = HashingUtil.sha256("test");
        var doubled = HashingUtil.doubleSha256("test");
        assertNotEquals(single, doubled);
    }

    @Test
    void hmacShouldBeConsistentForSameContentAndKey() {
        var mac1 = HashingUtil.hmacSha256("rule-content::v1::tenant-a", "secret-key-min-32-characters-long!!");
        var mac2 = HashingUtil.hmacSha256("rule-content::v1::tenant-a", "secret-key-min-32-characters-long!!");
        assertEquals(mac1, mac2);
    }

    @Test
    void hmacShouldDifferForDifferentKeys() {
        var macWithKeyA = HashingUtil.hmacSha256("rule-content::v1::tenant-a", "key-a-32-characters-long-padding!!!");
        var macWithKeyB = HashingUtil.hmacSha256("rule-content::v1::tenant-a", "key-b-32-characters-long-padding!!!");
        assertNotEquals(macWithKeyA, macWithKeyB);
    }

    @Test
    void hmacShouldProduce64CharHexOutput() {
        var mac = HashingUtil.hmacSha256("content", "some-secret-key-value-1234567890");
        assertEquals(64, mac.length());
    }

    @Test
    void verifyHmacShouldSucceedForMatchingSignature() {
        var content = "rule-content::v2::tenant-b";
        var key = "another-secret-key-32-chars-min!!";
        var signature = HashingUtil.hmacSha256(content, key);
        assertTrue(HashingUtil.verifyHmacSha256(content, key, signature));
    }

    @Test
    void verifyHmacShouldFailForTamperedContent() {
        var key = "another-secret-key-32-chars-min!!";
        var signature = HashingUtil.hmacSha256("original-content", key);
        assertFalse(HashingUtil.verifyHmacSha256("tampered-content", key, signature));
    }

    /**
     * SECURITY REGRESSION TEST: this is the exact vulnerability described in
     * docs/architecture-security-review-2026-07-31.md §3.3 — a bare SHA-256
     * hash of public data (rule content + version + tenant ID) can be
     * recomputed by anyone who knows those public inputs, so it proves
     * nothing about who signed the content. The keyed HMAC must NOT be
     * reproducible without the secret, even though the "public" inputs
     * (content string) are identical to what an attacker would know.
     */
    @Test
    void hmacCannotBeForgedWithoutTheSecretUnlikeABareHash() {
        var publicContent = "drl-content::v3::tenant-c";

        // An attacker who only knows the public content can trivially
        // reproduce the OLD (vulnerable) scheme...
        var forgeableBareHash = HashingUtil.sha256(publicContent);
        assertEquals(forgeableBareHash, HashingUtil.sha256(publicContent),
                "demonstrates the bare hash is fully reproducible from public data alone");

        // ...but cannot reproduce a valid HMAC without the secret key,
        // even though they know the exact same public content.
        var realSecret = "the-real-secret-only-the-service-holds!";
        var guessedSecret = "an-attackers-guess-at-the-secret-value!";
        var realSignature = HashingUtil.hmacSha256(publicContent, realSecret);
        var forgedAttempt = HashingUtil.hmacSha256(publicContent, guessedSecret);

        assertNotEquals(realSignature, forgedAttempt,
                "an attacker without the real secret must not be able to reproduce a valid signature");
        assertFalse(HashingUtil.verifyHmacSha256(publicContent, realSecret, forgedAttempt));
    }
}
