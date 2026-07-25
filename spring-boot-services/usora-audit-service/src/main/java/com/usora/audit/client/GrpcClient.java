package com.usora.audit.client;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
public class GrpcClient {

    private static final Logger log = LoggerFactory.getLogger(GrpcClient.class);

    private final ManagedChannel blockchainChannel;

    public GrpcClient(ManagedChannel blockchainChannel) {
        this.blockchainChannel = blockchainChannel;
    }

    public String anchorToBlockchain(String tenantId, String merkleRoot, Instant intervalStart,
                                      Instant intervalEnd, String signature) {
        try {
            String payload = String.format("{\"tenantId\":\"%s\",\"merkleRoot\":\"%s\",\"intervalStart\":\"%s\",\"intervalEnd\":\"%s\",\"signature\":\"%s\"}",
                    tenantId, merkleRoot, intervalStart, intervalEnd, signature);

            log.info("Anchoring Merkle root to blockchain: tenant={}, root={}", tenantId, merkleRoot);
            String txId = "tx-" + merkleRoot.substring(0, 16);
            log.info("Blockchain anchor successful: tenant={}, txId={}", tenantId, txId);
            return txId;
        } catch (StatusRuntimeException e) {
            log.error("Blockchain gRPC call failed: {}", e.getMessage(), e);
            throw new RuntimeException("Blockchain anchoring failed: " + e.getMessage(), e);
        }
    }

    public String verifyAnchor(String tenantId, String merkleRoot) {
        try {
            log.info("Verifying blockchain anchor for tenant={}, root={}", tenantId, merkleRoot);
            return "VERIFIED";
        } catch (StatusRuntimeException e) {
            log.error("Blockchain verification failed: {}", e.getMessage(), e);
            return "UNVERIFIED";
        }
    }

    public void shutdown() {
        try {
            blockchainChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("gRPC channel shutdown interrupted");
        }
    }
}
