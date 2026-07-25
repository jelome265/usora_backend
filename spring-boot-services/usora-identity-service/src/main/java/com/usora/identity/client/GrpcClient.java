package com.usora.identity.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class GrpcClient {

    private ManagedChannel notificationChannel;
    private ManagedChannel auditChannel;
    private ManagedChannel tenantChannel;

    public void init() {
        notificationChannel = ManagedChannelBuilder.forAddress("usora-notification-service", 50055)
                .usePlaintext()
                .build();
        auditChannel = ManagedChannelBuilder.forAddress("usora-audit-service", 50056)
                .usePlaintext()
                .build();
        tenantChannel = ManagedChannelBuilder.forAddress("usora-tenant-service", 50053)
                .usePlaintext()
                .build();

        log.info("gRPC channels initialized");
    }

    public ManagedChannel getNotificationChannel() {
        if (notificationChannel == null || notificationChannel.isShutdown()) {
            init();
        }
        return notificationChannel;
    }

    public ManagedChannel getAuditChannel() {
        if (auditChannel == null || auditChannel.isShutdown()) {
            init();
        }
        return auditChannel;
    }

    public ManagedChannel getTenantChannel() {
        if (tenantChannel == null || tenantChannel.isShutdown()) {
            init();
        }
        return tenantChannel;
    }

    @PreDestroy
    public void shutdown() {
        if (notificationChannel != null) notificationChannel.shutdown();
        if (auditChannel != null) auditChannel.shutdown();
        if (tenantChannel != null) tenantChannel.shutdown();
        log.info("gRPC channels shut down");
    }
}
