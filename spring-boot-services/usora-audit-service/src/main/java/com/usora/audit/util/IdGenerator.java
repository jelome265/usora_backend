package com.usora.audit.util;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class IdGenerator {

    public UUID generateUuid() {
        return generateUuidV7();
    }

    public UUID generateUuidV7() {
        long timestamp = Instant.now().toEpochMilli();

        long mostSigBits = timestamp << 16;
        mostSigBits |= 0x7000;
        mostSigBits |= (long) (Math.random() * 0x0FFF);

        long leastSigBits = 0x8000000000000000L | (long) (Math.random() * Long.MAX_VALUE);

        return new UUID(mostSigBits, leastSigBits);
    }

    public String generateEventId() {
        return generateUuid().toString();
    }

    public String generateShortId() {
        return Long.toHexString(Instant.now().toEpochMilli())
                + Integer.toHexString(System.identityHashCode(this));
    }
}
