use anyhow::Result;
use serde::Deserialize;
use std::path::PathBuf;

#[derive(Debug, Clone, Deserialize)]
pub struct Config {
    pub kafka: KafkaConfig,
    pub grpc: GrpcConfig,
    pub redis: RedisConfig,
    pub postgres: PostgresConfig,
    pub models: ModelConfig,
    pub faiss: FaissConfig,
    pub processing: ProcessingConfig,
    pub matching: MatchingConfig,
    pub telemetry: TelemetryConfig,
}

#[derive(Debug, Clone, Deserialize)]
pub struct KafkaConfig {
    pub brokers: String,
    pub tasks_topic: String,
    pub results_topic: String,
    pub consumer_group: String,
    pub audit_topic: String,
    pub max_poll_interval_ms: u32,
    pub session_timeout_ms: u32,
}

#[derive(Debug, Clone, Deserialize)]
pub struct GrpcConfig {
    pub bind_address: String,
    pub max_message_size: usize,
    pub max_concurrent_streams: u32,
    pub keepalive_interval_secs: u64,
    pub keepalive_timeout_secs: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct RedisConfig {
    pub url: String,
    pub pool_size: u32,
    pub default_ttl_secs: u32,
}

#[derive(Debug, Clone, Deserialize)]
pub struct PostgresConfig {
    pub url: String,
    pub max_connections: u32,
    pub min_connections: u32,
    pub acquire_timeout_secs: u64,
    pub idle_timeout_secs: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ModelConfig {
    pub detection_model_path: PathBuf,
    pub embedding_model_path: PathBuf,
    pub liveness_model_path: Option<PathBuf>,
    pub min_face_size: u32,
    pub max_face_size: u32,
    pub embedding_dimension: usize,
    pub detection_confidence_threshold: f32,
    pub input_width: u32,
    pub input_height: u32,
}

#[derive(Debug, Clone, Deserialize)]
pub struct FaissConfig {
    pub index_path: PathBuf,
    pub index_type: String,
    pub dimension: usize,
    pub nlist: u32,
    pub nprobe: u32,
    pub ef_search: u32,
    pub ef_construction: u32,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ProcessingConfig {
    pub max_concurrent_jobs: usize,
    pub job_timeout_secs: u64,
    pub queue_capacity: usize,
    pub retry_max_attempts: u32,
    pub retry_base_delay_ms: u64,
    pub enable_batch_processing: bool,
    pub batch_size: usize,
}

#[derive(Debug, Clone, Deserialize)]
pub struct MatchingConfig {
    pub one_to_one_threshold: f64,
    pub one_to_many_threshold: f64,
    pub top_k_results: usize,
    pub score_normalization: bool,
    pub enable_score_calibration: bool,
    pub calibration_factor: f64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct TelemetryConfig {
    pub service_name: String,
    pub otlp_endpoint: Option<String>,
    pub metrics_port: u16,
    pub tracing_enabled: bool,
    pub metrics_enabled: bool,
    pub log_format: LogFormat,
    pub log_level: String,
}

#[derive(Debug, Clone, Deserialize)]
pub enum LogFormat {
    Json,
    Text,
}

impl Config {
    pub fn from_env() -> Result<Self> {
        Ok(Config {
            kafka: KafkaConfig {
                brokers: env_or_default("KAFKA_BROKERS", "localhost:9093"),
                tasks_topic: env_or_default("KAFKA_TASKS_TOPIC", "biometric.tasks"),
                results_topic: env_or_default("KAFKA_RESULTS_TOPIC", "verification.results"),
                consumer_group: env_or_default("KAFKA_CONSUMER_GROUP", "face-matching-engine"),
                audit_topic: env_or_default("KAFKA_AUDIT_TOPIC", "biometric.audit"),
                max_poll_interval_ms: env_or_default("KAFKA_MAX_POLL_INTERVAL_MS", "300000"),
                session_timeout_ms: env_or_default("KAFKA_SESSION_TIMEOUT_MS", "45000"),
            },
            grpc: GrpcConfig {
                bind_address: env_or_default("GRPC_BIND_ADDRESS", "[::1]:50053"),
                max_message_size: env_or_default("GRPC_MAX_MESSAGE_SIZE", "4194304"),
                max_concurrent_streams: env_or_default("GRPC_MAX_CONCURRENT_STREAMS", "100"),
                keepalive_interval_secs: env_or_default("GRPC_KEEPALIVE_INTERVAL_SECS", "30"),
                keepalive_timeout_secs: env_or_default("GRPC_KEEPALIVE_TIMEOUT_SECS", "10"),
            },
            redis: RedisConfig {
                url: env_or_default("REDIS_URL", "redis://localhost:6379"),
                pool_size: env_or_default("REDIS_POOL_SIZE", "10"),
                default_ttl_secs: env_or_default("REDIS_DEFAULT_TTL_SECS", "3600"),
            },
            postgres: PostgresConfig {
                url: env_or_default(
                    "POSTGRES_URL",
                    "postgres://postgres:postgres@localhost:5432/usora",
                ),
                max_connections: env_or_default("POSTGRES_MAX_CONNECTIONS", "20"),
                min_connections: env_or_default("POSTGRES_MIN_CONNECTIONS", "5"),
                acquire_timeout_secs: env_or_default("POSTGRES_ACQUIRE_TIMEOUT_SECS", "30"),
                idle_timeout_secs: env_or_default("POSTGRES_IDLE_TIMEOUT_SECS", "600"),
            },
            models: ModelConfig {
                detection_model_path: PathBuf::from(env_or_default::<String>(
                    "DETECTION_MODEL_PATH",
                    "models/face_detection.onnx",
                )),
                embedding_model_path: PathBuf::from(env_or_default::<String>(
                    "EMBEDDING_MODEL_PATH",
                    "models/face_embedding.onnx",
                )),
                liveness_model_path: Some(PathBuf::from(env_or_default::<String>(
                    "LIVENESS_MODEL_PATH",
                    "models/liveness.onnx",
                ))),
                min_face_size: env_or_default("MIN_FACE_SIZE", "40"),
                max_face_size: env_or_default("MAX_FACE_SIZE", "2000"),
                embedding_dimension: env_or_default("EMBEDDING_DIMENSION", "512"),
                detection_confidence_threshold: env_or_default(
                    "DETECTION_CONFIDENCE_THRESHOLD",
                    "0.7",
                ),
                input_width: env_or_default("MODEL_INPUT_WIDTH", "112"),
                input_height: env_or_default("MODEL_INPUT_HEIGHT", "112"),
            },
            faiss: FaissConfig {
                index_path: PathBuf::from(env_or_default::<String>(
                    "FAISS_INDEX_PATH",
                    "data/faiss.index",
                )),
                index_type: env_or_default("FAISS_INDEX_TYPE", "IVFFlat"),
                dimension: env_or_default("FAISS_DIMENSION", "512"),
                nlist: env_or_default("FAISS_NLIST", "100"),
                nprobe: env_or_default("FAISS_NPROBE", "10"),
                ef_search: env_or_default("FAISS_EF_SEARCH", "64"),
                ef_construction: env_or_default("FAISS_EF_CONSTRUCTION", "128"),
            },
            processing: ProcessingConfig {
                max_concurrent_jobs: env_or_default("MAX_CONCURRENT_JOBS", "8"),
                job_timeout_secs: env_or_default("JOB_TIMEOUT_SECS", "60"),
                queue_capacity: env_or_default("QUEUE_CAPACITY", "1000"),
                retry_max_attempts: env_or_default("RETRY_MAX_ATTEMPTS", "3"),
                retry_base_delay_ms: env_or_default("RETRY_BASE_DELAY_MS", "100"),
                enable_batch_processing: env_or_default("ENABLE_BATCH_PROCESSING", "true"),
                batch_size: env_or_default("BATCH_SIZE", "16"),
            },
            matching: MatchingConfig {
                one_to_one_threshold: env_or_default("ONE_TO_ONE_THRESHOLD", "0.85"),
                one_to_many_threshold: env_or_default("ONE_TO_MANY_THRESHOLD", "0.85"),
                top_k_results: env_or_default("TOP_K_RESULTS", "10"),
                score_normalization: env_or_default("SCORE_NORMALIZATION", "true"),
                enable_score_calibration: env_or_default("ENABLE_SCORE_CALIBRATION", "true"),
                calibration_factor: env_or_default("CALIBRATION_FACTOR", "1.0"),
            },
            telemetry: TelemetryConfig {
                service_name: env_or_default("OTEL_SERVICE_NAME", "usora-face-matching-engine"),
                otlp_endpoint: std::env::var("OTLP_ENDPOINT").ok(),
                metrics_port: env_or_default("METRICS_PORT", "9090"),
                tracing_enabled: env_or_default("TRACING_ENABLED", "true"),
                metrics_enabled: env_or_default("METRICS_ENABLED", "true"),
                log_format: if std::env::var("LOG_FORMAT").unwrap_or_default() == "text" {
                    LogFormat::Text
                } else {
                    LogFormat::Json
                },
                log_level: env_or_default("LOG_LEVEL", "info"),
            },
        })
    }
}

fn env_or_default<T: std::str::FromStr>(key: &str, default: &str) -> T {
    std::env::var(key)
        .unwrap_or_else(|_| default.to_string())
        .parse()
        .unwrap_or_else(|_| panic!("Invalid value for env var {}", key))
}

impl std::fmt::Display for Config {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "Config {{ kafka_brokers: {}, grpc_bind: {}, redis: {}, postgres: hidden }}",
            self.kafka.brokers, self.grpc.bind_address, self.redis.url
        )
    }
}
