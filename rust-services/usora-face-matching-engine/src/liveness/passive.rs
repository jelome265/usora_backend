use anyhow::{Context, Result};
use async_trait::async_trait;
use image::{DynamicImage, GenericImageView};
use std::path::Path;
use tract_onnx::prelude::Framework;
use tracing::{info, warn};

use crate::detection::DetectedFace;
use crate::liveness::{LivenessDetector, LivenessResult};
use crate::models::{LivenessDetails, SpoofType};
use crate::utils;

pub struct PassiveLivenessDetector {
    liveness_threshold: f64,
    model: Option<PassiveLivenessModel>,
}

enum PassiveLivenessModel {
    Onnx(
        tract_onnx::prelude::SimplePlan<
            tract_onnx::prelude::TypedFact,
            Box<dyn tract_onnx::prelude::TypedOp>,
            tract_onnx::prelude::Graph<
                tract_onnx::prelude::TypedFact,
                Box<dyn tract_onnx::prelude::TypedOp>,
            >,
        >,
    ),
    TextureBased,
}

impl PassiveLivenessDetector {
    pub fn new(model_path: Option<&Path>) -> Result<Self> {
        let model = if let Some(path) = model_path {
            if path.exists() {
                info!(path = %path.display(), "Loading passive liveness ONNX model");
                let m = tract_onnx::prelude::onnx()
                    .model_for_path(path)
                    .context("Failed to load passive liveness model")?
                    .with_input_fact(
                        0,
                        tract_onnx::prelude::InferenceFact::dt_shape(
                            tract_onnx::prelude::f32::datum_type(),
                            tvec!(1, 3, 112, 112),
                        ),
                    )
                    .context("Failed to set input shape")?
                    .into_optimized()
                    .context("Failed to optimize model")?
                    .into_runnable()
                    .context("Failed to make model runnable")?;
                Some(PassiveLivenessModel::Onnx(m))
            } else {
                warn!("Passive liveness model not found, using texture-based fallback");
                None
            }
        } else {
            None
        };

        Ok(PassiveLivenessDetector {
            liveness_threshold: 0.90,
            model,
        })
    }

    fn analyze_texture(
        &self,
        image: &DynamicImage,
        face: &DetectedFace,
    ) -> Result<TextureAnalysis> {
        let cropped = utils::crop_face(image, face)?;
        let gray = cropped.to_luma8();
        let (w, h) = gray.dimensions();

        if w == 0 || h == 0 {
            anyhow::bail!("Empty face crop for texture analysis");
        }

        let lbp_uniformity = self.compute_lbp_uniformity(&gray);
        let frequency_analysis = self.compute_frequency_analysis(&gray);
        let color_distribution = self.analyze_color_distribution(&cropped);
        let specular_reflection = self.detect_specular_reflection(&cropped);
        let gradient_consistency = self.compute_gradient_consistency(&gray);
        let texture_variance = self.compute_texture_variance(&gray);

        let overall_texture_score = (lbp_uniformity * 0.20
            + frequency_analysis * 0.20
            + color_distribution * 0.20
            + (1.0 - specular_reflection) * 0.15
            + gradient_consistency * 0.15
            + texture_variance * 0.10);

        Ok(TextureAnalysis {
            lbp_uniformity,
            frequency_analysis,
            color_distribution,
            specular_reflection,
            gradient_consistency,
            texture_variance,
            overall_texture_score,
        })
    }

    fn compute_lbp_uniformity(&self, gray: &image::GrayImage) -> f64 {
        let (w, h) = gray.dimensions();
        let mut uniform_count = 0u64;
        let mut total_count = 0u64;

        for y in 1..(h - 1) {
            for x in 1..(w - 1) {
                let center_val = gray.get_pixel(x, y)[0];
                let mut lbp = 0u8;
                let neighbors = [
                    (-1i32, -1i32),
                    (0, -1),
                    (1, -1),
                    (1, 0),
                    (1, 1),
                    (0, 1),
                    (-1, 1),
                    (-1, 0),
                ];

                for (i, (dx, dy)) in neighbors.iter().enumerate() {
                    let nx = (x as i32 + dx) as u32;
                    let ny = (y as i32 + dy) as u32;
                    if gray.get_pixel(nx, ny)[0] >= center_val {
                        lbp |= 1 << i;
                    }
                }

                let transitions = self.count_bit_transitions(lbp);
                if transitions <= 2 {
                    uniform_count += 1;
                }
                total_count += 1;
            }
        }

        if total_count == 0 {
            return 0.5;
        }
        uniform_count as f64 / total_count as f64
    }

    fn count_bit_transitions(&self, mut byte: u8) -> u32 {
        let mut count = 0u32;
        let mut prev = byte & 1;
        for _ in 0..8 {
            let current = byte & 1;
            if current != prev {
                count += 1;
            }
            prev = current;
            byte >>= 1;
        }
        count
    }

