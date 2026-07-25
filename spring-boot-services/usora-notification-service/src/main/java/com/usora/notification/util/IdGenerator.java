package com.usora.notification.util;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class IdGenerator {

    public UUID generateId() {
        return generateUuidV7();
    }

    public UUID generateUuidV7() {
        var now = Instant.now();
        long timestamp = now.toEpochMilli();

        long msb = (timestamp & 0xFFFFFFFFFFFFL) << 16;
        msb |= 0x7000;
        msb |= (long) (Math.random() * 0x0FFF);

        long lsb = (long) (Math.random() * Long.MAX_VALUE);

        var uuid = new UUID(msb, lsb);
        return uuid;
    }

    public String generateIdString() {
        return generateId().toString();
    }
}
