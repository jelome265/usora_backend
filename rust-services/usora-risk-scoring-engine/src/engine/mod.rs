pub mod cache;
pub mod orchestrator;

use crate::models::ScoringResponse;
use async_trait::async_trait;

#[async_trait]
pub trait RiskEngine: Send + Sync {
    async fn score_applicant(
        &self,
        request: &crate::models::ApplicantScoringRequest,
    ) -> Result<ScoringResponse, RiskEngineError>;
    async fn score_transaction(
        &self,
        request: &crate::models::TransactionScoringRequest,
    ) -> Result<ScoringResponse, RiskEngineError>;
    async fn batch_score(
        &self,
        requests: &[crate::models::ApplicantScoringRequest],
    ) -> Result<Vec<ScoringResponse>, RiskEngineError>;
    async fn explain_score(
        &self,
        score_id: &uuid::Uuid,
        request: &crate::models::ExplainabilityRequest,
    ) -> Result<crate::models::ScoreExplanation, RiskEngineError>;
    async fn health_check(&self) -> Result<(), RiskEngineError>;
}

#[derive(Debug, thiserror::Error)]
pub enum RiskEngineError {
    #[error("Feature retrieval failed: {0}")]
    FeatureError(String),
    #[error("ML inference failed: {0}")]
    MlError(String),
    #[error("Rule evaluation failed: {0}")]
    RuleError(String),
    #[error("Scoring calculation failed: {0}")]
    ScoringError(String),
    #[error("Cache error: {0}")]
    CacheError(String),
    #[error("Configuration error: {0}")]
    ConfigError(String),
    #[error("Tenant isolation error: {0}")]
    TenantIsolationError(String),
    #[error("Timeout")]
    Timeout,
    #[error("Not found: {0}")]
    NotFound(String),
    #[error(transparent)]
    Internal(#[from] anyhow::Error),
}
