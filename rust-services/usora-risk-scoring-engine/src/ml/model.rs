use crate::config::ModelConfig;
use crate::ml::{ModelEnsemble, ModelError};
use crate::models::{EnsembleResult, ModelMetadata, ModelMetrics, ModelType};
use crate::utils::{compute_checksum, Stopwatch};
use arc_swap::ArcSwap;
use async_trait::async_trait;
use chrono::Utc;
use ndarray::Array2;
use std::collections::HashMap;
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use tokio::sync::RwLock;
use tract_onnx::prelude::*;

pub struct ModelInstance {
    model: RunnableModel<TypedFact, Box<dyn TypedOp>, Box<dyn TypedOp>>,
    metadata: ModelMetadata,
    config: ModelConfig,
    inference_count: AtomicU64,
    total_latency_ms: AtomicU64,
    error_count: AtomicU64,
}

impl ModelInstance {
    pub fn load(config: &ModelConfig) -> Result<Self, ModelError> {
        let model_path = Path::new(&config.model_path);
        let model_data =
            std::fs::read(model_path).map_err(|e| ModelError::LoadFailed(e.to_string()))?;
        let checksum = compute_checksum(&model_data);

        let model = tract_onnx::onnx()
            .model_for_path(model_path)
            .map_err(|e| ModelError::LoadFailed(e.to_string()))?
            .with_input_fact(
                0,
                InferenceFact::dt_shape(f32::datum_type(), tvec!(1, config.input_features)),
            )
            .map_err(|e| ModelError::LoadFailed(e.to_string()))?
            .into_optimized()
            .map_err(|e| ModelError::LoadFailed(e.to_string()))?
            .into_runnable()
            .map_err(|e| ModelError::LoadFailed(e.to_string()))?;

        let metadata = ModelMetadata {
            model_id: config.model_id.clone(),
            model_type: ModelType::Ensemble,
            version: config.version.clone(),
            path: config.model_path.clone(),
            input_features: config.input_features,
            output_classes: config.output_classes,
            class_labels: config.class_labels.clone(),
            checksum,
            loaded_at: Utc::now(),
            metrics: ModelMetrics {
                inference_count: 0,
                avg_latency_ms: 0.0,
                p99_latency_ms: 0.0,
                error_rate: 0.0,
                drift_score: 0.0,
                last_drift_check: None,
            },
        };

        Ok(Self {
            model,
            metadata,
            config: config.clone(),
            inference_count: AtomicU64::new(0),
            total_latency_ms: AtomicU64::new(0),
            error_count: AtomicU64::new(0),
        })
    }

    pub fn run_inference(&self, input_features: &[f32]) -> Result<Vec<f32>, ModelError> {
        let tensor = Tensor::from_shape(&[1, self.config.input_features], input_features)
            .map_err(|e| ModelError::InferenceFailed(e.to_string()))?;

        let result = self
            .model
            .run(tvec!(tensor.into()))
            .map_err(|e| ModelError::InferenceFailed(e.to_string()))?;

        let output = result[0]
            .to_array_view::<f32>()
            .map_err(|e| ModelError::InferenceFailed(e.to_string()))?;

        Ok(output.iter().copied().collect())
    }

    pub fn run_batch_inference(
        &self,
        batch_features: &[Vec<f32>],
    ) -> Result<Vec<Vec<f32>>, ModelError> {
        let batch_size = batch_features.len();
        let flat: Vec<f32> = batch_features.iter().flatten().copied().collect();

        let tensor = Tensor::from_shape(&[batch_size, self.config.input_features], &flat)
            .map_err(|e| ModelError::InferenceFailed(e.to_string()))?;

        let result = self
            .model
            .run(tvec!(tensor.into()))
            .map_err(|e| ModelError::InferenceFailed(e.to_string()))?;

        let output = result[0]
            .to_array_view::<f32>()
            .map_err(|e| ModelError::InferenceFailed(e.to_string()))?;

        let batch_output: Vec<Vec<f32>> = output
            .outer_iter()
            .map(|row| row.iter().copied().collect())
            .collect();

        Ok(batch_output)
    }

