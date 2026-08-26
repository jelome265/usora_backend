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
}

impl Default for RateLimitingConfig {
    fn default() -> Self {
        Self { default_rps: 100, burst_size: 200, window_ms: 1000 }
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
}

impl Default for UpstreamConfig {
    fn default() -> Self {
        Self {
            orchestrator_url: "https://orchestrator:9090".into(),
            compute_url: "https://compute:9090".into(),
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
    /// Expected `aud` claim. identity-service currently issues
    /// client-credentials tokens with a per-tenant audience (the tenant
    /// name) rather than one static value shared by every tenant, so this is
    /// intentionally left unenforced (empty) unless explicitly configured --
    /// see the comment on JwtValidator construction in AppState::new for why
    /// forcing a single static audience here would incorrectly reject valid
    /// tokens for every tenant but one. Set JWT_AUDIENCE only if/when
    /// identity-service is changed to issue a single shared audience.
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
            audience: String::new(),
            jwks_refresh_secs: 300,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[allow(unused)]
pub struct Config {
    pub bind_address: String,
    pub grpc_bind_address: String,
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

impl Config {
    pub fn from_env() -> anyhow::Result<Self> {
        let mut cfg = Config::default();

        if let Ok(v) = std::env::var("BIND_ADDRESS") {
            cfg.bind_address = v;
        }
        if let Ok(v) = std::env::var("GRPC_BIND_ADDRESS") {
            cfg.grpc_bind_address = v;
        }
        if let Ok(v) = std::env::var("TLS_CERT_PATH") {
            cfg.tls.cert_path = v;
        }
        if let Ok(v) = std::env::var("TLS_KEY_PATH") {
            cfg.tls.key_path = v;
        }
        if let Ok(v) = std::env::var("TLS_MIN_VERSION") {
            cfg.tls.min_version = v;
        }
        if let Ok(v) = std::env::var("TLS_REQUIRE_CLIENT_AUTH") {
            cfg.tls.require_client_auth = v.parse().unwrap_or(false);
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

        Ok(cfg)
    }

    pub fn tls_min_version(&self) -> &'static rustls::SupportedProtocolVersion {
        match self.tls.min_version.to_lowercase().as_str() {
            "tlsv1.2" => &rustls::version::TLS12,
            "tlsv1.3" => &rustls::version::TLS13,
            _ => &rustls::version::TLS12,
        }
    }

    pub fn load_tls_config(&self) -> anyhow::Result<rustls::ServerConfig> {
        let mut cert_reader = std::io::BufReader::new(std::fs::File::open(&self.tls.cert_path)?);
        let certs = rustls_pemfile::certs(&mut cert_reader)
            .collect::<Result<Vec<_>, _>>()?;
        let mut key_reader = std::io::BufReader::new(std::fs::File::open(&self.tls.key_path)?);
        let key = rustls_pemfile::private_key(&mut key_reader)?
            .ok_or_else(|| anyhow::anyhow!("no private key found"))?;

        let builder = rustls::ServerConfig::builder_with_protocol_versions(&[self.tls_min_version()]);

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
