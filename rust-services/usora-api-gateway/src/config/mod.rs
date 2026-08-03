use serde::Deserialize;

#[derive(Debug, Clone, Deserialize)]
#[allow(unused)]
pub struct TlsConfig {
    pub cert_path: String,
    pub key_path: String,
    pub min_version: String,
}

impl Default for TlsConfig {
    fn default() -> Self {
        Self {
            cert_path: "/etc/certs/tls.crt".into(),
            key_path: "/etc/certs/tls.key".into(),
            min_version: "TLSv1.2".into(),
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
pub struct Config {
    pub bind_address: String,
    pub grpc_bind_address: String,
    pub tls: TlsConfig,
    pub redis: RedisConfig,
    pub kafka: KafkaConfig,
    pub rate_limiting: RateLimitingConfig,
    pub upstream: UpstreamConfig,
    pub observability: ObservabilityConfig,
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
            upstream: UpstreamConfig::default(),
            observability: ObservabilityConfig::default(),
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

        let mut config = rustls::ServerConfig::builder_with_protocol_versions(&[self.tls_min_version()])
            .with_no_client_auth()
            .with_single_cert(certs, key)?;

        config.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];
        Ok(config)
    }
}