    pub fn record_inference(&self, latency_ms: f64, success: bool) {
        self.inference_count.fetch_add(1, Ordering::Relaxed);
        self.total_latency_ms
            .fetch_add((latency_ms * 1000.0) as u64, Ordering::Relaxed);
        if !success {
            self.error_count.fetch_add(1, Ordering::Relaxed);
        }
    }

    pub fn current_metrics(&self) -> ModelMetrics {
        let count = self.inference_count.load(Ordering::Relaxed);
        let total_latency = self.total_latency_ms.load(Ordering::Relaxed) as f64 / 1000.0;
        let errors = self.error_count.load(Ordering::Relaxed);
        ModelMetrics {
            inference_count: count,
            avg_latency_ms: if count > 0 {
                total_latency / count as f64
            } else {
                0.0
            },
            p99_latency_ms: 0.0,
            error_rate: if count > 0 {
                errors as f64 / count as f64
            } else {
                0.0
            },
            drift_score: self.metadata.metrics.drift_score,
            last_drift_check: self.metadata.metrics.last_drift_check,
        }
    }
}

pub struct ModelLifecycle {
    current: ArcSwap<ModelInstance>,
    config: ModelConfig,
    reload_interval: tokio::sync::watch::Sender<()>,
    metrics_history: RwLock<Vec<ModelMetrics>>,
}

impl ModelLifecycle {
    pub fn new(config: ModelConfig) -> Result<Self, ModelError> {
        let instance = ModelInstance::load(&config)?;
        let (tx, _) = tokio::sync::watch::channel(());
        Ok(Self {
            current: ArcSwap::new(Arc::new(instance)),
            config,
            reload_interval: tx,
            metrics_history: RwLock::new(Vec::new()),
        })
    }

    pub fn current(&self) -> Arc<ModelInstance> {
        self.current.load().clone()
    }

    pub async fn reload(&self) -> Result<(), ModelError> {
        let new_instance = ModelInstance::load(&self.config)?;
        self.current.store(Arc::new(new_instance));
        let _ = self.reload_interval.send(());
        tracing::info!(model_id = %self.config.model_id, "Model reloaded");
        Ok(())
    }

    pub async fn reload_from_path(
        &self,
        model_path: &str,
        version: &str,
        checksum: &str,
    ) -> Result<(), ModelError> {
        let mut new_config = self.config.clone();
        new_config.model_path = model_path.to_string();
        new_config.version = version.to_string();
        let instance = ModelInstance::load(&new_config)?;
        if instance.metadata.checksum != checksum {
            return Err(ModelError::ChecksumMismatch);
        }
        self.current.store(Arc::new(instance));
        let _ = self.reload_interval.send(());
        tracing::info!(
            model_id = %self.config.model_id,
            version = %version,
            "Model updated from path"
        );
        Ok(())
    }

    pub async fn record_metrics(&self, metrics: ModelMetrics) {
        let mut history = self.metrics_history.write().await;
        history.push(metrics);
        if history.len() > 1000 {
            history.remove(0);
        }
    }

    pub async fn metrics_history(&self) -> Vec<ModelMetrics> {
        self.metrics_history.read().await.clone()
    }

    pub fn config(&self) -> &ModelConfig {
        &self.config
    }

    pub async fn hot_reload_loop(
        self: Arc<Self>,
        interval_seconds: u64,
        cancel: tokio_util::sync::CancellationToken,
    ) {
        let mut interval =
            tokio::time::interval(tokio::time::Duration::from_secs(interval_seconds));
        loop {
            tokio::select! {
                _ = interval.tick() => {
                    let model_path = Path::new(&self.config.model_path);
                    if !model_path.exists() {
                        tracing::warn!(path = %self.config.model_path, "Model file not found for hot-reload check");
                        continue;
                    }
                    if let Err(e) = self.reload().await {
                        tracing::error!(error = %e, "Hot-reload failed");
                    }
                }
                _ = cancel.cancelled() => break,
            }
        }
    }
}

