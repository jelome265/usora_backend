package com.usora.integration.client;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GrpcClient {

    private static final Logger log = LoggerFactory.getLogger(GrpcClient.class);

    @GrpcClient("banking-service")
    private ManagedChannel bankingChannel;

    @GrpcClient("government-service")
    private ManagedChannel governmentChannel;

    @GrpcClient("credit-service")
    private ManagedChannel creditChannel;

    public ManagedChannel getBankingChannel() {
        return bankingChannel;
    }

    public ManagedChannel getGovernmentChannel() {
        return governmentChannel;
    }

    public ManagedChannel getCreditChannel() {
        return creditChannel;
    }

    public <T> T withChannel(ManagedChannel channel, GrpcOperation<T> operation) {
        try {
            return operation.execute();
        } catch (StatusRuntimeException e) {
            log.error("gRPC call failed with status: {} - {}", e.getStatus(), e.getMessage());
            throw new RuntimeException("gRPC call failed: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    public interface GrpcOperation<T> {
        T execute();
    }
}
