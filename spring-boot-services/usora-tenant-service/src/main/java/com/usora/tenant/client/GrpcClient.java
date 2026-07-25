package com.usora.tenant.client;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class GrpcClient {

    private static final Logger log = LoggerFactory.getLogger(GrpcClient.class);

    private final ManagedChannel identityServiceChannel;
    private final ManagedChannel complianceServiceChannel;

    public GrpcClient(ManagedChannel identityServiceChannel,
                      ManagedChannel complianceServiceChannel) {
        this.identityServiceChannel = identityServiceChannel;
        this.complianceServiceChannel = complianceServiceChannel;
    }

    public boolean checkIdentity(String userId, String tenantId) {
        try {
            // In production: use generated protobuf stubs
            // IdentityServiceGrpc.IdentityServiceBlockingStub stub =
            //     IdentityServiceGrpc.newBlockingStub(identityServiceChannel);
            // var request = UserCheckRequest.newBuilder().setUserId(userId).setTenantId(tenantId).build();
            // var response = stub.checkUserAccess(request);
            // return response.getAccessGranted();

            log.debug("Checking identity for user: {} in tenant: {}", userId, tenantId);
            return true;
        } catch (StatusRuntimeException e) {
            log.error("gRPC call to identity service failed: {}", e.getMessage());
            return false;
        }
    }

    public boolean checkCompliance(String tenantId) {
        try {
            // In production: use generated protobuf stubs
            // ComplianceServiceGrpc.ComplianceServiceBlockingStub stub =
            //     ComplianceServiceGrpc.newBlockingStub(complianceServiceChannel);
            // var request = ComplianceCheckRequest.newBuilder().setTenantId(tenantId).build();
            // var response = stub.checkCompliance(request);
            // return response.getCompliant();

            log.debug("Checking compliance for tenant: {}", tenantId);
            return true;
        } catch (StatusRuntimeException e) {
            log.error("gRPC call to compliance service failed: {}", e.getMessage());
            return false;
        }
    }

    public void shutdown() {
        identityServiceChannel.shutdown();
        complianceServiceChannel.shutdown();
        try {
            if (!identityServiceChannel.awaitTermination(5, TimeUnit.SECONDS)) {
                identityServiceChannel.shutdownNow();
            }
            if (!complianceServiceChannel.awaitTermination(5, TimeUnit.SECONDS)) {
                complianceServiceChannel.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
