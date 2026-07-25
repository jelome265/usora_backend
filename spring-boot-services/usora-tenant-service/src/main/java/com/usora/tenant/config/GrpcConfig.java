package com.usora.tenant.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcConfig {

    @Value("${grpc.server.port:9090}")
    private int grpcPort;

    @Value("${grpc.client.identity-service.host:localhost}")
    private String identityServiceHost;

    @Value("${grpc.client.identity-service.port:9091}")
    private int identityServicePort;

    @Value("${grpc.client.compliance-service.host:localhost}")
    private String complianceServiceHost;

    @Value("${grpc.client.compliance-service.port:9092}")
    private int complianceServicePort;

    @Bean
    public ManagedChannel identityServiceChannel() {
        return ManagedChannelBuilder.forAddress(identityServiceHost, identityServicePort)
                .usePlaintext()
                .build();
    }

    @Bean
    public ManagedChannel complianceServiceChannel() {
        return ManagedChannelBuilder.forAddress(complianceServiceHost, complianceServicePort)
                .usePlaintext()
                .build();
    }
}
