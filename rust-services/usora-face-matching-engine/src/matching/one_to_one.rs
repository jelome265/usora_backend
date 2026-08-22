use anyhow::Result;
use async_trait::async_trait;
use tracing::{info_span, trace};

use crate::embedding::FaceEmbedding;
use crate::matching::{MatchResult, Matcher};
use crate::utils;

pub struct CosineMatcher {
    threshold: f64,
}

impl CosineMatcher {
    pub fn new(threshold: f64) -> Self {
        CosineMatcher { threshold }
    }

    pub fn compute_similarity(a: &[f32], b: &[f32]) -> f64 {
        utils::cosine_similarity(a, b)
    }

    fn compute_match_confidence(similarity: f64, threshold: f64) -> f64 {
        if similarity >= threshold {
            let margin = similarity - threshold;
            (0.5 + margin * 2.5).min(1.0)
        } else {
            let deficit = threshold - similarity;
            (0.5 - deficit * 2.5).max(0.0)
        }
    }
}

#[async_trait]
impl Matcher for CosineMatcher {
    async fn verify_one_to_one(
        &self,
        probe: &FaceEmbedding,
        target: &FaceEmbedding,
    ) -> Result<MatchResult> {
        let _span = info_span!("verify_one_to_one");

        let similarity = Self::compute_similarity(&probe.vector, &target.vector);
        let passed_threshold = similarity >= self.threshold;
        let match_confidence = Self::compute_match_confidence(similarity, self.threshold);

        trace!(
            similarity = %similarity,
            threshold = %self.threshold,
            passed = %passed_threshold,
            "One-to-one verification"
        );

        Ok(MatchResult {
            user_id: None,
            similarity_score: similarity,
            match_confidence,
            passed_threshold,
            rank: None,
        })
    }

    async fn search_one_to_many(
        &self,
        _probe: &FaceEmbedding,
        _top_k: usize,
    ) -> Result<Vec<MatchResult>> {
        anyhow::bail!("One-to-many search not supported by CosineMatcher; use FaissMatcher");
    }

    fn threshold(&self) -> f64 {
        self.threshold
    }
}
