use anyhow::{Context, Result};
use async_trait::async_trait;
use image::{DynamicImage, GenericImageView, Pixel};
use ndarray::{s, Array, Array3, Axis};
use std::path::Path;
use std::sync::Arc;

use crate::detection::{DetectedFace, FaceDetector};
use crate::utils::{self, non_maximum_suppression, BBox};

pub struct OnnxFaceDetector {
    model: tract_onnx::prelude::SimplePlan<
        tract_onnx::prelude::TypedFact,
        Box<dyn tract_onnx::prelude::TypedOp>,
        tract_onnx::prelude::Graph<
            tract_onnx::prelude::TypedFact,
            Box<dyn tract_onnx::prelude::TypedOp>,
        >,
    >,
    input_width: u32,
    input_height: u32,
    min_face_size: u32,
    confidence_threshold: f32,
    anchors: Vec<Anchor>,
}

#[derive(Debug, Clone)]
struct Anchor {
    cx: f32,
    cy: f32,
    w: f32,
    h: f32,
}

impl OnnxFaceDetector {
    pub fn new(
        model_path: &Path,
        input_width: u32,
        input_height: u32,
        min_face_size: u32,
        confidence_threshold: f32,
    ) -> Result<Self> {
        let model = tract_onnx::prelude::onnx()
            .model_for_path(model_path)
            .context("Failed to load face detection ONNX model")?
            .with_input_fact(
                0,
                tract_onnx::prelude::InferenceFact::dt_shape(
                    tract_onnx::prelude::f32::datum_type(),
                    tvec!(1, 3, input_height as i64, input_width as i64),
                ),
            )
            .context("Failed to set input shape")?
            .into_optimized()
            .context("Failed to optimize face detection model")?
            .into_runnable()
            .context("Failed to make face detection model runnable")?;

        let anchors = Self::generate_anchors(input_width, input_height);

        Ok(OnnxFaceDetector {
            model,
            input_width,
            input_height,
            min_face_size,
            confidence_threshold,
            anchors,
        })
    }

    fn generate_anchors(image_width: u32, image_height: u32) -> Vec<Anchor> {
        let mut anchors = Vec::new();
        let strides = [8, 16, 32];
        let min_sizes = [vec![16.0, 32.0], vec![64.0, 128.0], vec![256.0, 512.0]];

        for (stride_idx, &stride) in strides.iter().enumerate() {
            let feature_w = (image_width as f32 / stride as f32).ceil() as u32;
            let feature_h = (image_height as f32 / stride as f32).ceil() as u32;

            for iy in 0..feature_h {
                for ix in 0..feature_w {
                    let cx = (ix as f32 + 0.5) * stride as f32;
                    let cy = (iy as f32 + 0.5) * stride as f32;

                    for &min_size in &min_sizes[stride_idx] {
                        let w = min_size;
                        let h = min_size;
                        anchors.push(Anchor { cx, cy, w, h });

                        let w2 = min_size * 2.0_f32.sqrt();
                        let h2 = min_size / 2.0_f32.sqrt();
                        anchors.push(Anchor {
                            cx,
                            cy,
                            w: w2,
                            h: h2,
                        });
                    }
                }
            }
        }

        anchors
    }

    fn preprocess(
        image: &DynamicImage,
        width: u32,
        height: u32,
    ) -> Result<(Array3<f32>, f32, f32)> {
        let (orig_w, orig_h) = image.dimensions();
        let scale = (width as f32 / orig_w as f32).min(height as f32 / orig_h as f32);
        let new_w = (orig_w as f32 * scale) as u32;
        let new_h = (orig_h as f32 * scale) as u32;

        let resized = image.resize_exact(new_w, new_h, image::imageops::FilterType::Lanczos3);

        let mut padded = DynamicImage::new_rgb8(width, height);
        let offset_x = (width - new_w) / 2;
        let offset_y = (height - new_h) / 2;
        image::imageops::overlay(&mut padded, &resized, offset_x as i64, offset_y as i64);

        let rgb = padded.to_rgb8();
        let mut tensor = Array3::<f32>::zeros((3, height as usize, width as usize));

        for y in 0..height {
            for x in 0..width {
                let pixel = rgb.get_pixel(x, y).to_rgb();
                for c in 0..3 {
                    tensor[[c, y as usize, x as usize]] = (pixel[c] as f32 / 255.0 - 0.5) / 0.5;
                }
            }
        }

        Ok((tensor, scale, (offset_x as f32, offset_y as f32)))
    }

