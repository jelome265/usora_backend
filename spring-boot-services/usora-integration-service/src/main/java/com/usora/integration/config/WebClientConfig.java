package com.usora.integration.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                // F-022: pins DNS resolution to the same SSRF-blocklist
                // check EgressUrlGuard performs, at the point Netty
                // actually opens the connection -- see
                // SsrfSafeAddressResolverGroup's javadoc for why this is
                // required to actually close the DNS-rebinding gap, not
                // just EgressUrlGuard.assertSafeDestination's earlier,
                // separate check.
                .resolver(com.usora.integration.util.SsrfSafeAddressResolverGroup.INSTANCE)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(30, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS)))
                .wiretap(false);

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        // F-022: previously returned a completely unconfigured builder
        // with no connector at all -- unused anywhere in this codebase
        // today, but a future caller autowiring WebClient.Builder to
        // build their own client would silently get NEITHER the
        // SSRF-safe resolver NOR any timeout configuration, with no
        // obvious signal that either was missing. Configured to match
        // the primary webClient() bean so any future consumer of this
        // builder is protected by default rather than needing to
        // remember to add it.
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .resolver(com.usora.integration.util.SsrfSafeAddressResolverGroup.INSTANCE)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(30, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS)))
                .wiretap(false);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