pub struct ModelRegistry {
    models: RwLock<HashMap<String, Arc<ModelLifecycle>>>,
}

impl ModelRegistry {
    pub fn new() -> Self {
        Self {
            models: RwLock::new(HashMap::new()),
        }
    }

    pub async fn register(&self, config: ModelConfig) -> Result<(), ModelError> {
        let lifecycle = ModelLifecycle::new(config.clone())?;
        self.models
            .write()
            .await
            .insert(config.model_id.clone(), Arc::new(lifecycle));
        Ok(())
    }

    pub async fn get(&self, model_id: &str) -> Option<Arc<ModelLifecycle>> {
        self.models.read().await.get(model_id).cloned()
    }

    pub async fn unregister(&self, model_id: &str) {
        self.models.write().await.remove(model_id);
    }

    pub async fn list(&self) -> Vec<String> {
        self.models.read().await.keys().cloned().collect()
    }

    pub async fn reload_all(&self) -> Vec<(String, Result<(), ModelError>)> {
        let models: Vec<Arc<ModelLifecycle>> = self.models.read().await.values().cloned().collect();
        let mut results = Vec::new();
        for m in models {
            let id = m.config().model_id.clone();
            let result = m.reload().await;
            results.push((id, result));
        }
        results
    }
}

#[async_trait]
impl ModelEnsemble for ModelLifecycle {
    async fn predict(&self, features: &HashMap<String, f64>) -> Result<EnsembleResult, ModelError> {
        let instance = self.current();
        let sw = Stopwatch::start();

        let input: Vec<f32> = {
            let cfg = &instance.config;
            (0..cfg.input_features)
                .map(|i| {
                    let key = format!("feature_{}", i);
                    features.get(&key).copied().unwrap_or(0.0) as f32
                })
                .collect()
        };

        let output = match instance.run_inference(&input) {
            Ok(o) => o,
            Err(e) => {
                instance.record_inference(sw.elapsed_ms(), false);
                return Err(e);
            }
        };

        let latency = sw.elapsed_ms();
        instance.record_inference(latency, true);

        let probabilities: Vec<f64> = {
            let max = output.iter().cloned().fold(f32::NEG_INFINITY, f32::max) as f64;
            let exp: Vec<f64> = output.iter().map(|&v| ((v as f64) - max).exp()).collect();
            let sum: f64 = exp.iter().sum();
            if sum > f64::EPSILON {
                exp.iter().map(|&v| v / sum).collect()
            } else {
                vec![1.0 / output.len() as f64; output.len()]
            }
        };

        let predicted_class = probabilities
            .iter()
            .enumerate()
            .max_by(|(_, a), (_, b)| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal))
            .map(|(i, _)| i)
            .unwrap_or(0);

        let score = instance.config.class_labels[predicted_class]
            .parse::<f64>()
            .unwrap_or_else(|_| match predicted_class {
                0 => 0.0,
                1 => 0.3,
                2 => 0.7,
                3 => 0.9,
                _ => 0.5,
            });

        let mut feature_importance = HashMap::new();
        for (i, prob) in probabilities.iter().enumerate() {
            feature_importance.insert(format!("class_{}", instance.config.class_labels[i]), *prob);
        }

