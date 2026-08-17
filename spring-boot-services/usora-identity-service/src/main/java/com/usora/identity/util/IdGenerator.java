package com.usora.identity.util;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public final class IdGenerator {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private IdGenerator() {}

    public static UUID uuidV7() {
        var now = Instant.now().toEpochMilli();
        var uuid = new byte[16];

        uuid[0] = (byte) ((now >>> 40) & 0xFF);
        uuid[1] = (byte) ((now >>> 32) & 0xFF);
        uuid[2] = (byte) ((now >>> 24) & 0xFF);
        uuid[3] = (byte) ((now >>> 16) & 0xFF);
        uuid[4] = (byte) ((now >>> 8) & 0xFF);
        uuid[5] = (byte) (now & 0xFF);
        uuid[6] = (byte) ((uuid[6] & 0x0F) | 0x70);
        uuid[7] = (byte) ((uuid[7] & 0x3F) | 0x80);

        byte[] randomBytes = new byte[8];
        SECURE_RANDOM.nextBytes(randomBytes);
        System.arraycopy(randomBytes, 0, uuid, 8, 8);

        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (uuid[i] & 0xFF);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (uuid[i] & 0xFF);
        }

        return new UUID(msb, lsb);
    }

    public static String secureToken() {
        var bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String randomHex(int bytes) {
        var data = new byte[bytes];
        SECURE_RANDOM.nextBytes(data);
        var sb = new StringBuilder();
        for (var b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static String generateClientId() {
        return "client_" + randomHex(16);
    }

    public static String generateClientSecret() {
        return secureToken();
    }
}
