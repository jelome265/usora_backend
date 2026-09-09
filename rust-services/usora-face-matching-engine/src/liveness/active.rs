use anyhow::{Context, Result};
use async_trait::async_trait;
use image::{DynamicImage, GenericImageView};
use std::path::Path;
use tracing::{info, warn};

use crate::detection::DetectedFace;
use crate::liveness::{LivenessDetector, LivenessResult};
use crate::models::{ActionVerification, LivenessDetails, SpoofType};
use crate::utils;

pub struct ActiveLivenessDetector {
    liveness_threshold: f64,
    challenge_types: Vec<String>,
    model: Option<ActiveLivenessModel>,
}

enum ActiveLivenessModel {
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
    Heuristic,
}

impl ActiveLivenessDetector {
    pub fn new(model_path: Option<&Path>) -> Result<Self> {
        let model = if let Some(path) = model_path {
            if path.exists() {
                info!(path = %path.display(), "Loading active liveness ONNX model");
                let m = tract_onnx::prelude::onnx()
                    .model_for_path(path)
                    .context("Failed to load active liveness model")?
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
                Some(ActiveLivenessModel::Onnx(m))
            } else {
                warn!("Liveness model not found, using heuristic fallback");
                None
            }
        } else {
            None
        };

        Ok(ActiveLivenessDetector {
            liveness_threshold: 0.90,
            challenge_types: vec![
                "blink".into(),
                "smile".into(),
                "turn_left".into(),
                "turn_right".into(),
            ],
            model,
        })
    }

    fn detect_blink(&self, image: &DynamicImage, face: &DetectedFace) -> f64 {
        if face.landmarks.len() < 6 {
            return 0.5;
        }

        let left_eye_top = face.landmarks[4];
        let left_eye_bottom = face.landmarks[5];
        let eye_height = (left_eye_top[1] - left_eye_bottom[1]).abs();

        let cropped = utils::crop_face(image, face).unwrap_or_else(|_| image.clone());
        let gray = cropped.to_luma8();
        let (w, h) = gray.dimensions();

        if w == 0 || h == 0 {
            return 0.5;
        }

        let eye_region_y = (left_eye_top[1] + left_eye_bottom[1]) as u32 / 2;
        let eye_region_y_rel = eye_region_y.saturating_sub(face.bbox.y1 as u32);

        if eye_region_y_rel >= h {
            return 0.5;
        }

        let eye_row: Vec<u8> = (0..w.min(40))
            .map(|x| gray.get_pixel(x, eye_region_y_rel)[0])
            .collect();

        if eye_row.is_empty() {
            return 0.5;
        }

        let mean: f64 = eye_row.iter().map(|&p| p as f64).sum::<f64>() / eye_row.len() as f64;
        let variance: f64 = eye_row
            .iter()
            .map(|&p| (p as f64 - mean).powi(2))
            .sum::<f64>()
            / eye_row.len() as f64;

        let openness_score = if variance < 50.0 && eye_height < 5.0 {
            0.0
        } else if variance > 200.0 && eye_height > 8.0 {
            1.0
        } else {
            (variance / 300.0).min(1.0)
        };

        openness_score
    }

    fn detect_smile(&self, image: &DynamicImage, face: &DetectedFace) -> f64 {
        if face.landmarks.len() < 5 {
            return 0.5;
        }

        let left_mouth = face.landmarks[3];
        let right_mouth = face.landmarks[4];
        let mouth_width = (right_mouth[0] - left_mouth[0]).abs();

        let face_width = face.bbox.width();
        if face_width <= 0.0 {
            return 0.5;
        }

        let normalized_mouth_width = mouth_width / face_width;

        let cropped = utils::crop_face(image, face).unwrap_or_else(|_| image.clone());
        let gray = cropped.to_luma8();
        let (w, h) = gray.dimensions();

        let mouth_y = ((left_mouth[1] + right_mouth[1]) / 2.0) as u32;
        let mouth_y_rel = mouth_y.saturating_sub(face.bbox.y1 as u32);

        if mouth_y_rel >= h || w == 0 {
            return 0.5;
        }

        let mouth_row: Vec<u8> = (0..w).map(|x| gray.get_pixel(x, mouth_y_rel)[0]).collect();
        let mean: f64 = mouth_row.iter().map(|&p| p as f64).sum::<f64>() / mouth_row.len() as f64;

        let dark_count = mouth_row
            .iter()
            .filter(|&&p| (p as f64) < mean - 20.0)
            .count();

        let smile_score = if normalized_mouth_width > 0.5 && dark_count > mouth_row.len() / 4 {
            1.0
        } else if normalized_mouth_width > 0.4 {
            0.7
        } else if normalized_mouth_width > 0.3 {
            0.4
        } else {
            0.1
        };

        smile_score
    }

