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
 * private RFC1918 ranges — which is a classic SSRF vector.
 *
 * F-022 CORRECTION: this class's own comment previously claimed this
 * check alone defends against DNS rebinding ("re-resolves the hostname at
 * call time ... so a DNS-rebinding attack ... is also caught"). That
 * claim was not actually true as implemented: this method performs one
 * DNS resolution to validate, and {@code RestClient} then makes a
 * SEPARATE, independent call to {@code webClient.post().uri(url)...},
 * which triggers Netty's own, later, second DNS resolution when it
 * actually opens the connection. An attacker who controls the DNS record
 * for their webhook's hostname (a very short TTL is all that's needed)
 * can have it resolve to a safe public address for THIS check, then
 * repoint it to an internal address before Netty's own resolution moments
 * later — the classic check-then-connect TOCTOU gap that "DNS rebinding"
 * specifically describes. This method is still valuable as an early,
 * fast, clear-error-message validation (rejecting obviously-bad URLs
 * before even attempting a connection, and covering scheme/host format
 * validation this class also does), but it cannot be the sole,
 * authoritative enforcement point.
 *
 * The actual, race-condition-free enforcement now lives in
 * {@link SsrfSafeAddressResolverGroup}, which performs this exact same
 * check (see {@link #isDisallowedAddress}) at the point Netty resolves an
 * address to actually connect to — meaning there is no gap between "the
 * address that was validated" and "the address that was connected to",
 * because they are the same resolution. See WebClientConfig for how that
 * resolver is wired into the WebClient every outbound call in this
 * service uses.
 *
 * Both layers together are still defense-in-depth, not a replacement for
 * routing tenant-destined egress through an isolated network proxy with
 * no route to internal infrastructure at all (remediation item 1) --
 * that network-level control is infrastructure work outside what this
 * service's own code can provide, and is not implemented here.
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

    /**
     * F-022: exposed (was private) so {@link SsrfSafeAddressResolverGroup}
     * can reuse the exact same blocklist logic at actual connection time,
     * not just at this early validation step. See that class's javadoc
     * for why this method alone is necessary but not sufficient.
     */
    public static boolean isDisallowedAddress(InetAddress addr) {
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
