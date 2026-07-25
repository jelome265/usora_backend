package com.usora.compliance.util;

import java.time.Instant;
import java.util.UUID;

public final class IdGenerator {

    private IdGenerator() {}

    public static String generate() {
        return UUIDv7().toString();
    }

    public static UUID UUIDv7() {
        var now = Instant.now();
        var milliseconds = now.toEpochMilli();

        var uuid = new byte[16];
        uuid[0] = (byte) ((milliseconds >> 40) & 0xFF);
        uuid[1] = (byte) ((milliseconds >> 32) & 0xFF);
        uuid[2] = (byte) ((milliseconds >> 24) & 0xFF);
        uuid[3] = (byte) ((milliseconds >> 16) & 0xFF);
        uuid[4] = (byte) ((milliseconds >> 8) & 0xFF);
        uuid[5] = (byte) (milliseconds & 0xFF);
        uuid[6] = (byte) ((uuid[6] & 0x0F) | 0x70);
        uuid[7] = (byte) ((uuid[7] & 0x3F) | 0x80);

        // Fill remaining with random bytes
        var random = new java.security.SecureRandom();
        random.nextBytes(uuid);
        uuid[6] = (byte) ((uuid[6] & 0x0F) | 0x70);
        uuid[8] = (byte) ((uuid[8] & 0x3F) | 0x80);

        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) msb = (msb << 8) | (uuid[i] & 0xFF);
        for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (uuid[i] & 0xFF);

        return new UUID(msb, lsb);
    }

    public static String shortId() {
        return Long.toHexString(Instant.now().toEpochMilli()) + Integer.toHexString(System.identityHashCode(new Object()));
    }
}
