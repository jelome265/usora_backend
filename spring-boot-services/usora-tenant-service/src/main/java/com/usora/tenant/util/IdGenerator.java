package com.usora.tenant.util;

import java.time.Instant;
import java.util.UUID;

public final class IdGenerator {

    private IdGenerator() {}

    public static UUID generateUUIDv7() {
        long timestamp = Instant.now().toEpochMilli();
        long mostSigBits = timestamp << 16;

        // Set version 7
        mostSigBits |= 0x7000;
        mostSigBits &= 0x7FFF_FFFF_FFFF_FFFFL;

        long leastSigBits = UUID.randomUUID().getLeastSignificantBits();
        // Set variant 2 (IETF)
        leastSigBits = (leastSigBits & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;

        return new UUID(mostSigBits, leastSigBits);
    }

    public static String generateShortId() {
        UUID uuid = generateUUIDv7();
        String encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                .encode(toBytes(uuid));
        return encoded.substring(0, 12);
    }

    private static byte[] toBytes(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        byte[] buffer = new byte[16];
        for (int i = 0; i < 8; i++) {
            buffer[i] = (byte) (msb >>> (8 * (7 - i)));
            buffer[8 + i] = (byte) (lsb >>> (8 * (7 - i)));
        }
        return buffer;
    }
}