        Ok(EnsembleResult {
            scores: output.iter().map(|&v| v as f64).collect(),
            probabilities,
            predicted_class,
            model_contributions: feature_importance.clone(),
            feature_importance,
            shap_values: None,
            latency_ms: latency,
            model_id: instance.metadata.model_id.clone(),
            model_version: instance.metadata.version.clone(),
        })
    }

    async fn predict_batch(
        &self,
        batch: &[HashMap<String, f64>],
    ) -> Result<Vec<EnsembleResult>, ModelError> {
        let instance = self.current();
        let cfg = &instance.config;
        if batch.len() > cfg.input_features {
            return Err(ModelError::BatchTooLarge {
                size: batch.len(),
                max: cfg.input_features,
            });
        }

        let sw = Stopwatch::start();
        let batch_input: Vec<Vec<f32>> = batch
            .iter()
            .map(|features| {
                (0..cfg.input_features)
                    .map(|i| {
                        let key = format!("feature_{}", i);
                        features.get(&key).copied().unwrap_or(0.0) as f32
                    })
                    .collect()
            })
            .collect();

        let outputs = match instance.run_batch_inference(&batch_input) {
            Ok(o) => o,
            Err(e) => {
                instance.record_inference(sw.elapsed_ms(), false);
                return Err(e);
            }
        };

        let latency = sw.elapsed_ms();
        instance.record_inference(latency, true);

        let results: Vec<EnsembleResult> = outputs
            .into_iter()
            .map(|output| {
                let probabilities: Vec<f64> = {
                    let max = output.iter().cloned().fold(f32::NEG_INFINITY, f32::max) as f64;
                    let exp: Vec<f64> = output.iter().map(|&v| ((v as f64) - max).exp()).collect();
                    let sum: f64 = exp.iter().sum();
                    if sum > f64::EPSILON {
                        exp.iter().map(|&v| v / sum).collect()
                    } else {
                        vec![1.0 / output.len() as f64; output.len()]
                    }
                };

                let predicted_class = probabilities
                    .iter()
                    .enumerate()
                    .max_by(|(_, a), (_, b)| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal))
                    .map(|(i, _)| i)
                    .unwrap_or(0);

                let mut fi = HashMap::new();
                for (i, prob) in probabilities.iter().enumerate() {
                    fi.insert(format!("class_{}", cfg.class_labels[i]), *prob);
                }

                EnsembleResult {
                    scores: output.iter().map(|&v| v as f64).collect(),
                    probabilities,
                    predicted_class,
                    model_contributions: fi.clone(),
                    feature_importance: fi,
                    shap_values: None,
                    latency_ms: latency,
                    model_id: instance.metadata.model_id.clone(),
                    model_version: instance.metadata.version.clone(),
                }
            })
            .collect();

        Ok(results)
    }

    async fn explain(
        &self,
        features: &HashMap<String, f64>,
        _result: &EnsembleResult,
    ) -> Result<HashMap<String, f64>, ModelError> {
        let instance = self.current();
        let base_input: Vec<f64> = {
            let cfg = &instance.config;
            (0..cfg.input_features)
                .map(|i| {
                    let key = format!("feature_{}", i);
                    features.get(&key).copied().unwrap_or(0.0)
                })
                .collect()
        };

        let mut contributions = HashMap::new();
        let base_pred = self
            .predict(features)
            .await?
            .probabilities
            .iter()
            .enumerate()
            .max_by(|(_, a), (_, b)| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal))
            .map(|(i, _)| i)
            .unwrap_or(0);

        for (i, &val) in base_input.iter().enumerate() {
            let mut perturbed = base_input.clone();
            perturbed[i] = 0.0;
            let perturbed_map: HashMap<String, f64> = perturbed
                .into_iter()
                .enumerate()
                .map(|(j, v)| (format!("feature_{}", j), v))
                .collect();

            let perturbed_pred = self
                .predict(&perturbed_map)
                .await?
                .probabilities
                .iter()
                .enumerate()
                .max_by(|(_, a), (_, b)| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal))
                .map(|(i, _)| i)
                .unwrap_or(0);

            let importance = (base_pred as f64 - perturbed_pred as f64).abs();
            if importance > 0.001 {
                contributions.insert(format!("feature_{}", i), importance);
            }
        }

        let total: f64 = contributions.values().sum();
        if total > 0.0 {
            for v in contributions.values_mut() {
                *v /= total;
            }
        }

        Ok(contributions)
    }

    fn metadata(&self) -> &ModelMetadata {
        &self.current().metadata
    }

    fn name(&self) -> &str {
        &self.config.model_id
    }

    fn version(&self) -> &str {
        &self.config.version
    }
}
