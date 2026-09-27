use anyhow::{Context, Result};
use std::path::Path;
use tracing::info;

use crate::config::ModelConfig;

pub struct ModelMetadata {
    pub model_version: String,
    pub input_shape: Vec<usize>,
    pub output_shape: Vec<usize>,
    pub model_type: ModelType,
    pub loaded_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum ModelType {
    ArcFace,
    CosFace,
    Unknown,
}

pub struct ModelManager {
    models: Vec<ModelEntry>,
}

struct ModelEntry {
    model: tract_onnx::prelude::SimplePlan<
        tract_onnx::prelude::TypedFact,
        Box<dyn tract_onnx::prelude::TypedOp>,
        tract_onnx::prelude::Graph<
            tract_onnx::prelude::TypedFact,
            Box<dyn tract_onnx::prelude::TypedOp>,
        >,
    >,
    metadata: ModelMetadata,
}

impl ModelManager {
    pub fn new() -> Self {
        ModelManager { models: Vec::new() }
    }

    pub fn load_model(&mut self, model_path: &Path, config: &ModelConfig) -> Result<()> {
        info!(path = %model_path.display(), "Loading embedding model");

        let model = tract_onnx::prelude::onnx()
            .model_for_path(model_path)
            .context("Failed to load embedding ONNX model")?
            .with_input_fact(
                0,
                tract_onnx::prelude::InferenceFact::dt_shape(
                    tract_onnx::prelude::f32::datum_type(),
                    tvec!(1, 3, config.input_height as i64, config.input_width as i64),
                ),
            )
            .context("Failed to set input shape")?
            .into_optimized()
            .context("Failed to optimize embedding model")?
            .into_runnable()
            .context("Failed to make embedding model runnable")?;

        let metadata = ModelMetadata {
            model_version: Self::detect_version(model_path),
            input_shape: vec![
                1,
                3,
                config.input_height as usize,
                config.input_width as usize,
            ],
            output_shape: vec![1, config.embedding_dimension],
            model_type: Self::detect_model_type(model_path),
            loaded_at: chrono::Utc::now(),
        };

        self.warmup(&model, config)?;

        self.models.push(ModelEntry { model, metadata });

        info!(
            version = %metadata.model_version,
            model_type = ?metadata.model_type,
            "Model loaded successfully"
        );

        Ok(())
    }

    pub fn get_latest(&self) -> Option<&ModelEntry> {
        self.models.last()
    }

    pub fn get_version(&self, version: &str) -> Option<&ModelEntry> {
        self.models
            .iter()
            .rev()
            .find(|e| e.metadata.model_version == version)
    }

    pub fn metadata(&self) -> Option<&ModelMetadata> {
        self.models.last().map(|e| &e.metadata)
    }

    fn warmup(
        &self,
        model: &tract_onnx::prelude::SimplePlan<
            tract_onnx::prelude::TypedFact,
            Box<dyn tract_onnx::prelude::TypedOp>,
            tract_onnx::prelude::Graph<
                tract_onnx::prelude::TypedFact,
                Box<dyn tract_onnx::prelude::TypedOp>,
            >,
        >,
        config: &ModelConfig,
    ) -> Result<()> {
        info!("Warming up embedding model");

        let input = ndarray::Array4::<f32>::zeros((
            1,
            3,
            config.input_height as usize,
            config.input_width as usize,
        ));
        let tensor = tract_onnx::prelude::tensor4(
            input.as_slice().unwrap(),
            &[1, 3, config.input_height as i64, config.input_width as i64],
        )?;

        for i in 0..3 {
            let _ = model
                .run(tvec!(tensor.clone()))
                .context("Warmup inference failed")?;
            info!(iteration = i + 1, "Warmup completed");
        }

        info!("Model warmup completed");
        Ok(())
    }

    fn detect_version(model_path: &Path) -> String {
        if let Some(stem) = model_path.file_stem() {
            let name = stem.to_string_lossy();
            if let Some(ver) = name.rsplit('_').next() {
                if ver.starts_with('v') || ver.chars().all(|c| c.is_ascii_digit() || c == '.') {
                    return format!("v{}", ver.trim_start_matches('v'));
                }
            }
        }
        "v1.0.0".to_string()
    }

    fn detect_model_type(model_path: &Path) -> ModelType {
        let name = model_path
            .file_stem()
            .map(|s| s.to_string_lossy().to_lowercase())
            .unwrap_or_default();
        if name.contains("arcface") {
            ModelType::ArcFace
        } else if name.contains("cosface") {
            ModelType::CosFace
        } else {
            ModelType::Unknown
        }
    }
}
