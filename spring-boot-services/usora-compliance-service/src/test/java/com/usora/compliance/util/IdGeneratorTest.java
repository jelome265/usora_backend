package com.usora.compliance.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class IdGeneratorTest {

    @Test
    void shouldGenerateUniqueIds() {
        var ids = new HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            ids.add(IdGenerator.generate());
        }
        assertEquals(1000, ids.size());
    }

    @Test
    void shouldGenerateValidUUID() {
        var id = IdGenerator.generate();
        assertDoesNotThrow(() -> UUID.fromString(id));
    }

    @Test
    void uuidv7ShouldHaveCorrectVersion() {
        var uuid = IdGenerator.UUIDv7();
        assertEquals(7, uuid.version());
    }

    @Test
    void shortIdShouldNotBeEmpty() {
        var shortId = IdGenerator.shortId();
        assertNotNull(shortId);
        assertFalse(shortId.isBlank());
    }
}
