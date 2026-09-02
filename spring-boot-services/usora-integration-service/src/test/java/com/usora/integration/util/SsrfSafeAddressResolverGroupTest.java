package com.usora.integration.util;

import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import io.netty.resolver.AddressResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F-022 regression test: {@link EgressUrlGuard#assertSafeDestination}
 * alone validates one DNS resolution and then lets Netty perform a
 * SEPARATE, later resolution when actually connecting -- a classic
 * check-then-connect TOCTOU gap that a DNS-rebinding attacker can win.
 * {@link SsrfSafeAddressResolverGroup} closes that gap by performing the
 * exact same check at the resolution Netty actually connects with, so
 * there is no second, independent lookup for an attacker to race.
 *
 * This test exercises the resolver directly against loopback/private
 * literal addresses (not a real hostname lookup, which would need
 * network access and DNS control this test doesn't have) -- resolving a
 * literal IP still goes through this resolver's validation logic exactly
 * the same way a hostname resolution would, since Netty's own default
 * resolver handles both the same way once the address reaches
 * InetSocketAddress.
 */
class SsrfSafeAddressResolverGroupTest {

    private static EventExecutor executor;

    @BeforeAll
    static void setUp() {
        executor = new DefaultEventExecutor();
    }

    @AfterAll
    static void tearDown() {
        executor.shutdownGracefully();
    }

    @Test
    void rejectsLoopbackAddressAtResolutionTime() throws Exception {
        AddressResolver<InetSocketAddress> resolver =
                SsrfSafeAddressResolverGroup.INSTANCE.getResolver(executor);

        var future = resolver.resolve(InetSocketAddress.createUnresolved("127.0.0.1", 443));
        assertFalse(future.await(5, TimeUnit.SECONDS).isSuccess(),
                "resolving a loopback address must fail, not silently succeed and let Netty connect to it");
    }

    @Test
    void rejectsMetadataEndpointAddressAtResolutionTime() throws Exception {
        AddressResolver<InetSocketAddress> resolver =
                SsrfSafeAddressResolverGroup.INSTANCE.getResolver(executor);

        var future = resolver.resolve(InetSocketAddress.createUnresolved("169.254.169.254", 80));
        assertFalse(future.await(5, TimeUnit.SECONDS).isSuccess(),
                "resolving the cloud metadata endpoint address must fail");
    }

    @Test
    void rejectsPrivateRfc1918AddressAtResolutionTime() throws Exception {
        AddressResolver<InetSocketAddress> resolver =
                SsrfSafeAddressResolverGroup.INSTANCE.getResolver(executor);

        var future = resolver.resolve(InetSocketAddress.createUnresolved("10.0.0.5", 443));
        assertFalse(future.await(5, TimeUnit.SECONDS).isSuccess(),
                "resolving a private RFC1918 address must fail");
    }

    @Test
    void allowsOrdinaryPublicAddress() throws Exception {
        AddressResolver<InetSocketAddress> resolver =
                SsrfSafeAddressResolverGroup.INSTANCE.getResolver(executor);

        // 8.8.8.8 (a well-known public IP) is used as a literal address
        // here specifically so this test doesn't depend on real DNS
        // resolution succeeding in a sandboxed/offline CI environment --
        // the point is only to confirm a genuinely public address is not
        // rejected by EgressUrlGuard.isDisallowedAddress's own logic.
        InetAddress publicAddr = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        assertFalse(EgressUrlGuard.isDisallowedAddress(publicAddr),
                "sanity check: a well-known public address must not be flagged as disallowed");
    }
}
