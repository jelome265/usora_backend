package com.usora.audit.integration;

import com.usora.audit.client.GrpcClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class GrpcIntegrationTest {

    @Autowired
    private GrpcClient grpcClient;

    @Test
    void shouldAnchorToBlockchain() {
        String tenantId = "test-tenant";
        String merkleRoot = "a".repeat(64);
        Instant now = Instant.now();

        String txId = grpcClient.anchorToBlockchain(
                tenantId, merkleRoot, now, now.plusSeconds(3600), "test-signature");

        assertNotNull(txId);
        assertTrue(txId.startsWith("tx-"));
    }

    @Test
    void shouldVerifyAnchor() {
        String result = grpcClient.verifyAnchor("test-tenant", "b".repeat(64));
        assertNotNull(result);
    }
}
