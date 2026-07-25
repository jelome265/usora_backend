package com.usora.compliance.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class GrpcConfig {

    @Value("${compliance.aml-screening.host:localhost}")
    private String amlHost;

    @Value("${compliance.aml-screening.port:9090}")
    private int amlPort;

    @Bean(name = "amlScreeningChannel")
    public ManagedChannel amlScreeningChannel() {
        return ManagedChannelBuilder.forAddress(amlHost, amlPort)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .maxRetryAttempts(3)
                .build();
    }
}
