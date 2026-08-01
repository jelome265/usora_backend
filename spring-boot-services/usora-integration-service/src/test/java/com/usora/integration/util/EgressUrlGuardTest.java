package com.usora.integration.util;

import com.usora.integration.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the SSRF egress guard added in
 * docs/architecture-security-review-2026-07-31.md §3.7 — outbound
 * webhook/REST calls must not be allowed to hit internal/private
 * infrastructure, the cloud metadata endpoint, or loopback addresses.
 */
class EgressUrlGuardTest {

    @Test
    void allowsOrdinaryPublicHttpsUrl() {
        assertDoesNotThrow(() -> EgressUrlGuard.assertSafeDestination("https://example.com/webhook"));
    }

    @Test
    void allowsOrdinaryPublicHttpUrl() {
        assertDoesNotThrow(() -> EgressUrlGuard.assertSafeDestination("http://example.com/webhook"));
    }

    @Test
    void rejectsNonHttpScheme() {
        assertThrows(BusinessException.class,
                () -> EgressUrlGuard.assertSafeDestination("file:///etc/passwd"));
    }

    @Test
    void rejectsMalformedUrl() {
        assertThrows(BusinessException.class,
                () -> EgressUrlGuard.assertSafeDestination("not a url at all"));
    }

    @Test
    void rejectsUrlWithNoHost() {
        assertThrows(BusinessException.class,
                () -> EgressUrlGuard.assertSafeDestination("https:///no-host-here"));
    }

    /**
     * SECURITY REGRESSION TEST: the cloud metadata endpoint is the single
     * highest-value SSRF target (credential theft on every major cloud
     * provider) and must always be blocked.
     */
    @Test
    void rejectsCloudMetadataEndpoint() {
        assertThrows(BusinessException.class,
                () -> EgressUrlGuard.assertSafeDestination("http://169.254.169.254/latest/meta-data/"));
    }

    @Test
    void rejectsLoopbackAddress() {
        assertThrows(BusinessException.class,
                () -> EgressUrlGuard.assertSafeDestination("http://127.0.0.1:8080/internal"));
    }

    @Test
    void rejectsLocalhostHostname() {
        assertThrows(BusinessException.class,
                () -> EgressUrlGuard.assertSafeDestination("http://localhost:8080/internal"));
    }

    @Test
    void rejectsPrivateRfc1918TenNetwork() {
        assertThrows(BusinessException.class,
                () -> EgressUrlGuard.assertSafeDestination("http://10.0.0.5/internal-service"));
    }

    @Test
    void rejectsPrivateRfc1918OneNinetyTwoNetwork() {
        assertThrows(BusinessException.class,
                () -> EgressUrlGuard.assertSafeDestination("http://192.168.1.1/router-admin"));
    }

    @Test
    void rejectsPrivateRfc1918OneSeventyTwoNetwork() {
        assertThrows(BusinessException.class,
                () -> EgressUrlGuard.assertSafeDestination("http://172.16.0.1/internal"));
        // Boundary just outside the 172.16.0.0/12 range must still be allowed
        // through the guard's private-range check (though it may still be a
        // real public address, which is fine — this just isn't RFC1918).
        assertDoesNotThrow(() -> EgressUrlGuard.assertSafeDestination("http://172.32.0.1/somewhere"));
    }

    @Test
    void rejectsLinkLocalAddress() {
        assertThrows(BusinessException.class,
                () -> EgressUrlGuard.assertSafeDestination("http://169.254.1.1/somewhere-else"));
    }

    @Test
    void rejectsCgnatRange() {
        assertThrows(BusinessException.class,
                () -> EgressUrlGuard.assertSafeDestination("http://100.64.0.1/internal"));
    }
}
