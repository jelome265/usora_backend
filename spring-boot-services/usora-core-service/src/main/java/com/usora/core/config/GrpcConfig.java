package com.usora.core.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class GrpcConfig {

    @Value("${usora.grpc.server.port:9090}")
    private int grpcServerPort;

    @Value("${usora.compute.document.host:document-compute.usora.svc.cluster.local}")
    private String documentHost;

    @Value("${usora.compute.document.port:9091}")
    private int documentPort;

    @Value("${usora.compute.biometric.host:biometric-compute.usora.svc.cluster.local}")
    private String biometricHost;

    @Value("${usora.compute.biometric.port:9092}")
    private int biometricPort;

    @Value("${usora.compute.risk.host:risk-compute.usora.svc.cluster.local}")
    private String riskHost;

    @Value("${usora.compute.risk.port:9093}")
    private int riskPort;

    @Value("${usora.grpc.client.deadline-ms:5000}")
    private long deadlineMs;

    @Bean
    public ManagedChannel documentChannel() {
        return buildChannel(documentHost, documentPort);
    }

    @Bean
    public ManagedChannel biometricChannel() {
        return buildChannel(biometricHost, biometricPort);
    }

    @Bean
    public ManagedChannel riskChannel() {
        return buildChannel(riskHost, riskPort);
    }

    private ManagedChannel buildChannel(String host, int port) {
        return ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .maxInboundMessageSize(4 * 1024 * 1024)
                .build();
    }
}
