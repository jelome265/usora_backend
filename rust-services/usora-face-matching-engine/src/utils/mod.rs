use anyhow::{Context, Result};
use base64::{engine::general_purpose::STANDARD as BASE64, Engine};
use chrono::Utc;
use image::{
    imageops::FilterType, io::Reader as ImageReader, DynamicImage, GenericImageView, ImageBuffer,
    Rgb,
};
use ndarray::{Array, Array3, Axis, Dim};
use serde::{Deserialize, Serialize};
use std::io::Cursor;
use uuid::Uuid;

use crate::detection::DetectedFace;

pub fn generate_uuid_v7() -> Uuid {
    let timestamp = Utc::now().timestamp_millis() as u64;
    let mut bytes = [0u8; 16];
    bytes[0..8].copy_from_slice(&timestamp.to_be_bytes());
    bytes[6] = (bytes[6] & 0x0f) | 0x70;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    Uuid::from_bytes(bytes)
}

pub fn encode_image_base64(data: &[u8]) -> String {
    BASE64.encode(data)
}

pub fn decode_image_base64(encoded: &str) -> Result<Vec<u8>> {
    BASE64
        .decode(encoded)
        .context("Failed to decode base64 image")
}

pub fn load_image_from_bytes(data: &[u8]) -> Result<DynamicImage> {
    let reader = ImageReader::new(Cursor::new(data))
        .with_guessed_format()
        .context("Failed to determine image format")?;
    reader.decode().context("Failed to decode image")
}

pub fn crop_face(image: &DynamicImage, face: &DetectedFace) -> Result<DynamicImage> {
    let bbox = &face.bbox;
    let x = (bbox.x1).max(0.0) as u32;
    let y = (bbox.y1).max(0.0) as u32;
    let w = (bbox.width()).min((image.width() - x) as f64) as u32;
    let h = (bbox.height()).min((image.height() - y) as f64) as u32;

    if w == 0 || h == 0 {
        anyhow::bail!("Invalid face bounding box dimensions: {}x{}", w, h);
    }

    Ok(image.crop_imm(x, y, w, h))
}

pub fn resize_face(image: &DynamicImage, width: u32, height: u32) -> DynamicImage {
    image.resize_exact(width, height, FilterType::Lanczos3)
}

pub fn align_face(image: &DynamicImage, landmarks: &[[f64; 2]]) -> Result<DynamicImage> {
    if landmarks.len() < 5 {
        anyhow::bail!("Need at least 5 landmarks for alignment");
    }

    let left_eye = landmarks[0];
    let right_eye = landmarks[1];
    let nose = landmarks[2];
    let left_mouth = landmarks[3];
    let right_mouth = landmarks[4];

    let eye_center = [
        (left_eye[0] + right_eye[0]) / 2.0,
        (left_eye[1] + right_eye[1]) / 2.0,
    ];

    let mouth_center = [
        (left_mouth[0] + right_mouth[0]) / 2.0,
        (left_mouth[1] + right_mouth[1]) / 2.0,
    ];

    let dx = right_eye[0] - left_eye[0];
    let dy = right_eye[1] - left_eye[1];
    let angle = dy.atan2(dx).to_degrees();

    let radians = (angle as f32).to_radians();
    let rotated_buffer = imageproc::geometric_transformations::rotate_about_center(
        &image.to_rgba8(),
        radians,
        imageproc::geometric_transformations::Interpolation::Bilinear,
        image::Rgba([0, 0, 0, 0]),
    );
    let rotated = DynamicImage::ImageRgba8(rotated_buffer);

    let scale_x: f64 = 112.0 / rotated.width() as f64;
    let scale_y: f64 = 112.0 / rotated.height() as f64;
    let scale = scale_x.max(scale_y) as f32;

    let scaled_width = (rotated.width() as f32 * scale) as u32;
    let scaled_height = (rotated.height() as f32 * scale) as u32;
    let scaled = rotated.resize_exact(scaled_width, scaled_height, FilterType::Lanczos3);

    let crop_x = ((scaled.width() as f64 - 112.0) / 2.0).max(0.0) as u32;
    let crop_y = ((scaled.height() as f64 - 112.0) / 2.0).max(0.0) as u32;

    Ok(scaled.crop_imm(
        crop_x,
        crop_y,
        112.min(scaled.width() - crop_x),
        112.min(scaled.height() - crop_y),
    ))
}

