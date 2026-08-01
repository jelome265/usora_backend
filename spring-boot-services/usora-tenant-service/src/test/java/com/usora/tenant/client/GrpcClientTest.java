package com.usora.tenant.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SECURITY REGRESSION TEST for
 * docs/architecture-security-review-2026-07-31.md §3.8 — checkIdentity/
 * checkCompliance previously returned `true` unconditionally, which would
 * have silently authorized everything if ever wired into a real
 * authorization check. They must now fail loudly (throw) rather than
 * grant access, until implemented against the real gRPC stubs.
 */
class GrpcClientTest {

    private final GrpcClient client = new GrpcClient(null, null);

    @Test
    void checkIdentityThrowsInsteadOfSilentlyAuthorizing() {
        assertThrows(UnsupportedOperationException.class,
                () -> client.checkIdentity("user-1", "tenant-1"),
                "checkIdentity must not silently return true while unimplemented");
    }

    @Test
    void checkComplianceThrowsInsteadOfSilentlyAuthorizing() {
        assertThrows(UnsupportedOperationException.class,
                () -> client.checkCompliance("tenant-1"),
                "checkCompliance must not silently return true while unimplemented");
    }
}
