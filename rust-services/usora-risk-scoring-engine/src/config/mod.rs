use crate::models::RiskThresholds;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServiceConfig {
    pub server: ServerConfig,
    pub kafka: KafkaConfig,
    pub redis: RedisConfig,
    pub postgres: PostgresConfig,
    pub models: ModelsConfig,
    pub thresholds: RiskThresholds,
    pub features: FeatureStoreConfig,
    pub explainability: ExplainabilityConfig,
    pub performance: PerformanceConfig,
    pub telemetry: TelemetryConfig,
}

impl Default for ServiceConfig {
    fn default() -> Self {
        Self {
            server: ServerConfig::default(),
            kafka: KafkaConfig::default(),
            redis: RedisConfig::default(),
            postgres: PostgresConfig::default(),
            models: ModelsConfig::default(),
            thresholds: RiskThresholds::default(),
            features: FeatureStoreConfig::default(),
            explainability: ExplainabilityConfig::default(),
            performance: PerformanceConfig::default(),
            telemetry: TelemetryConfig::default(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServerConfig {
    pub grpc_addr: String,
    pub http_addr: String,
    pub max_concurrent_requests: usize,
    pub request_timeout_ms: u64,
    pub shutdown_timeout_ms: u64,
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self {
            grpc_addr: "[::1]:50051".into(),
            http_addr: "[::1]:8080".into(),
            max_concurrent_requests: 1000,
            request_timeout_ms: 5000,
            shutdown_timeout_ms: 10000,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct KafkaConfig {
    pub brokers: String,
    pub group_id: String,
    pub risk_tasks_topic: String,
    pub risk_results_topic: String,
    pub consumer_threads: usize,
    pub auto_offset_reset: String,
    pub enable_auto_commit: bool,
    pub session_timeout_ms: u64,
    pub max_poll_interval_ms: u64,
}

impl Default for KafkaConfig {
    fn default() -> Self {
        Self {
            brokers: "localhost:9092".into(),
            group_id: "risk-scoring-engine".into(),
            risk_tasks_topic: "risk.tasks".into(),
            risk_results_topic: "risk.results".into(),
            consumer_threads: 4,
            auto_offset_reset: "earliest".into(),
            enable_auto_commit: true,
            session_timeout_ms: 30000,
            max_poll_interval_ms: 300000,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RedisConfig {
    pub url: String,
    pub connection_pool_size: u32,
    pub default_ttl_seconds: u64,
    pub max_retries: u32,
    pub retry_delay_ms: u64,
    pub key_prefix: String,
}

impl Default for RedisConfig {
    fn default() -> Self {
        Self {
            url: "redis://localhost:6379".into(),
            connection_pool_size: 16,
            default_ttl_seconds: 3600,
            max_retries: 3,
            retry_delay_ms: 100,
            key_prefix: "usora:risk:".into(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PostgresConfig {
    pub url: String,
    pub max_connections: u32,
    pub min_connections: u32,
    pub acquire_timeout_seconds: u64,
    pub idle_timeout_seconds: u64,
    pub schema: String,
}

impl Default for PostgresConfig {
    fn default() -> Self {
        Self {
            url: "postgres://postgres:postgres@localhost:5432/risk_scoring".into(),
            max_connections: 20,
            min_connections: 5,
            acquire_timeout_seconds: 30,
            idle_timeout_seconds: 600,
            schema: "risk_scoring".into(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelsConfig {
    pub applicant_risk: ModelConfig,
    pub transaction_risk: ModelConfig,
    pub hot_reload_interval_seconds: u64,
    pub inference_timeout_ms: u64,
    pub max_batch_size: usize,
}

impl Default for ModelsConfig {
    fn default() -> Self {
        Self {
            applicant_risk: ModelConfig {
                model_id: "applicant_risk".into(),
                model_path: "/models/applicant_risk_v5.onnx".into(),
                version: "5.2.1".into(),
                input_features: 256,
                output_classes: 4,
                class_labels: vec![
                    "low".into(),
                    "medium".into(),
                    "high".into(),
                    "critical".into(),
                ],
            },
            transaction_risk: ModelConfig {
                model_id: "transaction_risk".into(),
                model_path: "/models/transaction_risk_v3.onnx".into(),
                version: "3.1.0".into(),
                input_features: 128,
                output_classes: 4,
                class_labels: vec![
                    "low".into(),
                    "medium".into(),
                    "high".into(),
                    "critical".into(),
                ],
            },
            hot_reload_interval_seconds: 30,
            inference_timeout_ms: 2000,
            max_batch_size: 100,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelConfig {
    pub model_id: String,
    pub model_path: String,
    pub version: String,
    pub input_features: usize,
    pub output_classes: usize,
    pub class_labels: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeatureStoreConfig {
    pub store_type: String,
    pub ttl_seconds: u64,
    pub real_time_features: Vec<String>,
    pub batch_features: Vec<String>,
    pub cache_snapshot_max_age_seconds: u64,
    pub max_retries: u32,
}

impl Default for FeatureStoreConfig {
    fn default() -> Self {
        Self {
            store_type: "feast".into(),
            ttl_seconds: 3600,
            real_time_features: vec![
                "device_fingerprint".into(),
                "ip_reputation".into(),
                "behavioral_velocity".into(),
            ],
            batch_features: vec![
                "historical_fraud_rate".into(),
                "geographic_risk".into(),
                "watchlist_hits".into(),
            ],
            cache_snapshot_max_age_seconds: 3600,
            max_retries: 3,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExplainabilityConfig {
    pub enabled: bool,
    pub method: String,
    pub max_features: usize,
    pub min_feature_importance: f64,
}

impl Default for ExplainabilityConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            method: "shap".into(),
            max_features: 10,
            min_feature_importance: 0.01,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PerformanceConfig {
    pub max_latency_ms: u64,
    pub batch_size: usize,
    pub max_concurrent_per_tenant: usize,
    pub l1_cache_capacity: usize,
    pub l2_cache_ttl_seconds: u64,
}

impl Default for PerformanceConfig {
    fn default() -> Self {
        Self {
            max_latency_ms: 500,
            batch_size: 100,
            max_concurrent_per_tenant: 100,
            l1_cache_capacity: 10_000,
            l2_cache_ttl_seconds: 300,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TelemetryConfig {
    pub service_name: String,
    pub tracing_endpoint: Option<String>,
    pub metrics_endpoint: Option<String>,
    pub log_level: String,
    pub enable_otlp: bool,
}

impl Default for TelemetryConfig {
    fn default() -> Self {
        Self {
            service_name: "usora-risk-scoring-engine".into(),
            tracing_endpoint: None,
            metrics_endpoint: None,
            log_level: "info".into(),
            enable_otlp: false,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TenantOverrides {
    pub tenant_id: String,
    pub thresholds: Option<RiskThresholds>,
    pub model_overrides: HashMap<String, ModelConfig>,
    pub rules_enabled: Option<bool>,
    pub max_concurrent: Option<usize>,
}

impl ServiceConfig {
    pub fn from_path(path: &str) -> Result<Self, anyhow::Error> {
        let content = std::fs::read_to_string(path)
            .map_err(|e| anyhow::anyhow!("Failed to read config file {}: {}", path, e))?;
        let config: ServiceConfig = if path.ends_with(".yaml") || path.ends_with(".yml") {
            serde_yaml::from_str(&content)?
        } else {
            serde_json::from_str(&content)?
        };
        Ok(config)
    }

    pub fn from_env() -> Result<Self, anyhow::Error> {
        let config_path = std::env::var("RISK_SCORING_CONFIG")
            .unwrap_or_else(|_| "config/risk_scoring.yaml".into());
        Self::from_path(&config_path)
    }
}
