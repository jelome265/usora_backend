package com.usora.notification.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * F-009 remediation item 6: reject known placeholder strings and insecure
 * schemes when the "prod" profile is active, as a defense-in-depth backstop
 * -- not a replacement for removing unsafe fallback defaults from
 * application-prod.yml (already done alongside this class), but a check
 * that still catches the case an operator *explicitly* (if mistakenly)
 * sets DB_HOST=localhost, reuses the default password, or points
 * tenant-service at a loopback address in a real production deployment,
 * which removing yml fallbacks alone cannot catch.
 *
 * Only active under the "prod" profile so this never affects local/dev
 * usage -- see application-dev.yml, which legitimately uses these same
 * values (localhost Postgres/Kafka/Redis/tenant-service, in-repo default
 * password) for a real local Postgres/Kafka/Redis instance a developer is
 * expected to have running.
 */
@Slf4j
@Component
@Profile("prod")
public class ConfigSanityCheck {

    private static final String KNOWN_PLACEHOLDER_PASSWORD = "usora_secret";

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @Value("${spring.datasource.password:}")
    private String datasourcePassword;

    @Value("${grpc.client.tenant-service.address:}")
    private String tenantServiceAddress;

    @Value("${spring.kafka.bootstrap-servers:}")
    private String kafkaBootstrapServers;

    @Value("${spring.data.redis.host:}")
    private String redisHost;

    // F-011: this service no longer signs/verifies its own tokens with an
    // HMAC secret -- it now verifies against identity-service's RS256/JWKS
    // endpoint, the same as core-service/audit-service. The check this
    // field used to support (security.jwt.secret blank) no longer applies
    // -- that property doesn't exist anymore -- so this now checks that
    // the JWKS URI is actually configured and non-loopback instead. See
    // the loopback check below, which already covers this via
    // containsLoopback(jwkSetUri).
    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}")
    private String jwkSetUri;

    @PostConstruct
    public void validate() {
        List<String> problems = new ArrayList<>();

        if (containsLoopback(datasourceUrl)) {
            problems.add("spring.datasource.url resolves to a loopback host (" + redact(datasourceUrl)
                    + ") -- DB_HOST must point at the real production database");
        }
        if (KNOWN_PLACEHOLDER_PASSWORD.equals(datasourcePassword)) {
            problems.add("spring.datasource.password is still the in-repo placeholder value "
                    + "(\"" + KNOWN_PLACEHOLDER_PASSWORD + "\") -- DB_PASSWORD must be set from a real secret");
        }
        if (containsLoopback(tenantServiceAddress)) {
            problems.add("grpc.client.tenant-service.address resolves to a loopback host ("
                    + redact(tenantServiceAddress) + ") -- TENANT_SERVICE_HOST must point at the real service");
        }
        if (containsLoopback(kafkaBootstrapServers)) {
            problems.add("spring.kafka.bootstrap-servers resolves to a loopback host ("
                    + redact(kafkaBootstrapServers) + ") -- KAFKA_BOOTSTRAP_SERVERS must point at the real cluster");
        }
        if (containsLoopback(redisHost)) {
            problems.add("spring.data.redis.host resolves to a loopback host (" + redact(redisHost)
                    + ") -- REDIS_HOST must point at the real cache");
        }
        if (jwkSetUri.isBlank()) {
            problems.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri is blank -- "
                    + "JWK_SET_URI must be set (or the property default must resolve to a real endpoint)");
        } else if (containsLoopback(jwkSetUri)) {
            problems.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri resolves to a loopback host ("
                    + redact(jwkSetUri) + ") -- JWK_SET_URI must point at the real identity-service");
        }

        if (!problems.isEmpty()) {
            String message = "Refusing to start with 'prod' profile active and unsafe configuration "
                    + "detected (F-009):\n  - " + String.join("\n  - ", problems);
            log.error(message);
            throw new IllegalStateException(message);
        }

        log.info("Config sanity check passed: no loopback endpoints or placeholder secrets detected "
                + "in production configuration.");
    }

    private static boolean containsLoopback(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase();
        return lower.contains("localhost") || lower.contains("127.0.0.1") || lower.contains("://0.0.0.0");
    }

    /**
     * Never log the full resolved value of anything that might carry
     * embedded credentials (JDBC URLs, broker lists can technically carry
     * SASL config) -- just enough to identify which loopback host matched,
     * per the observability boundary rule against logging secrets.
     */
    private static String redact(String value) {
        if (value.toLowerCase().contains("localhost")) {
            return "...localhost...";
        }
        if (value.contains("127.0.0.1")) {
            return "...127.0.0.1...";
        }
        return "...0.0.0.0...";
    }
}