    fn compute_frequency_analysis(&self, gray: &image::GrayImage) -> f64 {
        let (w, h) = gray.dimensions();
        let mut high_freq_energy = 0.0f64;
        let mut total_energy = 0.0f64;

        for y in 1..(h - 1) {
            for x in 1..(w - 1) {
                let center = gray.get_pixel(x, y)[0] as f64;
                let left = gray.get_pixel(x - 1, y)[0] as f64;
                let right = gray.get_pixel(x + 1, y)[0] as f64;
                let top = gray.get_pixel(x, y - 1)[0] as f64;
                let bottom = gray.get_pixel(x, y + 1)[0] as f64;

                let horizontal_diff = (right - left).abs();
                let vertical_diff = (bottom - top).abs();
                let local_energy = horizontal_diff + vertical_diff;
                total_energy += local_energy;

                if horizontal_diff > 30.0 || vertical_diff > 30.0 {
                    high_freq_energy += local_energy;
                }
            }
        }

        if total_energy == 0.0 {
            return 0.5;
        }
        (high_freq_energy / total_energy).min(1.0)
    }

    fn analyze_color_distribution(&self, image: &DynamicImage) -> f64 {
        let (w, h) = image.dimensions();
        let total = (w * h) as f64;

        if total == 0.0 {
            return 0.5;
        }

        let rgb = image.to_rgb8();
        let mut r_sum = 0u64;
        let mut g_sum = 0u64;
        let mut b_sum = 0u64;

        for y in 0..h {
            for x in 0..w {
                let pixel = rgb.get_pixel(x, y);
                r_sum += pixel[0] as u64;
                g_sum += pixel[1] as u64;
                b_sum += pixel[2] as u64;
            }
        }

        let r_mean = r_sum as f64 / total;
        let g_mean = g_sum as f64 / total;
        let b_mean = b_sum as f64 / total;

        let mut r_var = 0.0f64;
        let mut g_var = 0.0f64;
        let mut b_var = 0.0f64;

        for y in 0..h {
            for x in 0..w {
                let pixel = rgb.get_pixel(x, y);
                r_var += (pixel[0] as f64 - r_mean).powi(2);
                g_var += (pixel[1] as f64 - g_mean).powi(2);
                b_var += (pixel[2] as f64 - b_mean).powi(2);
            }
        }

        r_var = (r_var / total).sqrt();
        g_var = (g_var / total).sqrt();
        b_var = (b_var / total).sqrt();

        let naturalness =
            1.0 - ((r_var - g_var).abs() + (g_var - b_var).abs() + (r_var - b_var).abs()) / 1530.0;
        naturalness.max(0.0).min(1.0)
    }

    fn detect_specular_reflection(&self, image: &DynamicImage) -> f64 {
        let (w, h) = image.dimensions();
        let rgb = image.to_rgb8();
        let mut specular_count = 0u64;
        let total = (w * h) as f64;

        if total == 0.0 {
            return 0.0;
        }

        for y in 0..h {
            for x in 0..w {
                let pixel = rgb.get_pixel(x, y);
                let max_ch = pixel[0].max(pixel[1].max(pixel[2])) as f64;
                let min_ch = pixel[0].min(pixel[1].min(pixel[2])) as f64;
                let intensity = (pixel[0] as f64 + pixel[1] as f64 + pixel[2] as f64) / 3.0;

                if intensity > 220.0 && (max_ch - min_ch) < 20.0 {
                    specular_count += 1;
                }
            }
        }

        specular_count as f64 / total
    }

    fn compute_gradient_consistency(&self, gray: &image::GrayImage) -> f64 {
        let (w, h) = gray.dimensions();
        let sobel_x: [[i32; 3]; 3] = [[-1, 0, 1], [-2, 0, 2], [-1, 0, 1]];
        let sobel_y: [[i32; 3]; 3] = [[-1, -2, -1], [0, 0, 0], [1, 2, 1]];

        let mut orientations = Vec::new();

        for y in 1..(h - 1) {
            for x in 1..(w - 1) {
                let mut gx = 0i32;
                let mut gy = 0i32;
                for ky in 0..3 {
                    for kx in 0..3 {
                        let px = gray.get_pixel(x + kx - 1, y + ky - 1)[0] as i32;
                        gx += px * sobel_x[ky as usize][kx as usize];
                        gy += px * sobel_y[ky as usize][kx as usize];
                    }
                }
                if gx != 0 || gy != 0 {
                    orientations.push((gy as f64).atan2(gx as f64));
                }
            }
        }

        if orientations.is_empty() {
            return 0.5;
        }

        let mean_orientation: f64 = orientations.iter().sum::<f64>() / orientations.len() as f64;
        let variance: f64 = orientations
            .iter()
            .map(|&o| (o - mean_orientation).powi(2))
            .sum::<f64>()
            / orientations.len() as f64;

        let consistency = 1.0 - (variance / std::f64::consts::PI).min(1.0);
        consistency
    }

