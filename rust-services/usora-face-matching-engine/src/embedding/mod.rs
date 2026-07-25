pub mod inference;
pub mod model;

use async_trait::async_trait;
use serde::{Deserialize, Serialize};

use crate::detection::DetectedFace;

pub type EmbeddingVector = Vec<f32>;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FaceEmbedding {
    pub vector: EmbeddingVector,
    pub dimension: usize,
    pub model_version: String,
    pub confidence: f64,
    pub face_id: Option<String>,
}

#[async_trait]
pub trait EmbeddingModel: Send + Sync {
    async fn generate_embedding(
        &self,
        image: &image::DynamicImage,
        face: &DetectedFace,
    ) -> anyhow::Result<FaceEmbedding>;

    async fn generate_embeddings_batch(
        &self,
        images: &[(&image::DynamicImage, &DetectedFace)],
    ) -> anyhow::Result<Vec<FaceEmbedding>>;

    fn dimension(&self) -> usize;
    fn model_version(&self) -> &str;
}
