use crate::config::ModelsConfig;
use crate::ml::feature_store::NormalizationParams;
use crate::ml::model::ModelRegistry;
use crate::ml::{FeatureMap, ModelEnsemble, ModelError};
use crate::models::EnsembleResult;
use crate::utils::Stopwatch;
use dashmap::DashMap;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::Semaphore;

pub struct InferenceService {
    registry: Arc<ModelRegistry>,
    normalization: Arc<NormalizationParams>,
    config: ModelsConfig,
    semaphore: Arc<Semaphore>,
    latency_histogram: Arc<DashMap<String, Vec<f64>>>,
}

impl InferenceService {
    pub fn new(
        registry: Arc<ModelRegistry>,
        normalization: Arc<NormalizationParams>,
        config: ModelsConfig,
    ) -> Self {
        let semaphore = Arc::new(Semaphore::new(config.max_batch_size));
        Self {
            registry,
            normalization,
            config,
            semaphore,
            latency_histogram: Arc::new(DashMap::new()),
        }
    }

    pub async fn predict(
        &self,
        model_id: &str,
        raw_features: &FeatureMap,
    ) -> Result<EnsembleResult, ModelError> {
        let sw = Stopwatch::start();
        let _permit = self
            .semaphore
            .acquire()
            .await
            .map_err(|_| ModelError::InferenceFailed("Semaphore closed".into()))?;

        let lifecycle = self
            .registry
            .get(model_id)
            .await
            .ok_or_else(|| ModelError::NotFound(model_id.to_string()))?;

        let normalized = self.normalization.normalize(raw_features);
        let feature_map: FeatureMap = normalized
            .into_iter()
            .enumerate()
            .map(|(i, v)| (format!("feature_{}", i), v))
            .collect();

        let result = lifecycle.predict(&feature_map).await?;
        let latency = sw.elapsed_ms();

        self.latency_histogram
            .entry(model_id.to_string())
            .or_insert_with(Vec::new)
            .push(latency);

        if self.latency_histogram.get(model_id).map_or(0, |v| v.len()) > 1000 {
            self.latency_histogram
                .get_mut(model_id)
                .map(|mut v| v.remove(0));
        }

        Ok(result)
    }

    pub async fn predict_batch(
        &self,
        model_id: &str,
        batch: &[FeatureMap],
    ) -> Result<Vec<EnsembleResult>, ModelError> {
        if batch.is_empty() {
            return Ok(Vec::new());
        }
        if batch.len() > self.config.max_batch_size {
            return Err(ModelError::BatchTooLarge {
                size: batch.len(),
                max: self.config.max_batch_size,
            });
        }

        let sw = Stopwatch::start();
        let _permit = self
            .semaphore
            .acquire()
            .await
            .map_err(|_| ModelError::InferenceFailed("Semaphore closed".into()))?;

        let lifecycle = self
            .registry
            .get(model_id)
            .await
            .ok_or_else(|| ModelError::NotFound(model_id.to_string()))?;

        let normalized_batch: Vec<FeatureMap> = batch
            .iter()
            .map(|raw| {
                let normalized = self.normalization.normalize(raw);
                normalized
                    .into_iter()
                    .enumerate()
                    .map(|(i, v)| (format!("feature_{}", i), v))
                    .collect()
            })
            .collect();

        let results = lifecycle.predict_batch(&normalized_batch).await?;
        let latency = sw.elapsed_ms();

        self.latency_histogram
            .entry(model_id.to_string())
            .or_insert_with(Vec::new)
            .push(latency);

        Ok(results)
    }

    pub async fn explain(
        &self,
        model_id: &str,
        raw_features: &FeatureMap,
        result: &EnsembleResult,
    ) -> Result<HashMap<String, f64>, ModelError> {
        let lifecycle = self
            .registry
            .get(model_id)
            .await
            .ok_or_else(|| ModelError::NotFound(model_id.to_string()))?;

        let normalized = self.normalization.normalize(raw_features);
        let feature_map: FeatureMap = normalized
            .into_iter()
            .enumerate()
            .map(|(i, v)| (format!("feature_{}", i), v))
            .collect();

        lifecycle.explain(&feature_map, result).await
    }

    pub fn avg_latency(&self, model_id: &str) -> f64 {
        self.latency_histogram
            .get(model_id)
            .map(|v| {
                let values: Vec<&f64> = v.iter().collect();
                if values.is_empty() {
                    0.0
                } else {
                    values.iter().copied().sum::<f64>() / values.len() as f64
                }
            })
            .unwrap_or(0.0)
    }

    pub fn p99_latency(&self, model_id: &str) -> f64 {
        self.latency_histogram
            .get(model_id)
            .map(|v| {
                let mut sorted: Vec<f64> = v.iter().copied().collect();
                if sorted.is_empty() {
                    return 0.0;
                }
                sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
                let idx = (sorted.len() as f64 * 0.99).ceil() as usize - 1;
                sorted[idx.min(sorted.len() - 1)]
            })
            .unwrap_or(0.0)
    }

    pub async fn ensemble_predict(
        &self,
        model_ids: &[&str],
        raw_features: &FeatureMap,
        weights: &[f64],
    ) -> Result<EnsembleResult, ModelError> {
        if model_ids.is_empty() {
            return Err(ModelError::NotFound("No models specified".into()));
        }
        if model_ids.len() != weights.len() {
            return Err(ModelError::InferenceFailed(
                "Model IDs and weights length mismatch".into(),
            ));
        }

        let sw = Stopwatch::start();
        let mut all_results = Vec::with_capacity(model_ids.len());
        let mut model_contributions = HashMap::new();

        for (&model_id, &weight) in model_ids.iter().zip(weights.iter()) {
            let result = self.predict(model_id, raw_features).await?;
            model_contributions.insert(model_id.to_string(), weight);
            all_results.push(result);
        }

        let num_classes = all_results
            .first()
            .map(|r| r.probabilities.len())
            .unwrap_or(0);
        let mut ensemble_probs = vec![0.0; num_classes];
        for (i, result) in all_results.iter().enumerate() {
            for (j, &prob) in result.probabilities.iter().enumerate() {
                ensemble_probs[j] += prob * weights[i];
            }
        }

        let total_weight: f64 = weights.iter().sum();
        if total_weight > 0.0 {
            for prob in &mut ensemble_probs {
                *prob /= total_weight;
            }
        }

        let predicted_class = ensemble_probs
            .iter()
            .enumerate()
            .max_by(|(_, a), (_, b)| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal))
            .map(|(i, _)| i)
            .unwrap_or(0);

        let latency = sw.elapsed_ms();
        let first = all_results
            .into_iter()
            .next()
            .unwrap_or_else(|| panic!("Ensemble must have at least one model result"));

        Ok(EnsembleResult {
            scores: ensemble_probs.clone(),
            probabilities: ensemble_probs,
            predicted_class,
            model_contributions,
            feature_importance: first.feature_importance,
            shap_values: None,
            latency_ms: latency,
            model_id: "ensemble".into(),
            model_version: "1.0".into(),
        })
    }
}
