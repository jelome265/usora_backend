package com.usora.integration.util;

import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

/**
 * F-022: closes the DNS-rebinding TOCTOU gap {@link EgressUrlGuard} alone
 * cannot close (see that class's javadoc for the full explanation).
 * Wired into the shared {@code WebClient}'s underlying Reactor Netty
 * {@code HttpClient} via {@code .resolver(...)} in WebClientConfig, so
 * this is the address resolver used for every outbound connection this
 * service makes -- meaning the address that gets validated here is
 * guaranteed to be the exact same address Netty then connects to, with no
 * separate, later re-resolution step for an attacker to race against.
 *
 * Delegates actual DNS resolution to Netty's own default resolver
 * (backed by {@code InetAddress}, the same mechanism {@link EgressUrlGuard}
 * itself uses, so both checks agree on what a hostname resolves to); this
 * class only adds a validation step on top of that delegate's result,
 * failing the resolution outright (so Netty never attempts to connect at
 * all) if every candidate address is disallowed.
 */
public final class SsrfSafeAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    public static final SsrfSafeAddressResolverGroup INSTANCE = new SsrfSafeAddressResolverGroup();

    private SsrfSafeAddressResolverGroup() {}

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) throws Exception {
        AddressResolver<InetSocketAddress> delegate = DefaultAddressResolverGroup.INSTANCE.getResolver(executor);
        return new SsrfSafeResolver(delegate, executor);
    }

    private static final class SsrfSafeResolver implements AddressResolver<InetSocketAddress> {

        private final AddressResolver<InetSocketAddress> delegate;
        private final EventExecutor executor;

        private SsrfSafeResolver(AddressResolver<InetSocketAddress> delegate, EventExecutor executor) {
            this.delegate = delegate;
            this.executor = executor;
        }

        @Override
        public boolean isSupported(SocketAddress address) {
            return delegate.isSupported(address);
        }

        @Override
        public boolean isResolved(SocketAddress address) {
            return delegate.isResolved(address);
        }

        @Override
        public Future<InetSocketAddress> resolve(SocketAddress address) {
            Promise<InetSocketAddress> promise = executor.newPromise();
            resolve(address, promise);
            return promise;
        }

        @Override
        public Future<InetSocketAddress> resolve(SocketAddress address, Promise<InetSocketAddress> promise) {
            Promise<InetSocketAddress> delegatePromise = executor.newPromise();
            delegatePromise.addListener((Future<InetSocketAddress> f) -> {
                if (!f.isSuccess()) {
                    promise.tryFailure(f.cause());
                    return;
                }
                InetSocketAddress resolved = f.getNow();
                if (resolved != null && resolved.getAddress() != null
                        && EgressUrlGuard.isDisallowedAddress(resolved.getAddress())) {
                    promise.tryFailure(new java.net.UnknownHostException(
                            "Destination resolves to a disallowed internal/private address: "
                                    + resolved.getAddress().getHostAddress()));
                } else {
                    promise.trySuccess(resolved);
                }
            });
            delegate.resolve(address, delegatePromise);
            return promise;
        }

        @Override
        public Future<List<InetSocketAddress>> resolveAll(SocketAddress address) {
            Promise<List<InetSocketAddress>> promise = executor.newPromise();
            resolveAll(address, promise);
            return promise;
        }

        @Override
        public Future<List<InetSocketAddress>> resolveAll(SocketAddress address, Promise<List<InetSocketAddress>> promise) {
            Promise<List<InetSocketAddress>> delegatePromise = executor.newPromise();
            delegatePromise.addListener((Future<List<InetSocketAddress>> f) -> {
                if (!f.isSuccess()) {
                    promise.tryFailure(f.cause());
                    return;
                }
                List<InetSocketAddress> resolved = f.getNow();
                // F-022: every candidate address must be safe -- if even one
                // resolved address is disallowed, fail the whole resolution
                // rather than silently filtering it out and connecting to a
                // remaining "safe-looking" one, since an attacker controlling
                // DNS could otherwise deliberately mix a decoy safe address
                // with the real internal target and rely on the caller only
                // checking that not-all addresses were bad.
                boolean anyDisallowed = resolved != null && resolved.stream()
                        .anyMatch(a -> a.getAddress() != null && EgressUrlGuard.isDisallowedAddress(a.getAddress()));
                if (anyDisallowed) {
                    promise.tryFailure(new java.net.UnknownHostException(
                            "Destination resolves to a disallowed internal/private address"));
                } else {
                    promise.trySuccess(resolved);
                }
            });
            delegate.resolveAll(address, delegatePromise);
            return promise;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