    fn compute_texture_variance(&self, gray: &image::GrayImage) -> f64 {
        let (w, h) = gray.dimensions();
        let block_size = 16u32;
        let mut block_variances = Vec::new();

        for by in (0..h).step_by(block_size as usize) {
            for bx in (0..w).step_by(block_size as usize) {
                let mut block_pixels = Vec::new();
                for dy in 0..block_size.min(h - by) {
                    for dx in 0..block_size.min(w - bx) {
                        block_pixels.push(gray.get_pixel(bx + dx, by + dy)[0] as f64);
                    }
                }

                if block_pixels.len() < 4 {
                    continue;
                }

                let mean: f64 = block_pixels.iter().sum::<f64>() / block_pixels.len() as f64;
                let var: f64 = block_pixels
                    .iter()
                    .map(|&p| (p - mean).powi(2))
                    .sum::<f64>()
                    / block_pixels.len() as f64;
                block_variances.push(var);
            }
        }

        if block_variances.is_empty() {
            return 0.5;
        }

        let mean_var: f64 = block_variances.iter().sum::<f64>() / block_variances.len() as f64;
        let var_of_var: f64 = block_variances
            .iter()
            .map(|&v| (v - mean_var).powi(2))
            .sum::<f64>()
            / block_variances.len() as f64;

        let score = (var_of_var / 5000.0).min(1.0);
        score
    }

    fn run_onnx_inference(
        &self,
        model: &tract_onnx::prelude::SimplePlan<
            tract_onnx::prelude::TypedFact,
            Box<dyn tract_onnx::prelude::TypedOp>,
            tract_onnx::prelude::Graph<
                tract_onnx::prelude::TypedFact,
                Box<dyn tract_onnx::prelude::TypedOp>,
            >,
        >,
        image: &DynamicImage,
        face: &DetectedFace,
    ) -> Result<f64> {
        let cropped = utils::crop_face(image, face)?;
        let resized = cropped.resize_exact(112, 112, image::imageops::FilterType::Lanczos3);
        let rgb = resized.to_rgb8();

        let mut tensor = ndarray::Array4::<f32>::zeros((1, 3, 112, 112));
        for y in 0..112 {
            for x in 0..112 {
                let pixel = rgb.get_pixel(x, y);
                for c in 0..3 {
                    tensor[[0, c, y as usize, x as usize]] = pixel[c] as f32 / 255.0;
                }
            }
        }

        let input = tract_onnx::prelude::Tensor::from_slice(tensor.as_slice().unwrap())?
            .into_shape(&[1, 3, 112, 112])?;

        let result = model.run(tvec!(input))?;
        let output = result[0].to_array_view::<f32>()?;

        let score = output.iter().copied().sum::<f32>() as f64 / output.len() as f64;
        Ok(score.min(1.0).max(0.0))
    }

    fn classify_spoof_type(&self, texture: &TextureAnalysis) -> SpoofType {
        if texture.specular_reflection > 0.15 {
            SpoofType::Replay
        } else if texture.lbp_uniformity < 0.3 && texture.texture_variance < 0.2 {
            SpoofType::SiliconeMask
        } else if texture.frequency_analysis > 0.8 && texture.gradient_consistency < 0.3 {
            SpoofType::Deepfake
        } else if texture.lbp_uniformity < 0.4 && texture.texture_variance < 0.3 {
            SpoofType::Print3d
        } else {
            SpoofType::None
        }
    }
}

struct TextureAnalysis {
    lbp_uniformity: f64,
    frequency_analysis: f64,
    color_distribution: f64,
    specular_reflection: f64,
    gradient_consistency: f64,
    texture_variance: f64,
    overall_texture_score: f64,
}

#[async_trait]
impl LivenessDetector for PassiveLivenessDetector {
    async fn check_liveness(
        &self,
        image: &DynamicImage,
        face: &DetectedFace,
        _challenge: Option<&str>,
    ) -> Result<LivenessResult> {
        let texture = self.analyze_texture(image, face)?;

        let model_score = match &self.model {
            Some(PassiveLivenessModel::Onnx(m)) => {
                let onnx_score = self.run_onnx_inference(m, image, face)?;
                onnx_score * 0.7 + texture.overall_texture_score * 0.3
            }
            _ => texture.overall_texture_score,
        };

        let is_live = model_score >= self.liveness_threshold();
        let spoof_type = if is_live {
            SpoofType::None
        } else {
            self.classify_spoof_type(&texture)
        };

        Ok(LivenessResult {
            is_live,
            confidence: model_score,
            spoof_type,
            details: LivenessDetails {
                texture_score: texture.overall_texture_score,
                motion_score: 0.0,
                depth_score: 0.0,
                color_distribution_score: texture.color_distribution,
                specular_reflection_score: texture.specular_reflection,
                micro_expression_score: texture.lbp_uniformity,
                action_verification: None,
            },
        })
    }

    async fn estimate_spoof_type(
        &self,
        image: &DynamicImage,
        face: &DetectedFace,
    ) -> Result<SpoofType> {
        let texture = self.analyze_texture(image, face)?;
        Ok(self.classify_spoof_type(&texture))
    }

    fn liveness_threshold(&self) -> f64 {
        self.liveness_threshold
    }
}
