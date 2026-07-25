package com.usora.integration.util;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class IdGenerator {

    private static final long EPOCH = 1700000000000L;
    private static final int NODE_ID_BITS = 10;
    private static final int SEQUENCE_BITS = 12;
    private static final int MAX_SEQUENCE = (1 << SEQUENCE_BITS) - 1;

    private final long nodeId;
    private final AtomicLong lastTimestamp = new AtomicLong(-1L);
    private final AtomicLong sequence = new AtomicLong(0L);

    public IdGenerator() {
        this.nodeId = 1L;
    }

    public IdGenerator(long nodeId) {
        if (nodeId < 0 || nodeId >= (1 << NODE_ID_BITS)) {
            throw new IllegalArgumentException("Node ID must be between 0 and " + ((1 << NODE_ID_BITS) - 1));
        }
        this.nodeId = nodeId;
    }

    public String generateId() {
        return UUID.randomUUID().toString();
    }

    public UUID generateUuidV7() {
        return UUID.randomUUID();
    }

    public String generateEventId() {
        return generateId() + "-" + Instant.now().toEpochMilli();
    }

    public long generateSnowflakeId() {
        long currentTimestamp = System.currentTimeMillis() - EPOCH;
        long lastTs = lastTimestamp.get();

        if (currentTimestamp == lastTs) {
            long seq = sequence.incrementAndGet() & MAX_SEQUENCE;
            if (seq == 0) {
                while (System.currentTimeMillis() - EPOCH <= currentTimestamp) {
                    Thread.yield();
                }
                currentTimestamp = System.currentTimeMillis() - EPOCH;
            }
        } else {
            sequence.set(0);
        }

        lastTimestamp.set(currentTimestamp);

        return (currentTimestamp << (NODE_ID_BITS + SEQUENCE_BITS))
                | (nodeId << SEQUENCE_BITS)
                | sequence.get();
    }

    public String generateCorrelationId() {
        return "corr-" + generateSnowflakeId();
    }
}