    fn detect_head_turn(&self, image: &DynamicImage, face: &DetectedFace, direction: &str) -> f64 {
        if face.landmarks.len() < 5 {
            return 0.5;
        }

        let left_eye = face.landmarks[0];
        let right_eye = face.landmarks[1];
        let nose = face.landmarks[2];

        let eye_mid_x = (left_eye[0] + right_eye[0]) / 2.0;
        let nose_offset = nose[0] - eye_mid_x;
        let face_width = face.bbox.width();

        if face_width <= 0.0 {
            return 0.5;
        }

        let normalized_offset = nose_offset / face_width;

        match direction {
            "turn_left" => {
                if normalized_offset < -0.05 {
                    (normalized_offset.abs() * 5.0).min(1.0)
                } else {
                    0.0
                }
            }
            "turn_right" => {
                if normalized_offset > 0.05 {
                    (normalized_offset * 5.0).min(1.0)
                } else {
                    0.0
                }
            }
            _ => 0.5,
        }
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

        let input = tract_onnx::prelude::tensor4(tensor.as_slice().unwrap(), &[1, 3, 112, 112])?;

        let result = model.run(tvec!(input))?;
        let output = result[0].to_array_view::<f32>()?;

        let liveness_score = output.iter().copied().sum::<f32>() as f64 / output.len() as f64;
        Ok(liveness_score.min(1.0).max(0.0))
    }
}

#[async_trait]
impl LivenessDetector for ActiveLivenessDetector {
    async fn check_liveness(
        &self,
        image: &DynamicImage,
        face: &DetectedFace,
        challenge: Option<&str>,
    ) -> Result<LivenessResult> {
        let challenge_type = challenge.unwrap_or("blink");
        let action_score = match challenge_type {
            "blink" => self.detect_blink(image, face),
            "smile" => self.detect_smile(image, face),
            "turn_left" | "turn_right" => self.detect_head_turn(image, face, challenge_type),
            _ => 0.5,
        };

        let model_score = match &self.model {
            Some(ActiveLivenessModel::Onnx(m)) => self.run_onnx_inference(m, image, face)?,
            _ => action_score,
        };

        let overall_confidence = model_score * 0.6 + action_score * 0.4;
        let is_live = overall_confidence >= self.liveness_threshold();

        let spoof_type = if is_live {
            SpoofType::None
        } else {
            SpoofType::Replay
        };

        Ok(LivenessResult {
            is_live,
            confidence: overall_confidence,
            spoof_type,
            details: LivenessDetails {
                texture_score: model_score,
                motion_score: action_score,
                depth_score: 0.0,
                color_distribution_score: 0.0,
                specular_reflection_score: 0.0,
                micro_expression_score: action_score,
                action_verification: Some(ActionVerification {
                    actions_requested: vec![challenge_type.to_string()],
                    actions_performed: if is_live {
                        vec![challenge_type.to_string()]
                    } else {
                        vec![]
                    },
                    action_scores: vec![action_score],
                    overall_score: action_score,
                }),
            },
        })
    }

    async fn estimate_spoof_type(
        &self,
        _image: &DynamicImage,
        _face: &DetectedFace,
    ) -> Result<SpoofType> {
        Ok(SpoofType::None)
    }

    fn liveness_threshold(&self) -> f64 {
        self.liveness_threshold
    }
}
