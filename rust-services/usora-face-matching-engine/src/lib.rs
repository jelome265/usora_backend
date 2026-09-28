pub mod config;
pub mod detection;
pub mod embedding;
pub mod grpc;
pub mod liveness;
pub mod matching;
pub mod models;
pub mod utils;

pub use config::Config;
pub use detection::{DetectedFace, FaceDetector, QualityScore};
pub use embedding::{EmbeddingModel, FaceEmbedding};
pub use liveness::{LivenessDetector, LivenessResult};
pub use matching::{MatchResult, Matcher};
pub use models::*;

use anyhow::Result;
use image::DynamicImage;
use std::sync::Arc;
use tracing::{info, info_span, warn};

use crate::detection::face_detector::create_detector;
use crate::detection::quality_check::QualityChecker;
use crate::embedding::inference::OnnxEmbeddingModel;
use crate::liveness::active::ActiveLivenessDetector;
use crate::liveness::passive::PassiveLivenessDetector;
use crate::matching::one_to_many::FaissMatcher;
use crate::matching::one_to_one::CosineMatcher;

pub struct FaceMatchingEngine {
    face_detector: Arc<dyn FaceDetector>,
    embedding_model: Arc<OnnxEmbeddingModel>,
    quality_checker: Arc<QualityChecker>,
    active_liveness: Arc<ActiveLivenessDetector>,
    passive_liveness: Arc<PassiveLivenessDetector>,
    cosine_matcher: Arc<CosineMatcher>,
    faiss_matcher: Arc<FaissMatcher>,
    default_threshold: f64,
}

impl FaceMatchingEngine {
    pub fn new(
        face_detector: Arc<dyn FaceDetector>,
        embedding_model: Arc<OnnxEmbeddingModel>,
        quality_checker: Arc<QualityChecker>,
        active_liveness: Arc<ActiveLivenessDetector>,
        passive_liveness: Arc<PassiveLivenessDetector>,
        cosine_matcher: Arc<CosineMatcher>,
        faiss_matcher: Arc<FaissMatcher>,
        default_threshold: f64,
    ) -> Self {
        FaceMatchingEngine {
            face_detector,
            embedding_model,
            quality_checker,
            active_liveness,
            passive_liveness,
            cosine_matcher,
            faiss_matcher,
            default_threshold,
        }
    }

    pub async fn detect_and_extract(
        &self,
        image: &DynamicImage,
        perform_quality_check: bool,
    ) -> Result<Vec<DetectedFace>> {
        let _span = info_span!("detect_and_extract").entered();
        let mut faces = self.face_detector.detect_faces(image).await?;

        if perform_quality_check {
            for face in &mut faces {
                match self.quality_checker.check_quality(image, face) {
                    Ok(score) => {
                        face.quality_score = Some(score);
                    }
                    Err(e) => {
                        warn!(error = %e, "Quality check failed for face");
                    }
                }
            }
            faces.retain(|f| {
                f.quality_score
                    .as_ref()
                    .map(|q| q.passes_quality_gate)
                    .unwrap_or(true)
            });
        }

        info!(faces_detected = faces.len(), "Face detection completed");
        Ok(faces)
    }

    pub async fn verify_faces(
        &self,
        source: &DynamicImage,
        target: &DynamicImage,
        threshold: f64,
    ) -> Result<MatchResult> {
        let _span = info_span!("verify_faces").entered();

        let source_faces = self.detect_and_extract(source, true).await?;
        let target_faces = self.detect_and_extract(target, true).await?;

        let source_face = source_faces
            .into_iter()
            .next()
            .ok_or_else(|| anyhow::anyhow!("No face detected in source image"))?;

        let target_face = target_faces
            .into_iter()
            .next()
            .ok_or_else(|| anyhow::anyhow!("No face detected in target image"))?;

        let source_embedding = self.embedding_model
            .generate_embedding(source, &source_face)
            .await?;

        let target_embedding = self.embedding_model
            .generate_embedding(target, &target_face)
            .await?;

        let result = self.cosine_matcher
            .verify_one_to_one(&source_embedding, &target_embedding)
            .await?;

        Ok(result)
    }

    pub async fn identify_face(
        &self,
        probe: &DynamicImage,
        top_k: usize,
        tenant_id: &str,
    ) -> Result<Vec<MatchResult>> {
        let _span = info_span!("identify_face", tenant = %tenant_id).entered();

        let faces = self.detect_and_extract(probe, true).await?;
        let best_face = faces
            .into_iter()
            .next()
            .ok_or_else(|| anyhow::anyhow!("No face detected in probe image"))?;

        let embedding = self.embedding_model
            .generate_embedding(probe, &best_face)
            .await?;

        // SECURITY/CORRECTNESS: must search the caller's own tenant index,
        // not the "__default__" bucket that the tenant-less
        // search_one_to_many() falls back to. Previously tenant_id from the
        // request was dropped before reaching this call, meaning 1:N
        // identification silently searched an index that real enrollments
        // are never written to — effectively disabling duplicate/fraud
        // identity detection rather than failing loudly.
        let results = self.faiss_matcher
            .search_one_to_many_with_tenant(&embedding, top_k, tenant_id)
            .await?;

        Ok(results)
    }

    pub async fn check_liveness(
        &self,
        image: &DynamicImage,
        challenge_type: &str,
        challenge_data: Option<&str>,
    ) -> Result<LivenessResult> {
        let _span = info_span!("check_liveness", challenge_type = %challenge_type).entered();

        let faces = self.detect_and_extract(image, true).await?;
        let best_face = faces
            .into_iter()
            .next()
            .ok_or_else(|| anyhow::anyhow!("No face detected for liveness check"))?;

        let active_result = self.active_liveness
            .check_liveness(image, &best_face, Some(challenge_type))
            .await?;

        let passive_result = self.passive_liveness
            .check_liveness(image, &best_face, challenge_data)
            .await?;

        let combined_confidence = active_result.confidence * 0.5 + passive_result.confidence * 0.5;
        let is_live = combined_confidence >= 0.7;

        let spoof_type = if !is_live {
            if active_result.spoof_type != models::SpoofType::None {
                active_result.spoof_type
            } else {
                passive_result.spoof_type
            }
        } else {
            models::SpoofType::None
        };

        Ok(LivenessResult {
            is_live,
            confidence: combined_confidence,
            spoof_type,
            details: models::LivenessDetails {
                texture_score: passive_result.details.texture_score,
                motion_score: active_result.details.motion_score,
                depth_score: 0.0,
                color_distribution_score: passive_result.details.color_distribution_score,
                specular_reflection_score: passive_result.details.specular_reflection_score,
                micro_expression_score: active_result.details.micro_expression_score,
                action_verification: active_result.details.action_verification,
            },
        })
    }

    pub async fn register_face(
        &self,
        image: &DynamicImage,
        user_id: &str,
    ) -> Result<FaceEmbedding> {
        let _span = info_span!("register_face", user_id = %user_id).entered();

        let faces = self.detect_and_extract(image, true).await?;
        let best_face = faces
            .into_iter()
            .next()
            .ok_or_else(|| anyhow::anyhow!("No face detected in registration image"))?;

        let embedding = self.embedding_model
            .generate_embedding(image, &best_face)
            .await?;

        self.faiss_matcher.add_embedding(&embedding, user_id)?;

        info!(user_id = %user_id, "Face registered successfully");
        Ok(embedding)
    }

    pub fn default_threshold(&self) -> f64 {
        self.default_threshold
    }
}
