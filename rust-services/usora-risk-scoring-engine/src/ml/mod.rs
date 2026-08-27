pub mod feature_store;
pub mod inference;
pub mod model;

use crate::models::EnsembleResult;
use async_trait::async_trait;
use std::collections::HashMap;

pub type FeatureMap = HashMap<String, f64>;

#[async_trait]
pub trait ModelEnsemble: Send + Sync {
    async fn predict(&self, features: &FeatureMap) -> Result<EnsembleResult, ModelError>;
    async fn predict_batch(&self, batch: &[FeatureMap]) -> Result<Vec<EnsembleResult>, ModelError>;
    async fn explain(
        &self,
        features: &FeatureMap,
        result: &EnsembleResult,
    ) -> Result<HashMap<String, f64>, ModelError>;
    fn metadata(&self) -> crate::models::ModelMetadata;
    fn name(&self) -> &str;
    fn version(&self) -> &str;
}

#[derive(Debug, thiserror::Error)]
pub enum ModelError {
    #[error("Model not found: {0}")]
    NotFound(String),
    #[error("Model load failed: {0}")]
    LoadFailed(String),
    #[error("Inference failed: {0}")]
    InferenceFailed(String),
    #[error("Feature error: {0}")]
    FeatureError(String),
    #[error("Timeout")]
    Timeout,
    #[error("Batch too large: {size} > {max}")]
    BatchTooLarge { size: usize, max: usize },
    #[error("Model version mismatch: expected {expected}, got {actual}")]
    VersionMismatch { expected: String, actual: String },
    #[error("Checksum mismatch")]
    ChecksumMismatch,
    #[error(transparent)]
    Internal(#[from] anyhow::Error),
}
