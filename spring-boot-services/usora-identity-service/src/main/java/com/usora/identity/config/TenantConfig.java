package com.usora.identity.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "identity")
public class TenantConfig {

    private Jwt jwt = new Jwt();
    private Mfa mfa = new Mfa();
    private Opa opa = new Opa();
    private BruteForce bruteForce = new BruteForce();
    private Session session = new Session();

    @Data
    public static class Jwt {
        private String algorithm = "RS256";
        private long accessTokenTtl = 900;
        private long refreshTokenTtl = 604800;
        private long rotationWindow = 86400;
        private int keySize = 2048;
    }

    @Data
    public static class Mfa {
        private List<String> requiredRoles = List.of("admin", "compliance_officer");
        private boolean adaptiveEnabled = true;
        private List<String> providers = List.of("totp", "webauthn", "sms_backup");
    }

    @Data
    public static class Opa {
        private String url = "http://opa.usora.svc.cluster.local:8181";
        private String policyBundlePath = "/policies";
        private long decisionCacheTtl = 60;
        private long timeoutMs = 500;
    }

    @Data
    public static class BruteForce {
        private int maxAttempts = 5;
        private int windowSeconds = 300;
        private int lockoutDuration = 900;
    }

    @Data
    public static class Session {
        private Duration ttl = Duration.ofHours(24);
        private boolean deviceFingerprintEnabled = true;
        private boolean ipSubnetBinding = true;
    }
}
