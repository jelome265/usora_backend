package com.usora.audit.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcConfig {

    @Value("${audit.blockchain.grpc.host:localhost}")
    private String blockchainHost;

    @Value("${audit.blockchain.grpc.port:50051}")
    private int blockchainPort;

    @Bean
    public ManagedChannel blockchainChannel() {
        return ManagedChannelBuilder.forAddress(blockchainHost, blockchainPort)
                .usePlaintext()
                .build();
    }
}
