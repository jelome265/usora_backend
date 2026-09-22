use anyhow::{Context, Result};
use async_trait::async_trait;
use std::collections::HashMap;
use std::path::Path;
use std::sync::Mutex;
use std::sync::atomic::{AtomicUsize, Ordering};
use tracing::{info, info_span, warn};
use uuid::Uuid;

use crate::config::FaissConfig;
use crate::embedding::FaceEmbedding;
use crate::matching::{MatchResult, Matcher};

const MAX_PENDING_SAVES: usize = 100;

pub struct FaissMatcher {
    indices: Mutex<HashMap<String, Box<dyn faiss::Index>>>,
    pending_additions: Mutex<HashMap<String, usize>>,
    threshold: f64,
    top_k_default: usize,
    dimension: usize,
    nprobe: u32,
    index_path: std::path::PathBuf,
    save_counter: AtomicUsize,
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

        let mut indices = HashMap::new();
        indices.insert("__default__".to_string(), index);

        Ok(FaissMatcher {
            indices: Mutex::new(indices),
            pending_additions: Mutex::new(HashMap::new()),
            threshold,
            top_k_default,
            dimension: config.dimension,
            nprobe: config.nprobe,
            index_path: config.index_path.clone(),
            save_counter: AtomicUsize::new(0),
        })
    }

    fn get_or_create_tenant_index(
        indices: &mut HashMap<String, Box<dyn faiss::Index>>,
        tenant_id: &str,
        dimension: usize,
    ) -> Result<&mut Box<dyn faiss::Index>> {
        if !indices.contains_key(tenant_id) {
            let flat = faiss::IndexFlatIP::new(dimension as i32)?;
            indices.insert(tenant_id.to_string(), Box::new(flat));
        }
        Ok(indices.get_mut(tenant_id).expect("just inserted above, key must be present"))
    }

    pub fn add_embedding(&self, embedding: &FaceEmbedding, user_id: &str, tenant_id: &str) -> Result<()> {
        let mut indices = self.indices.lock()
            .map_err(|_| anyhow::anyhow!("FAISS indices mutex poisoned by an earlier panic"))?;
        let index = Self::get_or_create_tenant_index(
            &mut indices, tenant_id, self.dimension,
        )?;

        let vec = embedding.vector.clone();
        let id = Self::user_id_to_faiss_id(user_id, tenant_id);
        let ids = [id];

        index.add_with_ids(vec.as_slice(), &ids)
            .context("Failed to add embedding to index")?;

        let mut pending = self.pending_additions.lock()
            .map_err(|_| anyhow::anyhow!("pending_additions mutex poisoned by an earlier panic"))?;
        *pending.entry(tenant_id.to_string()).or_insert(0) += 1;

        let count = self.save_counter.fetch_add(1, Ordering::SeqCst);
        if count >= MAX_PENDING_SAVES {
            drop(pending);
            drop(indices);
            if let Err(e) = self.flush_all() {
                warn!("Failed to auto-save FAISS indices: {e}");
            }
            self.save_counter.store(0, Ordering::SeqCst);
        }

        Ok(())
    }

    pub fn remove_embedding(&self, user_id: &str, tenant_id: &str) -> Result<()> {
        let id = Self::user_id_to_faiss_id(user_id, tenant_id);
        let mut indices = self.indices.lock()
            .map_err(|_| anyhow::anyhow!("FAISS indices mutex poisoned by an earlier panic"))?;
        if let Some(index) = indices.get_mut(tenant_id) {
            let selector = faiss::IDSelectorRange::new(id, id + 1)?;
            index.remove_ids(&selector)?;
        }
        Ok(())
    }

    pub fn flush_all(&self) -> Result<()> {
        let indices = self.indices.lock()
            .map_err(|_| anyhow::anyhow!("FAISS indices mutex poisoned by an earlier panic"))?;
        for (tenant_id, index) in indices.iter() {
            let path = if tenant_id == "__default__" {
                self.index_path.clone()
            } else {
                let parent = self.index_path.parent().unwrap_or(Path::new("data"));
                let stem = self.index_path.file_stem().unwrap_or_default();
                let ext = self.index_path.extension().unwrap_or_default();
                parent.join(format!("{}_{}.{}", stem.to_string_lossy(), tenant_id, ext.to_string_lossy()))
            };
            faiss::write_index(index.as_ref(), &path.to_string_lossy())
                .context(format!("Failed to write FAISS index for tenant {tenant_id}"))?;
            info!(tenant = %tenant_id, path = %path.display(), "FAISS index saved");
        }
        Ok(())
    }

    pub fn save_index(&self, path: &Path) -> Result<()> {
        let index = self.indices.lock()
            .map_err(|_| anyhow::anyhow!("FAISS indices mutex poisoned by an earlier panic"))?;
        if let Some(idx) = index.get("__default__") {
            faiss::write_index(idx.as_ref(), &path.to_string_lossy())
                .context("Failed to write FAISS index")?;
        }
        Ok(())
    }

    pub fn index_size(&self, tenant_id: &str) -> usize {
        // Not Result-returning — this is a best-effort size query, not a
        // correctness-critical path. Recovering the poisoned guard
        // (rather than propagating an error this function has no way to
        // return) means a prior panic elsewhere degrades this to a
        // possibly-stale read instead of poisoning every future call to
        // this specific method too.
        let indices = self.indices.lock().unwrap_or_else(|e| e.into_inner());
        indices.get(tenant_id)
            .map(|idx| idx.ntotal() as usize)
            .unwrap_or(0)
    }

    pub fn train_index(&self, embeddings: &[f32], tenant_id: &str) -> Result<()> {
        let mut indices = self.indices.lock()
            .map_err(|_| anyhow::anyhow!("FAISS indices mutex poisoned by an earlier panic"))?;
        let index = Self::get_or_create_tenant_index(&mut indices, tenant_id, self.dimension)?;
        if !index.is_trained() {
            index.train(embeddings)?;
        }
        Ok(())
    }

    fn user_id_to_faiss_id(user_id: &str, tenant_id: &str) -> i64 {
        let combined = format!("{}:{}", tenant_id, user_id);
        let hash = blake3::hash(combined.as_bytes());
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
        self.search_one_to_many_with_tenant(probe, top_k, "__default__").await
    }
}

impl FaissMatcher {
    pub async fn search_one_to_many_with_tenant(
        &self,
        probe: &FaceEmbedding,
        top_k: usize,
        tenant_id: &str,
    ) -> Result<Vec<MatchResult>> {
        let _span = info_span!("search_one_to_many", top_k = top_k, tenant = %tenant_id).entered();

        let k = top_k.min(1000);
        let query_slice = probe.vector.as_slice();

        let (distances, labels) = {
            let indices = self.indices.lock()
            .map_err(|_| anyhow::anyhow!("FAISS indices mutex poisoned by an earlier panic"))?;
            let index = match indices.get(tenant_id) {
                Some(idx) => idx,
                None => return Ok(Vec::new()),
            };

            if let Some(ivf) = index.as_any().downcast_ref::<faiss::IndexIVFFlat>() {
                let mut mutable_ivf = ivf.clone();
                mutable_ivf.nprobe = self.nprobe;
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

        Ok(final_results)
    }

    fn threshold(&self) -> f64 {
        self.threshold
    }
}

unsafe impl Send for FaissMatcher {}
unsafe impl Sync for FaissMatcher {}
