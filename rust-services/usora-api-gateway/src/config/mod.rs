use serde::Deserialize;

#[derive(Debug, Clone, Deserialize)]
#[allow(unused)]
pub struct TlsConfig {
    pub cert_path: String,
    pub key_path: String,
    pub min_version: String,
    /// If true, the TLS handshake itself requires and verifies a client
    /// certificate against client_ca_path (mutual TLS). See
    /// auth::mtls::MtlsValidator, which implemented this verifier but was
    /// never wired into load_tls_config until now.
    pub require_client_auth: bool,
    /// CA bundle used to verify client certificates. Required (and
    /// validated at startup, see load_tls_config) when
    /// require_client_auth is true; ignored otherwise.
    pub client_ca_path: Option<String>,
}

impl Default for TlsConfig {
    fn default() -> Self {
        Self {
            cert_path: "/etc/certs/tls.crt".into(),
            key_path: "/etc/certs/tls.key".into(),
            // SECURITY: was "TLSv1.2". TLS 1.3 removes a number of
            // long-deprecated, weak cipher suites and handshake modes (CBC
            // padding-oracle-prone ciphers, static RSA key exchange with no
            // forward secrecy, renegotiation) that 1.2 still permits
            // depending on suite negotiation. Default to the stronger floor;
            // TLS_MIN_VERSION can still override back to "TLSv1.2" for a
            // client that genuinely can't be upgraded.
            min_version: "TLSv1.3".into(),
            require_client_auth: false,
            client_ca_path: None,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[allow(unused)]
pub struct RedisConfig {
    pub url: String,
    pub pool_size: u32,
}

impl Default for RedisConfig {
    fn default() -> Self {
        Self {
            url: "redis://127.0.0.1:6379".into(),
            pool_size: 10,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[allow(unused)]
pub struct KafkaConfig {
    pub brokers: String,
    pub topic_prefix: String,
}

impl Default for KafkaConfig {
    fn default() -> Self {
        Self {
            brokers: "127.0.0.1:9092".into(),
            topic_prefix: "usora".into(),
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[allow(unused)]
pub struct RateLimitingConfig {
    pub default_rps: u64,
    pub burst_size: u64,
    pub window_ms: u64,
    /// F-006: when Redis is unavailable -- either never connected at
    /// startup (AppState::new) or a command genuinely fails mid-request
    /// -- the gateway falls back to a per-pod, in-memory rate limiter.
    /// That limiter has no visibility into any other replica, so an
    /// attacker who knows (or discovers) Redis is down can distribute
    /// requests across replicas to multiply their effective budget by
    /// roughly the replica count. This divisor scales the per-pod local
    /// ceiling down (default_rps/burst_size divided by this value) so a
    /// single pod's fallback quota is deliberately conservative rather
    /// than the full intended *global* budget. Default of 3 matches this
    /// service's Helm chart minReplicas (infrastructure/helm/usora-gateway/
    /// values.yaml) -- i.e. the worst case (all traffic pinned to the
    /// minimum replica count) still can't exceed the intended global rate
    /// by more than that factor. This does not fully solve the finding
    /// (it does not distinguish cheap vs. expensive routes, item 4 of the
    /// remediation plan), but it closes the "full per-pod budget on every
    /// replica" multiplication the audit specifically calls out.
    pub local_fallback_divisor: u64,
}

impl Default for RateLimitingConfig {
    fn default() -> Self {
        Self { default_rps: 100, burst_size: 200, window_ms: 1000, local_fallback_divisor: 3 }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct CorsConfig {
    /// Explicit allowlist of origins permitted to make cross-origin
    /// requests to this API. SECURITY: this must never default to a
    /// wildcard — see docs/USORA-BACKEND-ENTERPRISE-AUDIT-2026-08-16.md
    /// finding C5. An empty list means no cross-origin browser access is
    /// permitted (server-to-server / same-origin callers are unaffected).
    pub allowed_origins: Vec<String>,
}

impl Default for CorsConfig {
    fn default() -> Self {
        // Intentionally empty by default: a fresh/misconfigured environment
        // must fail closed (no cross-origin access) rather than fail open
        // (any origin). Set CORS_ALLOWED_ORIGINS explicitly per environment.
        Self { allowed_origins: Vec::new() }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[allow(unused)]
pub struct UpstreamConfig {
    pub orchestrator_url: String,
    pub compute_url: String,
    /// CA bundle used to verify the orchestrator/compute upstreams' server
    /// certificates. Without this, these URLs default to "https://" but
    /// GrpcClients::connect never actually configured any TLS transport for
    /// them (tonic's default Channel has no TLS backend enabled unless you
    /// call .tls_config() -- the https:// scheme alone does nothing), so
    /// the channel was, despite its URL, unauthenticated and either
    /// plaintext or simply broken depending on what was actually listening
    /// on the other end. See GrpcClients::connect for how this is used.
    pub tls_ca_path: Option<String>,
    /// Shared bearer token attached as `authorization: Bearer <token>` on
    /// every outbound call to the orchestrator/compute upstreams, so those
    /// services can at least authenticate that a call came from this
    /// gateway (rather than any host that can reach their gRPC port) until
    /// per-service mTLS client identities are set up. Optional: if unset,
    /// no interceptor is attached and calls go out exactly as before.
    pub internal_service_token: Option<String>,
}

impl Default for UpstreamConfig {
    fn default() -> Self {
        Self {
            orchestrator_url: "https://orchestrator:9090".into(),
            compute_url: "https://compute:9090".into(),
            tls_ca_path: None,
            internal_service_token: None,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[allow(unused)]
pub struct ObservabilityConfig {
    pub otlp_endpoint: String,
    pub service_name: String,
}

impl Default for ObservabilityConfig {
    fn default() -> Self {
        Self {
            otlp_endpoint: "http://127.0.0.1:4317".into(),
            service_name: "usora-api-gateway".into(),
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[allow(unused)]
pub struct IdentityConfig {
    /// OIDC JWKS endpoint of usora-identity-service, e.g.
    /// "https://usora-identity-service:8081/oauth2/jwks" in a real
    /// deployment. AppState::new fetches this at startup and logs loudly
    /// (but does not abort startup) if it's unreachable -- see the comment
    /// there for why.
    pub jwks_url: String,
    /// Expected `iss` claim. Must match identity-service's own OAUTH2_ISSUER
    /// (see spring-boot-services/usora-identity-service's SecurityConfig).
    pub issuer: String,
    /// Expected `aud` claim. F-004: identity-service now issues every token,
    /// across all four grant types and both its OAuth2-authorization-server
    /// path and its manual JWTClaimsSet path, with the same stable service
    /// audience ("usora-api") rather than the tenant-specific value it used
    /// to set only for client_credentials tokens. That means this can now
    /// default to a real, non-empty value and be enforced unconditionally
    /// instead of being treated as optional -- see the (now removed) `if
    /// audience.is_empty() { None }` branch this used to require in
    /// AppState::new. Override with JWT_AUDIENCE only if identity-service's
    /// audience value changes; do not blank it out to disable enforcement.
    pub audience: String,
    /// How often to re-fetch the JWKS in the background, so identity-service
    /// key rotation is picked up without a gateway restart.
    pub jwks_refresh_secs: u64,
}

impl Default for IdentityConfig {
    fn default() -> Self {
        Self {
            jwks_url: "http://localhost:8081/oauth2/jwks".into(),
            issuer: "http://localhost:8081".into(),
            audience: "usora-api".into(),
            jwks_refresh_secs: 300,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[allow(unused)]
pub struct Config {
    pub bind_address: String,
    pub grpc_bind_address: String,
    /// F-007: deployment environment label ("development", "staging",
    /// "production"), used solely by the validation at the end of
    /// from_env() to fail startup when production-unsafe settings (no
    /// mTLS, no upstream TLS, no internal service token) are combined
    /// with a production environment. Defaults to "development" so an
    /// unset ENVIRONMENT never *silently* becomes stricter than before;
    /// every real deployment must set ENVIRONMENT=production explicitly
    /// to get the new fail-closed checks, the same way Helm charts in
    /// this repo already set an explicit Spring profile per environment.
    pub environment: String,
    pub tls: TlsConfig,
    pub redis: RedisConfig,
    pub kafka: KafkaConfig,
    pub rate_limiting: RateLimitingConfig,
    pub cors: CorsConfig,
    pub upstream: UpstreamConfig,
    pub observability: ObservabilityConfig,
    pub identity: IdentityConfig,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            bind_address: "0.0.0.0:8443".into(),
            grpc_bind_address: "0.0.0.0:9090".into(),
            environment: "development".into(),
            tls: TlsConfig::default(),
            redis: RedisConfig::default(),
            kafka: KafkaConfig::default(),
            rate_limiting: RateLimitingConfig::default(),
            cors: CorsConfig::default(),
            upstream: UpstreamConfig::default(),
            observability: ObservabilityConfig::default(),
            identity: IdentityConfig::default(),
        }
    }
}

/// F-009: shared loopback detection for the production config-sanity check
/// above. Kept simple (substring match) deliberately -- this only ever
/// runs against this gateway's own known config *keys*, not arbitrary
/// user input, so false positives (e.g. a hostname that legitimately
/// contains "localhost" as a substring) are an acceptable, very unlikely
/// tradeoff against the alternative of a silent miss from a stricter
/// parser rejecting a URL shape it doesn't recognize.
fn is_loopback_url(value: &str) -> bool {
    let lower = value.to_lowercase();
    lower.contains("localhost") || lower.contains("127.0.0.1") || lower.contains("://0.0.0.0")
}

impl Config {
    pub fn from_env() -> anyhow::Result<Self> {
        let mut cfg = Config::default();

        if let Ok(v) = std::env::var("BIND_ADDRESS") {
            cfg.bind_address = v;
        }
        if let Ok(v) = std::env::var("GRPC_BIND_ADDRESS") {
            cfg.grpc_bind_address = v;
        }
        if let Ok(v) = std::env::var("ENVIRONMENT") {
            cfg.environment = v;
        }
        if let Ok(v) = std::env::var("TLS_CERT_PATH") {
            cfg.tls.cert_path = v;
        }
        if let Ok(v) = std::env::var("TLS_KEY_PATH") {
            cfg.tls.key_path = v;
        }
        if let Ok(v) = std::env::var("TLS_MIN_VERSION") {
            // SECURITY (F-008): previously any unrecognized value here
            // (a typo like "TLSv1.3 " with trailing whitespace, "TLS1.3"
            // missing the dot, "SSLv3") was accepted here and only
            // discovered to be meaningless later in tls_min_version(),
            // which silently mapped every unrecognized string to TLS 1.2 --
            // quietly downgrading the floor below the intended TLS 1.3
            // default with no error at any point. Reject anything that
            // isn't one of the two versions this gateway actually supports,
            // right where the value is read, rather than let it reach
            // load_tls_config() having already been silently reinterpreted.
            let normalized = v.trim().to_lowercase();
            if normalized != "tlsv1.2" && normalized != "tlsv1.3" {
                anyhow::bail!(
                    "TLS_MIN_VERSION={v:?} is not a supported value (expected \"TLSv1.2\" or \
                     \"TLSv1.3\"). Refusing to start rather than silently falling back to TLS 1.2 -- \
                     see F-008."
                );
            }
            cfg.tls.min_version = v;
        }
        if let Ok(v) = std::env::var("TLS_REQUIRE_CLIENT_AUTH") {
            // SECURITY (F-008): `.parse().unwrap_or(false)` silently turned
            // ANY unparseable value -- a typo like "ture", an empty string
            // from a botched Helm template, "True" with capitalization
            // Rust's bool parser doesn't accept, anything -- into `false`,
            // i.e. mTLS silently OFF. A security-relevant boolean must
            // reject invalid input outright rather than quietly picking the
            // weaker of the two possible states.
            cfg.tls.require_client_auth = v.trim().parse().map_err(|_| {
                anyhow::anyhow!(
                    "TLS_REQUIRE_CLIENT_AUTH={v:?} is not a valid boolean (expected \"true\" or \
                     \"false\"). Refusing to start rather than silently treating an invalid value \
                     as \"false\" (mTLS disabled) -- see F-008."
                )
            })?;
        }
        if let Ok(v) = std::env::var("TLS_CLIENT_CA_PATH") {
            cfg.tls.client_ca_path = Some(v);
        }
        if let Ok(v) = std::env::var("REDIS_URL") {
            cfg.redis.url = v;
        }
        if let Ok(v) = std::env::var("REDIS_POOL_SIZE") {
            cfg.redis.pool_size = v.parse()?;
        }
        if let Ok(v) = std::env::var("KAFKA_BROKERS") {
            cfg.kafka.brokers = v;
        }
        if let Ok(v) = std::env::var("KAFKA_TOPIC_PREFIX") {
            cfg.kafka.topic_prefix = v;
        }
        if let Ok(v) = std::env::var("RATE_LIMIT_DEFAULT_RPS") {
            cfg.rate_limiting.default_rps = v.parse()?;
        }
        if let Ok(v) = std::env::var("RATE_LIMIT_BURST_SIZE") {
            cfg.rate_limiting.burst_size = v.parse()?;
        }
        if let Ok(v) = std::env::var("RATE_LIMIT_WINDOW_MS") {
            cfg.rate_limiting.window_ms = v.parse()?;
        }
        if let Ok(v) = std::env::var("RATE_LIMIT_LOCAL_FALLBACK_DIVISOR") {
            cfg.rate_limiting.local_fallback_divisor = v.parse()?;
        }
        if let Ok(v) = std::env::var("CORS_ALLOWED_ORIGINS") {
            cfg.cors.allowed_origins = v
                .split(',')
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty())
                .collect();
        }
        if let Ok(v) = std::env::var("UPSTREAM_ORCHESTRATOR_URL") {
            cfg.upstream.orchestrator_url = v;
        }
        if let Ok(v) = std::env::var("UPSTREAM_COMPUTE_URL") {
            cfg.upstream.compute_url = v;
        }
        if let Ok(v) = std::env::var("UPSTREAM_TLS_CA_PATH") {
            cfg.upstream.tls_ca_path = Some(v);
        }
        if let Ok(v) = std::env::var("INTERNAL_SERVICE_TOKEN") {
            cfg.upstream.internal_service_token = Some(v);
        }
        if let Ok(v) = std::env::var("OTLP_ENDPOINT") {
            cfg.observability.otlp_endpoint = v;
        }
        if let Ok(v) = std::env::var("SERVICE_NAME") {
            cfg.observability.service_name = v;
        }
        if let Ok(v) = std::env::var("IDENTITY_JWKS_URL") {
            cfg.identity.jwks_url = v;
        }
        if let Ok(v) = std::env::var("JWT_ISSUER") {
            cfg.identity.issuer = v;
        }
        if let Ok(v) = std::env::var("JWT_AUDIENCE") {
            cfg.identity.audience = v;
        }
        if let Ok(v) = std::env::var("JWKS_REFRESH_SECS") {
            cfg.identity.jwks_refresh_secs = v.parse()?;
        }

        cfg.validate_production_transport_security()?;

        Ok(cfg)
    }

    /// F-007: transport authentication (inbound mTLS, outbound TLS +
    /// service token to orchestrator/compute) was entirely opt-in --
    /// every one of these controls defaulted to off/unset, with no
    /// mechanism to stop a production deployment from silently running
    /// that way. Per remediation item 5 ("optional development plaintext
    /// mode only behind a development profile that cannot load in
    /// production"), a deployment that explicitly declares itself
    /// production must have all of these actually configured, or refuse
    /// to start rather than come up in a degraded-trust state.
    ///
    /// This does not implement full mTLS/SPIFFE (items 1-3, 6) -- it only
    /// makes the *existing* opt-in controls mandatory once an operator
    /// says "this is production", closing the gap where they could be
    /// silently left off in exactly the environment where that matters
    /// most.
    fn validate_production_transport_security(&self) -> anyhow::Result<()> {
        let is_production = matches!(
            self.environment.to_lowercase().as_str(),
            "production" | "prod"
        );
        if !is_production {
            return Ok(());
        }

        let mut problems: Vec<String> = Vec::new();

        if !self.tls.require_client_auth {
            problems.push(
                "TLS_REQUIRE_CLIENT_AUTH must be true in production (inbound mTLS is currently optional)".to_string()
            );
        }
        if self.upstream.tls_ca_path.is_none() {
            problems.push(
                "UPSTREAM_TLS_CA_PATH must be set in production (outbound calls to orchestrator/compute \
                 would otherwise have no TLS transport configured despite their https:// URLs)".to_string()
            );
        }
        if self.upstream.internal_service_token.is_none() {
            problems.push(
                "INTERNAL_SERVICE_TOKEN must be set in production (orchestrator/compute would otherwise \
                 accept calls from this gateway with no service-identity proof at all)".to_string()
            );
        }

        // F-009: this gateway's defaults for the identity JWKS endpoint,
        // OAuth2 issuer, and both upstream service URLs are all loopback
        // addresses (see IdentityConfig::default / UpstreamConfig::default)
        // -- reasonable so a bare `cargo run` works locally with nothing
        // configured, but if any of those defaults survive unnoticed into
        // an ENVIRONMENT=production deployment, the gateway starts up
        // "successfully" while actually pointed at nothing real. Reject
        // any of these that still resolve to a loopback host once
        // production is declared explicitly.
        for (name, value) in [
            ("IDENTITY_JWKS_URL", self.identity.jwks_url.as_str()),
            ("JWT_ISSUER", self.identity.issuer.as_str()),
            ("UPSTREAM_ORCHESTRATOR_URL", self.upstream.orchestrator_url.as_str()),
            ("UPSTREAM_COMPUTE_URL", self.upstream.compute_url.as_str()),
        ] {
            if is_loopback_url(value) {
                problems.push(format!(
                    "{name} still resolves to a loopback address in production -- it must point at the \
                     real service, not the built-in local-development default"
                ));
            }
        }

        if problems.is_empty() {
            Ok(())
        } else {
            anyhow::bail!(
                "ENVIRONMENT=production but required transport-security configuration is missing; \
                 refusing to start (F-007/F-009):\n  - {}",
                problems.join("\n  - ")
            );
        }
    }

    pub fn tls_min_version(&self) -> &'static rustls::SupportedProtocolVersion {
        match self.tls.min_version.to_lowercase().as_str() {
            "tlsv1.2" => Ok(&rustls::version::TLS12),
            "tlsv1.3" => Ok(&rustls::version::TLS13),
            other => anyhow::bail!(
                "tls.min_version={other:?} is not a supported TLS version (expected \"tlsv1.2\" or \
                 \"tlsv1.3\"). This should have been rejected by Config::from_env() already -- refusing \
                 to silently fall back to a weaker floor here."
            ),
        }
    }

    pub fn load_tls_config(&self) -> anyhow::Result<rustls::ServerConfig> {
        let mut cert_reader = std::io::BufReader::new(std::fs::File::open(&self.tls.cert_path)?);
        let certs = rustls_pemfile::certs(&mut cert_reader)
            .collect::<Result<Vec<_>, _>>()?;
        let mut key_reader = std::io::BufReader::new(std::fs::File::open(&self.tls.key_path)?);
        let key = rustls_pemfile::private_key(&mut key_reader)?
            .ok_or_else(|| anyhow::anyhow!("no private key found"))?;

        let builder = rustls::ServerConfig::builder_with_protocol_versions(&[self.tls_min_version()?]);

        // SECURITY: this used to be with_no_client_auth() unconditionally --
        // crate::auth::mtls::MtlsValidator implemented a working client
        // certificate verifier, but nothing ever called it, so the gateway
        // never actually performed mutual TLS at the handshake level despite
        // that module existing. Wiring it in here is opt-in
        // (TLS_REQUIRE_CLIENT_AUTH) rather than unconditional, since not
        // every deployment of this gateway necessarily has client
        // certificate infrastructure in place yet; when it's off, behavior
        // is unchanged from before.
        let mut config = if self.tls.require_client_auth {
            let ca_path = self.tls.client_ca_path.as_ref().ok_or_else(|| {
                anyhow::anyhow!(
                    "TLS_REQUIRE_CLIENT_AUTH is true but TLS_CLIENT_CA_PATH is not set -- refusing to \
                     start with mTLS requested but no CA to verify client certificates against."
                )
            })?;
            let verifier = crate::auth::mtls::MtlsValidator::new(ca_path)?.client_verifier()?;
            builder
                .with_client_cert_verifier(verifier)
                .with_single_cert(certs, key)?
        } else {
            builder.with_no_client_auth().with_single_cert(certs, key)?
        };

        config.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];
        Ok(config)
    }
}
