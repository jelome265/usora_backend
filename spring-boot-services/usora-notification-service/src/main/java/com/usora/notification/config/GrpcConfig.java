package com.usora.notification.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;

@Configuration
public class GrpcConfig {

    @Value("${grpc.tenant-service.host:localhost}")
    private String tenantServiceHost;

    @Value("${grpc.tenant-service.port:9090}")
    private int tenantServicePort;

    @Bean
    public ManagedChannel tenantServiceChannel() {
        return ManagedChannelBuilder.forAddress(tenantServiceHost, tenantServicePort)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .usePlaintext()
                .build();
    }
}
