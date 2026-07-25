use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum RiskLevel {
    Low,
    Medium,
    High,
    Critical,
}

impl RiskLevel {
    pub fn from_score(score: f64, thresholds: &RiskThresholds) -> Self {
        if score >= thresholds.critical {
            RiskLevel::Critical
        } else if score >= thresholds.high {
            RiskLevel::High
        } else if score >= thresholds.medium {
            RiskLevel::Medium
        } else {
            RiskLevel::Low
        }
    }

    pub fn as_f64(&self) -> f64 {
        match self {
            RiskLevel::Low => 0.0,
            RiskLevel::Medium => 0.3,
            RiskLevel::High => 0.7,
            RiskLevel::Critical => 0.9,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RiskThresholds {
    pub low: f64,
    pub medium: f64,
    pub high: f64,
    pub critical: f64,
}

impl Default for RiskThresholds {
    fn default() -> Self {
        Self {
            low: 0.0,
            medium: 0.3,
            high: 0.7,
            critical: 0.9,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApplicantScoringRequest {
    pub applicant_id: String,
    pub tenant_id: String,
    pub transaction_id: Option<String>,
    pub features: HashMap<String, FeatureValue>,
    pub include_explanation: bool,
    pub request_id: Uuid,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TransactionScoringRequest {
    pub transaction_id: String,
    pub applicant_id: String,
    pub tenant_id: String,
    pub amount: f64,
    pub currency: String,
    pub transaction_type: String,
    pub features: HashMap<String, FeatureValue>,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchScoringRequest {
    pub tenant_id: String,
    pub requests: Vec<ApplicantScoringRequest>,
    pub priority: BatchPriority,
    pub max_concurrency: Option<usize>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum BatchPriority {
    Low,
    Normal,
    High,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScoringResponse {
    pub score_id: Uuid,
    pub applicant_id: String,
    pub tenant_id: String,
    pub composite_score: f64,
    pub risk_level: RiskLevel,
    pub ml_score: Option<f64>,
    pub ml_risk_level: Option<RiskLevel>,
    pub rule_score: Option<f64>,
    pub rule_risk_level: Option<RiskLevel>,
    pub explanation: Option<ScoreExplanation>,
    pub risk_factors: Vec<RiskFactor>,
    pub model_version: String,
    pub rule_version: String,
    pub processing_time_ms: f64,
    pub computed_at: DateTime<Utc>,
    pub cached: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScoreExplanation {
    pub method: String,
    pub base_score: f64,
    pub feature_contributions: Vec<FeatureContribution>,
    pub rule_contributions: Vec<RuleContribution>,
    pub top_risk_drivers: Vec<String>,
    pub confidence: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeatureContribution {
    pub feature_name: String,
    pub value: FeatureValue,
    pub importance: f64,
    pub direction: ContributionDirection,
    pub shap_value: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum ContributionDirection {
    IncreasesRisk,
    DecreasesRisk,
    Neutral,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RuleContribution {
    pub rule_id: String,
    pub rule_name: String,
    pub triggered: bool,
    pub score_delta: f64,
    pub description: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RiskFactor {
    pub factor_id: String,
    pub factor_type: RiskFactorType,
    pub severity: f64,
    pub description: String,
    pub source: String,
    pub timestamp: DateTime<Utc>,
    pub expires_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum RiskFactorType {
    Identity,
    Fraud,
    Sanctions,
    Behavioral,
    Geographic,
    Financial,
    Device,
    Network,
    Custom(String),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelMetadata {
    pub model_id: String,
    pub model_type: ModelType,
    pub version: String,
    pub path: String,
    pub input_features: usize,
    pub output_classes: usize,
    pub class_labels: Vec<String>,
    pub checksum: String,
    pub loaded_at: DateTime<Utc>,
    pub metrics: ModelMetrics,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum ModelType {
    XGBoost,
    NeuralNetwork,
    Ensemble,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelMetrics {
    pub inference_count: u64,
    pub avg_latency_ms: f64,
    pub p99_latency_ms: f64,
    pub error_rate: f64,
    pub drift_score: f64,
    pub last_drift_check: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EnsembleResult {
    pub scores: Vec<f64>,
    pub probabilities: Vec<f64>,
    pub predicted_class: usize,
    pub model_contributions: HashMap<String, f64>,
    pub feature_importance: HashMap<String, f64>,
    pub shap_values: Option<Vec<f64>>,
    pub latency_ms: f64,
    pub model_id: String,
    pub model_version: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RuleResult {
    pub triggered: bool,
    pub rule_id: String,
    pub rule_name: String,
    pub priority: i32,
    pub score_delta: f64,
    pub risk_level_override: Option<RiskLevel>,
    pub explanation: String,
    pub metadata: HashMap<String, String>,
    pub execution_time_ms: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RuleDefinition {
    pub rule_id: String,
    pub tenant_id: Option<String>,
    pub name: String,
    pub description: String,
    pub priority: i32,
    pub enabled: bool,
    pub dsl_script: String,
    pub version: String,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub tags: Vec<String>,
    pub simulation_mode: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeatureVector {
    pub tenant_id: String,
    pub applicant_id: String,
    pub features: HashMap<String, FeatureValue>,
    pub fetch_timestamp: DateTime<Utc>,
    pub source: FeatureSource,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum FeatureSource {
    Realtime,
    Batch,
    Cache,
    Default,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum FeatureValue {
    String(String),
    Integer(i64),
    Float(f64),
    Boolean(bool),
    Array(Vec<FeatureValue>),
    Object(HashMap<String, FeatureValue>),
    Null,
}

impl FeatureValue {
    pub fn as_f64(&self) -> Option<f64> {
        match self {
            FeatureValue::Float(v) => Some(*v),
            FeatureValue::Integer(v) => Some(*v as f64),
            FeatureValue::Boolean(v) => Some(if *v { 1.0 } else { 0.0 }),
            _ => None,
        }
    }

    pub fn as_str(&self) -> Option<&str> {
        match self {
            FeatureValue::String(v) => Some(v),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExplainabilityRequest {
    pub score_id: Uuid,
    pub applicant_id: String,
    pub tenant_id: String,
    pub max_features: Option<usize>,
    pub min_importance: Option<f64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExplainabilityResponse {
    pub score_id: Uuid,
    pub explanation: ScoreExplanation,
    pub model_version: String,
    pub rule_version: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RiskFactorsRequest {
    pub score_id: Uuid,
    pub applicant_id: String,
    pub tenant_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RiskFactorsResponse {
    pub score_id: Uuid,
    pub risk_factors: Vec<RiskFactor>,
    pub total_risk_score: f64,
    pub risk_level: RiskLevel,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelUpdateRequest {
    pub model_id: String,
    pub tenant_id: Option<String>,
    pub new_version: String,
    pub model_path: String,
    pub checksum: String,
    pub force: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelUpdateResponse {
    pub model_id: String,
    pub previous_version: String,
    pub current_version: String,
    pub status: UpdateStatus,
    pub reloaded_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum UpdateStatus {
    Success,
    Failed(String),
    Pending,
    RolledBack,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct KafkaScoreMessage {
    pub score_id: Uuid,
    pub applicant_id: String,
    pub tenant_id: String,
    pub composite_score: f64,
    pub risk_level: RiskLevel,
    pub processing_time_ms: f64,
    pub model_version: String,
    pub rule_version: String,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScoredApplicantResult {
    pub applicant_id: String,
    pub response: ScoringResponse,
    pub error: Option<String>,
}
