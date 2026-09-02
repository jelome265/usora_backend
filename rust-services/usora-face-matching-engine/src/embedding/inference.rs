use anyhow::{Context, Result};
use async_trait::async_trait;
use image::DynamicImage;
use std::path::Path;
use tract_onnx::prelude::Framework;
use tracing::{info_span, span, Level};

use crate::detection::DetectedFace;
use crate::embedding::{EmbeddingModel, EmbeddingVector, FaceEmbedding};
use crate::utils;

pub struct OnnxEmbeddingModel {
    model: tract_onnx::prelude::SimplePlan<
        tract_onnx::prelude::TypedFact,
        Box<dyn tract_onnx::prelude::TypedOp>,
        tract_onnx::prelude::Graph<
            tract_onnx::prelude::TypedFact,
            Box<dyn tract_onnx::prelude::TypedOp>,
        >,
    >,
    dimension: usize,
    input_width: u32,
    input_height: u32,
    model_version: String,
}

impl OnnxEmbeddingModel {
    pub fn new(
        model_path: &Path,
        dimension: usize,
        input_width: u32,
        input_height: u32,
    ) -> Result<Self> {
        let _span = span!(Level::INFO, "load_embedding_model", path = %model_path.display());

        let model = tract_onnx::prelude::onnx()
            .model_for_path(model_path)
            .context("Failed to load embedding ONNX model")?
            .with_input_fact(
                0,
                tract_onnx::prelude::InferenceFact::dt_shape(
                    tract_onnx::prelude::f32::datum_type(),
                    tvec!(1, 3, input_height as i64, input_width as i64),
                ),
            )
            .context("Failed to set input shape")?
            .into_optimized()
            .context("Failed to optimize embedding model")?
            .into_runnable()
            .context("Failed to make embedding model runnable")?;

        let model_version = Self::derive_version(model_path);

        let instance = OnnxEmbeddingModel {
            model,
            dimension,
            input_width,
            input_height,
            model_version,
        };

        instance.warmup()?;

        Ok(instance)
    }

    fn derive_version(model_path: &Path) -> String {
        if let Some(stem) = model_path.file_stem() {
            let name = stem.to_string_lossy();
            if let Some(ver) = name.rsplit('_').next() {
                if ver.starts_with('v') || ver.chars().all(|c| c.is_ascii_digit() || c == '.') {
                    return format!("v{}", ver.trim_start_matches('v'));
                }
            }
        }
        env!("CARGO_PKG_VERSION").to_string()
    }

    fn warmup(&self) -> Result<()> {
        let input = ndarray::Array4::<f32>::zeros((
            1,
            3,
            self.input_height as usize,
            self.input_width as usize,
        ));
        let tensor = tract_onnx::prelude::Tensor::from_slice(input.as_slice().unwrap())?
            .into_shape(&[1, 3, self.input_height as usize, self.input_width as usize])?;

        for _ in 0..3 {
            let _ = self
                .model
                .run(tvec!(tensor.clone()))
                .context("Warmup inference failed")?;
        }

        Ok(())
    }

    fn preprocess(image: &DynamicImage, width: u32, height: u32) -> Result<ndarray::Array4<f32>> {
        let resized = image.resize_exact(width, height, image::imageops::FilterType::Lanczos3);
        let rgb = resized.to_rgb8();

        let mut tensor = ndarray::Array4::<f32>::zeros((1, 3, height as usize, width as usize));
        let mean: [f32; 3] = [0.5, 0.5, 0.5];
        let std: [f32; 3] = [0.5, 0.5, 0.5];

        for y in 0..height {
            for x in 0..width {
                let pixel = rgb.get_pixel(x, y);
                for c in 0..3 {
                    tensor[[0, c, y as usize, x as usize]] =
                        (pixel[c] as f32 / 255.0 - mean[c]) / std[c];
                }
            }
        }

        Ok(tensor)
    }

    fn run_inference(&self, tensor: ndarray::Array4<f32>) -> Result<EmbeddingVector> {
        let input = tract_onnx::prelude::Tensor::from_slice(tensor.as_slice().unwrap())?
            .into_shape(&[1, 3, self.input_height as usize, self.input_width as usize])?;

        let result = self
            .model
            .run(tvec!(input))
            .context("Embedding inference failed")?;

        let output = result[0]
            .to_array_view::<f32>()
            .context("Failed to get output array")?;

        let mut embedding: Vec<f32> = output.iter().copied().collect();
        utils::normalize_l2(&mut embedding);

        Ok(embedding)
    }
}

#[async_trait]
impl EmbeddingModel for OnnxEmbeddingModel {
    async fn generate_embedding(
        &self,
        image: &DynamicImage,
        face: &DetectedFace,
    ) -> Result<FaceEmbedding> {
        let (cropped, input_w, input_h) = {
            let span = info_span!("generate_embedding");
            let _guard = span.enter();
            let cropped = utils::crop_face(image, face)?;
            (cropped, self.input_width, self.input_height)
        };

        let tensor = Self::preprocess(&cropped, input_w, input_h)?;

        let vector = tokio::task::spawn_blocking(move || self.run_inference(tensor))
            .await
            .context("Embedding spawn blocking failed")??;

        Ok(FaceEmbedding {
            vector,
            dimension: self.dimension,
            model_version: self.model_version.clone(),
            confidence: face.confidence as f64,
            face_id: None,
        })
    }

    async fn generate_embeddings_batch(
        &self,
        images: &[(&DynamicImage, &DetectedFace)],
    ) -> Result<Vec<FaceEmbedding>> {
        let mut embeddings = Vec::with_capacity(images.len());

        for (image, face) in images {
            let emb = self.generate_embedding(image, face).await?;
            embeddings.push(emb);
        }

        Ok(embeddings)
    }

    fn dimension(&self) -> usize {
        self.dimension
    }

    fn model_version(&self) -> &str {
        &self.model_version
    }
}
