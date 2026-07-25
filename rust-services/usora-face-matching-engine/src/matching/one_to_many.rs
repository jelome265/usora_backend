use anyhow::{Context, Result};
use async_trait::async_trait;
use std::path::Path;
use std::sync::Mutex;
use tracing::{info, info_span, warn};
use uuid::Uuid;

use crate::config::FaissConfig;
use crate::embedding::FaceEmbedding;
use crate::matching::{MatchResult, Matcher};

pub struct FaissMatcher {
    index: Mutex<Box<dyn faiss::Index>>,
    threshold: f64,
    top_k_default: usize,
    dimension: usize,
    nprobe: u32,
}

impl FaissMatcher {
    pub fn new(
        config: &FaissConfig,
        threshold: f64,
        top_k_default: usize,
    ) -> Result<Self> {
        let index: Box<dyn faiss::Index> = if config.index_path.exists() {
            info!(path = %config.index_path.display(), "Loading existing FAISS index");
            let idx = faiss::read_index(&config.index_path.to_string_lossy())
                .context("Failed to read FAISS index")?;
            idx
        } else {
            warn!("FAISS index not found, creating empty index. Path: {}", config.index_path.display());
            let flat = faiss::IndexFlatIP::new(config.dimension as i32)?;
            Box::new(flat)
        };

        Ok(FaissMatcher {
            index: Mutex::new(index),
            threshold,
            top_k_default,
            dimension: config.dimension,
            nprobe: config.nprobe,
        })
    }

    pub fn add_embedding(&self, embedding: &FaceEmbedding, user_id: &str) -> Result<()> {
        let mut index = self.index.lock().unwrap();
        let vec = embedding.vector.clone();
        let id = Self::user_id_to_faiss_id(user_id);

        index.add_with_ids(vec.as_slice(), &[id])
            .context("Failed to add embedding to index")?;

        Ok(())
    }

    pub fn remove_embedding(&self, user_id: &str) -> Result<()> {
        let id = Self::user_id_to_faiss_id(user_id);
        let mut index = self.index.lock().unwrap();
        let selector = faiss::IDSelectorRange::new(id, id + 1)?;
        index.remove_ids(&selector)?;
        Ok(())
    }

    pub fn save_index(&self, path: &Path) -> Result<()> {
        let index = self.index.lock().unwrap();
        faiss::write_index(index.as_ref(), &path.to_string_lossy())
            .context("Failed to write FAISS index")?;
        Ok(())
    }

    pub fn index_size(&self) -> usize {
        let index = self.index.lock().unwrap();
        index.ntotal() as usize
    }

    pub fn train_index(&self, embeddings: &[f32]) -> Result<()> {
        let mut index = self.index.lock().unwrap();
        if !index.is_trained() {
            index.train(embeddings)?;
        }
        Ok(())
    }

    fn user_id_to_faiss_id(user_id: &str) -> i64 {
        let hash = blake3::hash(user_id.as_bytes());
        let bytes = hash.as_bytes();
        i64::from_ne_bytes([
            bytes[0], bytes[1], bytes[2], bytes[3],
            bytes[4], bytes[5], bytes[6], bytes[7],
        ])
    }

    fn faiss_id_to_user_id(id: i64) -> Uuid {
        let mut bytes = [0u8; 16];
        let id_bytes = id.to_ne_bytes();
        bytes[..8].copy_from_slice(&id_bytes);
        Uuid::from_bytes(bytes)
    }

    fn re_rank(
        results: &[(i64, f32)],
        _probe: &FaceEmbedding,
        _original_top_k: usize,
    ) -> Vec<(i64, f64)> {
        let mut reranked: Vec<(i64, f64)> = results.iter()
            .map(|&(id, score)| {
                let normalized_score = (score as f64 + 1.0) / 2.0;
                (id, normalized_score)
            })
            .collect();

        reranked.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
        reranked
    }
}

#[async_trait]
impl Matcher for FaissMatcher {
    async fn verify_one_to_one(
        &self,
        _probe: &FaceEmbedding,
        _target: &FaceEmbedding,
    ) -> Result<MatchResult> {
        anyhow::bail!("One-to-one verification not supported directly by FaissMatcher; use CosineMatcher");
    }

    async fn search_one_to_many(
        &self,
        probe: &FaceEmbedding,
        top_k: usize,
    ) -> Result<Vec<MatchResult>> {
        let _span = info_span!("search_one_to_many", top_k = top_k).entered();

        let k = top_k.min(1000);
        let query_slice = probe.vector.as_slice();

        let (distances, labels) = {
            let mut index = self.index.lock().unwrap();

            if let Some(ivf) = index.as_any_mut().downcast_mut::<faiss::IndexIVFFlat>() {
                ivf.nprobe = self.nprobe;
            }

            let ntotal = index.ntotal();
            if ntotal == 0 {
                return Ok(Vec::new());
            }
            let k_actual = k.min(ntotal as usize);

            index.search(query_slice, k_actual as i32)
                .context("FAISS search failed")?
        };

        let results: Vec<(i64, f32)> = labels[0]
            .iter()
            .zip(distances[0].iter())
            .filter(|(&id, _)| id >= 0)
            .map(|(&id, &score)| (id, score))
            .collect();

        if results.is_empty() {
            return Ok(Vec::new());
        }

        let reranked = Self::re_rank(&results, probe, k);

        let match_results: Vec<MatchResult> = reranked
            .into_iter()
            .map(|(id, score)| {
                let passed = score >= self.threshold;
                let confidence = if passed {
                    (0.5 + (score - self.threshold) * 5.0).min(1.0)
                } else {
                    (0.5 - (self.threshold - score) * 5.0).max(0.0)
                };

                let user_id = Self::faiss_id_to_user_id(id);

                MatchResult {
                    user_id: Some(user_id),
                    similarity_score: score,
                    match_confidence: confidence,
                    passed_threshold: passed,
                    rank: None,
                }
            })
            .collect();

        let final_results: Vec<MatchResult> = match_results
            .into_iter()
            .enumerate()
            .map(|(i, mut r)| {
                r.rank = Some(i + 1);
                r
            })
            .collect();

        info!(
            query_results = final_results.len(),
            threshold = %self.threshold,
            "One-to-many search completed"
        );

        Ok(final_results)
    }

    fn threshold(&self) -> f64 {
        self.threshold
    }
}
