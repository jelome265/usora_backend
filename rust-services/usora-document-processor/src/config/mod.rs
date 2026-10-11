use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub kafka_brokers: String,
    pub kafka_group_id: String,
    pub kafka_tasks_topic: String,
    pub kafka_results_topic: String,
    pub grpc_bind_address: String,
    pub redis_url: String,
    pub postgres_url: String,
    pub max_concurrent_jobs: usize,
    pub model_path: PathBuf,
    pub tesseract_data_path: PathBuf,
    pub temp_dir: PathBuf,
    pub otlp_endpoint: Option<String>,
    pub service_name: String,
    pub rest_bind_address: Option<String>,
    /// Shared HMAC secret used to verify internal-service bearer tokens
    /// on the REST API — see auth.rs. Required (no insecure default): a
    /// missing value fails validate() rather than silently accepting
    /// unauthenticated callers.
    pub internal_service_jwt_secret: Option<String>,
    /// PRE-EXISTING GAP, found and fixed while writing this service's Helm
    /// chart: kafka_dead_letter_topic/kafka_retry_count/
    /// kafka_retry_backoff_ms were already declared in the chart's
    /// values.yaml (topics.deadLetter, retryCount, retryBackoffMs) with
    /// no corresponding env var anywhere in this struct and no retry/DLQ
    /// logic anywhere in main.rs's Kafka consumer — meaning a message
    /// that failed processing (a corrupted image, a transient DB error,
    /// an OCR crash) was logged and then permanently lost, because
    /// `enable.auto.commit: true` committed its offset regardless of
    /// whether processing succeeded. See main.rs::run_kafka_consumer for
    /// the fix.
    pub kafka_dead_letter_topic: String,
    pub kafka_retry_count: u32,
    pub kafka_retry_backoff_ms: u64,
}

impl Config {
    pub fn from_env() -> anyhow::Result<Self> {
        Ok(Self {
            kafka_brokers: std::env::var("KAFKA_BROKERS")
                .unwrap_or_else(|_| "localhost:9092".to_string()),
            kafka_group_id: std::env::var("KAFKA_GROUP_ID")
                .unwrap_or_else(|_| "document-processor-group".to_string()),
            kafka_tasks_topic: std::env::var("KAFKA_TASKS_TOPIC")
                .unwrap_or_else(|_| "document.tasks".to_string()),
            kafka_results_topic: std::env::var("KAFKA_RESULTS_TOPIC")
                .unwrap_or_else(|_| "verification.results".to_string()),
            grpc_bind_address: std::env::var("GRPC_BIND_ADDRESS")
                .unwrap_or_else(|_| "0.0.0.0:50052".to_string()),
            redis_url: std::env::var("REDIS_URL")
                .unwrap_or_else(|_| "redis://127.0.0.1:6379".to_string()),
            postgres_url: std::env::var("DATABASE_URL")
                .unwrap_or_else(|_| "postgres://postgres:postgres@localhost:5432/usora".to_string()),
            max_concurrent_jobs: std::env::var("MAX_CONCURRENT_JOBS")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(4),
            model_path: std::env::var("MODEL_PATH")
                .map(PathBuf::from)
                .unwrap_or_else(|_| PathBuf::from("./models")),
            tesseract_data_path: std::env::var("TESSDATA_PREFIX")
                .map(PathBuf::from)
                .unwrap_or_else(|_| PathBuf::from("/usr/share/tessdata")),
            temp_dir: std::env::var("TEMP_DIR")
                .map(PathBuf::from)
                .unwrap_or_else(|_| PathBuf::from("./tmp")),
            otlp_endpoint: std::env::var("OTLP_ENDPOINT").ok(),
            service_name: std::env::var("SERVICE_NAME")
                .unwrap_or_else(|_| "usora-document-processor".to_string()),
            rest_bind_address: std::env::var("REST_BIND_ADDRESS").ok(),
            internal_service_jwt_secret: std::env::var("INTERNAL_SERVICE_JWT_SECRET").ok(),
            kafka_dead_letter_topic: std::env::var("KAFKA_DEAD_LETTER_TOPIC")
                .unwrap_or_else(|_| "document.dead.letter".to_string()),
            kafka_retry_count: std::env::var("KAFKA_RETRY_COUNT")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(3),
            kafka_retry_backoff_ms: std::env::var("KAFKA_RETRY_BACKOFF_MS")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(1000),
        })
    }

    pub fn validate(&self) -> anyhow::Result<()> {
        if self.kafka_brokers.is_empty() {
            anyhow::bail!("KAFKA_BROKERS must not be empty");
        }
        if self.grpc_bind_address.is_empty() {
            anyhow::bail!("GRPC_BIND_ADDRESS must not be empty");
        }
        if self.max_concurrent_jobs == 0 {
            anyhow::bail!("MAX_CONCURRENT_JOBS must be positive");
        }
        // SECURITY: the REST API performs document/biometric forensic
        // analysis and takes tenant_id from the request body — it MUST be
        // authenticated. Refuse to start rather than silently serve that
        // surface unauthenticated. See auth.rs for the full rationale.
        if self.internal_service_jwt_secret.as_deref().unwrap_or("").is_empty() {
            anyhow::bail!(
                "INTERNAL_SERVICE_JWT_SECRET must be set — the REST API cannot start \
                 without it, since it would otherwise serve document/biometric analysis \
                 endpoints with no authentication at all"
            );
        }
        Ok(())
    }
}
