pub mod active;
pub mod passive;

use async_trait::async_trait;
use serde::{Deserialize, Serialize};

use crate::detection::DetectedFace;
use crate::models::{LivenessDetails, SpoofType};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LivenessResult {
    pub is_live: bool,
    pub confidence: f64,
    pub spoof_type: SpoofType,
    pub details: LivenessDetails,
}

#[async_trait]
pub trait LivenessDetector: Send + Sync {
    async fn check_liveness(
        &self,
        image: &image::DynamicImage,
        face: &DetectedFace,
        challenge: Option<&str>,
    ) -> anyhow::Result<LivenessResult>;

    async fn estimate_spoof_type(
        &self,
        image: &image::DynamicImage,
        face: &DetectedFace,
    ) -> anyhow::Result<SpoofType>;

    fn liveness_threshold(&self) -> f64;
}
