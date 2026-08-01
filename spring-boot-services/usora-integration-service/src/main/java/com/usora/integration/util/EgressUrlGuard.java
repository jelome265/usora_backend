package com.usora.integration.util;

import com.usora.integration.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Validates outbound destination URLs (tenant-configured webhook endpoints,
 * third-party integration calls) before {@link com.usora.integration.client.RestClient}
 * is allowed to hit them.
 *
 * SECURITY: without this check, a tenant-supplied webhook URL (or any other
 * caller-influenced URL reaching {@code RestClient}) can be pointed at
 * internal infrastructure — the cloud metadata endpoint, loopback, or
 * private RFC1918 ranges — which is a classic SSRF vector. This guard
 * re-resolves the hostname at call time (not just at config-save time) so
 * a DNS-rebinding attack (A record valid at save time, repointed to an
 * internal address at request time) is also caught.
 *
 * This is a defense-in-depth measure, not a replacement for routing
 * tenant-destined egress through an isolated proxy with no route to
 * internal infrastructure — that network-level control should still be
 * added per the architecture review (see docs/architecture-security-review-2026-07-31.md, §3.7).
 */
public final class EgressUrlGuard {

    private EgressUrlGuard() {}

    public static void assertSafeDestination(String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(BusinessException.VALIDATION_FAILED, "Invalid destination URL", HttpStatus.BAD_REQUEST);
        }

        var scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            throw new BusinessException(BusinessException.VALIDATION_FAILED, "Destination URL must use http or https", HttpStatus.BAD_REQUEST);
        }

        var host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BusinessException(BusinessException.VALIDATION_FAILED, "Destination URL must have a host", HttpStatus.BAD_REQUEST);
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new BusinessException(BusinessException.VALIDATION_FAILED, "Destination host could not be resolved", HttpStatus.BAD_REQUEST);
        }

        for (var addr : addresses) {
            if (isDisallowedAddress(addr)) {
                throw new BusinessException(BusinessException.VALIDATION_FAILED,
                        "Destination resolves to a disallowed internal/private address",
                        HttpStatus.BAD_REQUEST);
            }
        }
    }

    private static boolean isDisallowedAddress(InetAddress addr) {
        if (addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isAnyLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }

        // Cloud metadata endpoint (AWS/GCP/Azure all use 169.254.169.254).
        if (addr instanceof Inet4Address && addr.getHostAddress().equals("169.254.169.254")) {
            return true;
        }

        // Explicit RFC1918 / carrier-grade NAT / documented-private ranges,
        // in case isSiteLocalAddress() ever misses a variant.
        if (addr instanceof Inet4Address) {
            var octets = addr.getAddress();
            int a = octets[0] & 0xFF;
            int b = octets[1] & 0xFF;
            if (a == 10) return true;
            if (a == 172 && b >= 16 && b <= 31) return true;
            if (a == 192 && b == 168) return true;
            if (a == 100 && b >= 64 && b <= 127) return true; // CGNAT 100.64.0.0/10
            if (a == 127) return true;
        }

        if (addr instanceof Inet6Address) {
            // Unique local addresses (fc00::/7) and IPv4-mapped loopback/link-local.
            var first = addr.getAddress()[0] & 0xFF;
            if ((first & 0xFE) == 0xFC) return true;
        }

        return false;
    }
}