pub fn preprocess_for_embedding(image: &DynamicImage) -> Result<Array3<f32>> {
    let resized = image.resize_exact(112, 112, FilterType::Lanczos3);
    let rgb = resized.to_rgb8();

    let mut tensor = Array3::<f32>::zeros((3, 112, 112));
    let mean: [f32; 3] = [0.5, 0.5, 0.5];
    let std: [f32; 3] = [0.5, 0.5, 0.5];

    for y in 0..112 {
        for x in 0..112 {
            let pixel = rgb.get_pixel(x, y);
            for c in 0..3 {
                tensor[[c, y as usize, x as usize]] = (pixel[c] as f32 / 255.0 - mean[c]) / std[c];
            }
        }
    }

    Ok(tensor)
}

pub fn normalize_l2(vector: &mut [f32]) {
    let norm: f32 = vector.iter().map(|x| x * x).sum::<f32>().sqrt();
    if norm > 1e-10 {
        for v in vector.iter_mut() {
            *v /= norm;
        }
    }
}

pub fn cosine_similarity(a: &[f32], b: &[f32]) -> f64 {
    if a.len() != b.len() {
        return 0.0;
    }
    let dot: f32 = a.iter().zip(b.iter()).map(|(x, y)| x * y).sum();
    let norm_a: f32 = a.iter().map(|x| x * x).sum::<f32>().sqrt();
    let norm_b: f32 = b.iter().map(|x| x * x).sum::<f32>().sqrt();
    if norm_a < 1e-10 || norm_b < 1e-10 {
        return 0.0;
    }
    (dot / (norm_a * norm_b)) as f64
}

pub fn euclidean_distance(a: &[f32], b: &[f32]) -> f64 {
    a.iter()
        .zip(b.iter())
        .map(|(x, y)| ((x - y) as f64).powi(2))
        .sum::<f64>()
        .sqrt()
}

pub fn compute_laplacian_variance(image: &DynamicImage) -> f64 {
    let gray = image.to_luma8();
    let (width, height) = gray.dimensions();
    let mut sum = 0.0f64;
    let mut count = 0u64;

    for y in 1..(height - 1) {
        for x in 1..(width - 1) {
            let pixel_center = gray.get_pixel(x, y)[0] as f64;
            let top = gray.get_pixel(x, y - 1)[0] as f64;
            let bottom = gray.get_pixel(x, y + 1)[0] as f64;
            let left = gray.get_pixel(x - 1, y)[0] as f64;
            let right = gray.get_pixel(x + 1, y)[0] as f64;
            let laplacian = (4.0 * pixel_center - top - bottom - left - right).abs();
            sum += laplacian;
            count += 1;
        }
    }

    if count == 0 {
        return 0.0;
    }
    sum / count as f64
}

pub fn compute_brightness(image: &DynamicImage) -> f64 {
    let gray = image.to_luma8();
    let pixels = gray.as_raw();
    let sum: u32 = pixels.iter().map(|&p| p as u32).sum();
    sum as f64 / pixels.len() as f64 / 255.0 * 100.0
}

pub fn compute_contrast(image: &DynamicImage) -> f64 {
    let gray = image.to_luma8();
    let mean: f64 =
        gray.as_raw().iter().map(|&p| p as f64).sum::<f64>() / gray.as_raw().len() as f64;
    let variance: f64 = gray
        .as_raw()
        .iter()
        .map(|&p| {
            let diff = p as f64 - mean;
            diff * diff
        })
        .sum::<f64>()
        / gray.as_raw().len() as f64;
    variance.sqrt() / 255.0 * 100.0
}

pub fn compute_sharpness(image: &DynamicImage) -> f64 {
    compute_laplacian_variance(image)
}

