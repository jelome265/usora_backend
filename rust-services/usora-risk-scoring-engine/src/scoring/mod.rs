pub mod calculator;
pub mod engine;

use crate::models::ScoringResponse;
use async_trait::async_trait;

#[async_trait]
pub trait ScoringEngine: Send + Sync {
    async fn calculate_score(
        &self,
        request: &crate::models::ApplicantScoringRequest,
    ) -> Result<ScoringResponse, ScoringError>;
    async fn calculate_batch(
        &self,
        requests: &[crate::models::ApplicantScoringRequest],
    ) -> Result<Vec<ScoringResponse>, ScoringError>;
    async fn explain_score(
        &self,
        request: &crate::models::ExplainabilityRequest,
    ) -> Result<crate::models::ScoreExplanation, ScoringError>;
    async fn health(&self) -> Result<(), ScoringError>;
}

#[derive(Debug, thiserror::Error)]
pub enum ScoringError {
    #[error("ML scoring failed: {0}")]
    MlError(String),
    #[error("Rule scoring failed: {0}")]
    RuleError(String),
    #[error("Calculation error: {0}")]
    CalculationError(String),
    #[error("Feature error: {0}")]
    FeatureError(String),
    #[error("Not found: {0}")]
    NotFound(String),
    #[error("Timeout")]
    Timeout,
    #[error(transparent)]
    Internal(#[from] anyhow::Error),
}
