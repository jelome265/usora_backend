package com.usora.notification.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.concurrent.Executors;

/**
 * F-007: the {@code grpc.client.tenant-service.negotiation-type} property in
 * application.yml / application-dev.yml / application-prod.yml (plaintext
 * for dev, tls for prod) was dead configuration -- this hand-rolled
 * {@link ManagedChannel} bean, which is what {@code GrpcClient} actually
 * gets autowired with, ignored it completely and called
 * {@code .usePlaintext()} unconditionally. That meant every environment,
 * including production, ran this channel in plaintext regardless of the
 * profile-specific YAML, which only ever fed a *different*,
 * never-instantiated channel that net.devh's grpc-spring-boot-starter would
 * have auto-configured had this bean not existed to override/shadow it.
 *
 * This bean now actually reads that same property and enforces it: TLS
 * unless explicitly configured otherwise, and a hard startup failure if a
 * "prod" profile is active with plaintext configured, per remediation item
 * 5 ("optional development plaintext mode only behind a development
 * profile that cannot load in production").
 */
@Slf4j
@Configuration
public class GrpcConfig {

    @Value("${grpc.tenant-service.host:localhost}")
    private String tenantServiceHost;

    @Value("${grpc.tenant-service.port:9090}")
    private int tenantServicePort;

    @Value("${grpc.client.tenant-service.negotiation-type:tls}")
    private String negotiationType;

    private final Environment environment;

    public GrpcConfig(Environment environment) {
        this.environment = environment;
    }

    @Bean
    public ManagedChannel tenantServiceChannel() {
        boolean plaintext = "plaintext".equalsIgnoreCase(negotiationType);
        boolean prodProfileActive = java.util.Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> p.equalsIgnoreCase("prod") || p.equalsIgnoreCase("production"));

        if (plaintext && prodProfileActive) {
            throw new IllegalStateException(
                    "grpc.client.tenant-service.negotiation-type is 'plaintext' while a 'prod'/"
                            + "'production' Spring profile is active. Refusing to start: internal gRPC "
                            + "traffic to tenant-service must not run in plaintext in production "
                            + "(F-007). Set negotiation-type to 'tls' (see application-prod.yml) or "
                            + "remove the prod profile if this is genuinely a non-production environment."
            );
        }

        if (plaintext) {
            log.warn("tenant-service gRPC channel is configured for PLAINTEXT (negotiation-type={}). " +
                    "This is only acceptable in local development.", negotiationType);
        }

        var builder = ManagedChannelBuilder.forAddress(tenantServiceHost, tenantServicePort)
                .executor(Executors.newVirtualThreadPerTaskExecutor());

        if (plaintext) {
            builder.usePlaintext();
        } else {
            // Uses the JVM/OS default trust store to verify tenant-service's
            // certificate -- sufficient for a cert-manager/service-mesh
            // issued certificate chained to a trusted (e.g. cluster-internal
            // CA already installed in the pod's trust store). If a custom,
            // non-system-trusted CA is ever needed here, this needs a
            // proper SslContext built via GrpcSslContexts + a configured CA
            // path (see the gateway's TlsConfig for the equivalent pattern)
            // -- not attempted here without knowing tenant-service's actual
            // certificate provisioning in this environment.
            builder.useTransportSecurity();
        }

        return builder.build();
    }
}