    fn decode_outputs(
        &self,
        output: &tract_onnx::prelude::TValue,
        scale: f32,
        offset: (f32, f32),
        orig_width: u32,
        orig_height: u32,
    ) -> Vec<DetectedFace> {
        let data = output.to_array_view::<f32>().unwrap();
        let shape = data.shape();
        let num_detections = shape[2];

        let mut detections = Vec::new();

        for i in 0..num_detections {
            let conf = data[[0, 1, i]];
            if conf < self.confidence_threshold {
                continue;
            }

            let x1 = data[[0, 2, i]] as f64;
            let y1 = data[[0, 3, i]] as f64;
            let x2 = data[[0, 4, i]] as f64;
            let y2 = data[[0, 5, i]] as f64;

            let img_x1 = ((x1 - offset.0 as f64) / scale as f64)
                .max(0.0)
                .min(orig_width as f64);
            let img_y1 = ((y1 - offset.1 as f64) / scale as f64)
                .max(0.0)
                .min(orig_height as f64);
            let img_x2 = ((x2 - offset.0 as f64) / scale as f64)
                .max(0.0)
                .min(orig_width as f64);
            let img_y2 = ((y2 - offset.1 as f64) / scale as f64)
                .max(0.0)
                .min(orig_height as f64);

            let bbox = BBox {
                x1: img_x1,
                y1: img_y1,
                x2: img_x2,
                y2: img_y2,
            };

            if bbox.width() < self.min_face_size as f64 || bbox.height() < self.min_face_size as f64
            {
                continue;
            }

            let landmarks = self.extract_landmarks(&data.view(), i);

            let face_width = bbox.width();
            let face_height = bbox.height();
            let face_angle = if face_width > 0.0 && face_height > 0.0 {
                (face_height / face_width).atan().to_degrees()
            } else {
                0.0
            };

            detections.push(DetectedFace {
                bbox,
                landmarks,
                confidence: conf,
                quality_score: None,
                face_angle,
                rotation_angle: 0.0,
            });
        }

        non_maximum_suppression(&mut detections, 0.5, self.confidence_threshold)
    }

    fn extract_landmarks(&self, data: &ndarray::ArrayViewD<'_, f32>, idx: usize) -> Vec<[f64; 2]> {
        let mut landmarks = Vec::new();
        for lm_idx in 0..5 {
            let lx = data[[0, 6 + lm_idx * 2, idx]] as f64;
            let ly = data[[0, 6 + lm_idx * 2 + 1, idx]] as f64;
            landmarks.push([lx, ly]);
        }
        landmarks
    }

    fn estimate_rotation_angle(image: &DynamicImage, face: &DetectedFace) -> f64 {
        if face.landmarks.len() < 2 {
            return 0.0;
        }

        let left_eye = face.landmarks[0];
        let right_eye = face.landmarks[1];
        let dx = right_eye[0] - left_eye[0];
        let dy = right_eye[1] - left_eye[1];

        dy.atan2(dx).to_degrees()
    }
}

