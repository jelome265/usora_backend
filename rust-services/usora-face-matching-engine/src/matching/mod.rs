pub mod one_to_many;
pub mod one_to_one;

use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::embedding::FaceEmbedding;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MatchResult {
    pub user_id: Option<Uuid>,
    pub similarity_score: f64,
    pub match_confidence: f64,
    pub passed_threshold: bool,
    pub rank: Option<usize>,
}

#[async_trait]
pub trait Matcher: Send + Sync {
    async fn verify_one_to_one(
        &self,
        probe: &FaceEmbedding,
        target: &FaceEmbedding,
    ) -> anyhow::Result<MatchResult>;

    async fn search_one_to_many(
        &self,
        probe: &FaceEmbedding,
        top_k: usize,
    ) -> anyhow::Result<Vec<MatchResult>>;

    fn threshold(&self) -> f64;
}