pub fn estimate_eye_openness(image: &DynamicImage, landmarks: &[[f64; 2]]) -> f64 {
    if landmarks.len() < 6 {
        return 1.0;
    }

    let left_eye_top = landmarks[4];
    let left_eye_bottom = landmarks[5];
    let _left_eye_height = (left_eye_top[1] - left_eye_bottom[1]).abs();

    let gray = image.to_luma8();
    let (w, h) = gray.dimensions();
    let eye_y = ((left_eye_top[1] + left_eye_bottom[1]) / 2.0) as u32;
    let eye_x = landmarks[0][0] as u32;

    if eye_x >= w || eye_y >= h {
        return 1.0;
    }

    let eye_pixel = gray.get_pixel(eye_x, eye_y)[0] as f64;
    let avg: f64 = gray.as_raw().iter().map(|&p| p as f64).sum::<f64>() / (w * h) as f64;
    let normalized = (eye_pixel - avg).abs() / 255.0;

    (1.0 - normalized).max(0.0).min(1.0)
}

pub fn is_face_centered(face: &DetectedFace, img_width: u32, img_height: u32) -> bool {
    let center_x = (face.bbox.x1 + face.bbox.x2) / 2.0;
    let center_y = (face.bbox.y1 + face.bbox.y2) / 2.0;
    let img_cx = img_width as f64 / 2.0;
    let img_cy = img_height as f64 / 2.0;
    let norm_dx = (center_x - img_cx).abs() / img_width as f64;
    let norm_dy = (center_y - img_cy).abs() / img_height as f64;
    norm_dx < 0.3 && norm_dy < 0.3
}

pub fn compute_iou(a: &BBox, b: &BBox) -> f64 {
    let x1 = a.x1.max(b.x1);
    let y1 = a.y1.max(b.y1);
    let x2 = a.x2.min(b.x2);
    let y2 = a.y2.min(b.y2);

    if x2 <= x1 || y2 <= y1 {
        return 0.0;
    }

    let inter_area = (x2 - x1) * (y2 - y1);
    let a_area = a.area();
    let b_area = b.area();
    let union_area = a_area + b_area - inter_area;

    if union_area <= 0.0 {
        return 0.0;
    }

    inter_area / union_area
}

pub fn non_maximum_suppression(
    detections: &mut Vec<DetectedFace>,
    iou_threshold: f64,
    score_threshold: f32,
) -> Vec<DetectedFace> {
    detections.retain(|d| d.confidence >= score_threshold);
    detections.sort_by(|a, b| {
        b.confidence
            .partial_cmp(&a.confidence)
            .unwrap_or(std::cmp::Ordering::Equal)
    });

    let mut keep = Vec::new();
    let mut suppressed = vec![false; detections.len()];

    for i in 0..detections.len() {
        if suppressed[i] {
            continue;
        }
        keep.push(detections[i].clone());
        for j in (i + 1)..detections.len() {
            if !suppressed[j]
                && compute_iou(&detections[i].bbox, &detections[j].bbox) > iou_threshold
            {
                suppressed[j] = true;
            }
        }
    }

    keep
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BBox {
    pub x1: f64,
    pub y1: f64,
    pub x2: f64,
    pub y2: f64,
}

impl BBox {
    pub fn width(&self) -> f64 {
        (self.x2 - self.x1).abs()
    }

    pub fn height(&self) -> f64 {
        (self.y2 - self.y1).abs()
    }

    pub fn area(&self) -> f64 {
        self.width() * self.height()
    }

    pub fn center(&self) -> (f64, f64) {
        ((self.x1 + self.x2) / 2.0, (self.y1 + self.y2) / 2.0)
    }
}

pub fn landmark_distances(landmarks: &[[f64; 2]]) -> Vec<f64> {
    let mut dists = Vec::new();
    for i in 0..landmarks.len() {
        for j in (i + 1)..landmarks.len() {
            let dx = landmarks[i][0] - landmarks[j][0];
            let dy = landmarks[i][1] - landmarks[j][1];
            dists.push((dx * dx + dy * dy).sqrt());
        }
    }
    dists
}