#[async_trait]
impl FaceDetector for OnnxFaceDetector {
    async fn detect_faces(&self, image: &DynamicImage) -> Result<Vec<DetectedFace>> {
        let (orig_w, orig_h) = image.dimensions();
        let (tensor, scale, offset) = Self::preprocess(image, self.input_width, self.input_height)?;

        let input = tract_onnx::prelude::tensor4(
            &tensor.as_slice().unwrap(),
            &[1, 3, self.input_height as i64, self.input_width as i64],
        )?;

        let result = self.model.run(tvec!(input))?;
        let output = result[0].clone();

        let mut detections = self.decode_outputs(&output, scale, offset, orig_w, orig_h);

        for face in &mut detections {
            face.rotation_angle = Self::estimate_rotation_angle(image, face);
        }

        Ok(detections)
    }

    async fn detect_single_face(&self, image: &DynamicImage) -> Result<Option<DetectedFace>> {
        let faces = self.detect_faces(image).await?;
        let best = faces.into_iter().max_by(|a, b| {
            a.confidence
                .partial_cmp(&b.confidence)
                .unwrap_or(std::cmp::Ordering::Equal)
        });
        Ok(best)
    }

    fn min_face_size(&self) -> u32 {
        self.min_face_size
    }

    fn confidence_threshold(&self) -> f32 {
        self.confidence_threshold
    }
}

pub struct RetinaFaceDetector {
    inner: OnnxFaceDetector,
}

impl RetinaFaceDetector {
    pub fn new(
        model_path: &Path,
        input_width: u32,
        input_height: u32,
        min_face_size: u32,
        confidence_threshold: f32,
    ) -> Result<Self> {
        let inner = OnnxFaceDetector::new(
            model_path,
            input_width,
            input_height,
            min_face_size,
            confidence_threshold,
        )?;
        Ok(RetinaFaceDetector { inner })
    }
}

#[async_trait]
impl FaceDetector for RetinaFaceDetector {
    async fn detect_faces(&self, image: &DynamicImage) -> Result<Vec<DetectedFace>> {
        self.inner.detect_faces(image).await
    }

    async fn detect_single_face(&self, image: &DynamicImage) -> Result<Option<DetectedFace>> {
        self.inner.detect_single_face(image).await
    }

    fn min_face_size(&self) -> u32 {
        self.inner.min_face_size()
    }

    fn confidence_threshold(&self) -> f32 {
        self.inner.confidence_threshold()
    }
}

pub fn create_detector(
    model_path: &Path,
    input_width: u32,
    input_height: u32,
    min_face_size: u32,
    confidence_threshold: f32,
) -> Result<Arc<dyn FaceDetector>> {
    Ok(Arc::new(RetinaFaceDetector::new(
        model_path,
        input_width,
        input_height,
        min_face_size,
        confidence_threshold,
    )?))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_anchor_generation() {
        let anchors = OnnxFaceDetector::generate_anchors(640, 480);
        assert!(!anchors.is_empty());
        assert!(anchors[0].cx > 0.0);
    }

    #[test]
    fn test_iou_computation() {
        let a = BBox {
            x1: 0.0,
            y1: 0.0,
            x2: 10.0,
            y2: 10.0,
        };
        let b = BBox {
            x1: 5.0,
            y1: 5.0,
            x2: 15.0,
            y2: 15.0,
        };
        let iou = utils::compute_iou(&a, &b);
        assert!((iou - 25.0 / 175.0).abs() < 1e-6);
    }

    #[test]
    fn test_nms() {
        let mut detections = vec![
            DetectedFace {
                bbox: BBox {
                    x1: 0.0,
                    y1: 0.0,
                    x2: 10.0,
                    y2: 10.0,
                },
                landmarks: vec![],
                confidence: 0.9,
                quality_score: None,
                face_angle: 0.0,
                rotation_angle: 0.0,
            },
            DetectedFace {
                bbox: BBox {
                    x1: 1.0,
                    y1: 1.0,
                    x2: 9.0,
                    y2: 9.0,
                },
                landmarks: vec![],
                confidence: 0.8,
                quality_score: None,
                face_angle: 0.0,
                rotation_angle: 0.0,
            },
        ];
        let keep = non_maximum_suppression(&mut detections, 0.5, 0.5);
        assert_eq!(keep.len(), 1);
    }
}
