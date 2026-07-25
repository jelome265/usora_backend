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
        Ok(())
    }
}
