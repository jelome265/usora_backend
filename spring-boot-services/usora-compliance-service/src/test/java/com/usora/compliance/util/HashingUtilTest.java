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
}
