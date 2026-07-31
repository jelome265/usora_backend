package com.usora.tenant.client;

import io.grpc.ManagedChannel;
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

    // TODO(platform-team, USORA-XXX, 2026-07-31): wire these up to the real
    // generated protobuf stubs (IdentityServiceGrpc / ComplianceServiceGrpc).
    // Until then, both methods intentionally throw rather than silently
    // returning `true` — this method is not currently called from anywhere
    // in the codebase (verified at time of writing), but a method named
    // `checkIdentity`/`checkCompliance` that quietly grants access is exactly
    // the kind of landmine that gets wired into a real authorization check
    // later by someone who reasonably assumes it does what its name says.
    // Failing loudly here means a caller finds out immediately, at
    // integration time, rather than shipping an always-true auth check.

    public boolean checkIdentity(String userId, String tenantId) {
        throw new UnsupportedOperationException(
                "GrpcClient.checkIdentity is not yet implemented against the identity-service "
                        + "protobuf stubs (see USORA-XXX) — it must not be treated as a working "
                        + "authorization check.");
    }

    public boolean checkCompliance(String tenantId) {
        throw new UnsupportedOperationException(
                "GrpcClient.checkCompliance is not yet implemented against the compliance-service "
                        + "protobuf stubs (see USORA-XXX) — it must not be treated as a working "
                        + "compliance check.");
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
