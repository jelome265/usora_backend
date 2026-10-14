use anyhow::Result;
use image::{DynamicImage, GenericImageView};
use ndarray_stats::QuantileExt;

use crate::detection::{
    DetectedFace, FaceGeometry, HistogramAnalysis, QualityDetails, QualityScore, TextureAnalysis,
};
use crate::utils;

pub struct QualityChecker {
    min_brightness: f64,
    max_brightness: f64,
    min_contrast: f64,
    max_contrast: f64,
    min_sharpness: f64,
    min_eye_openness: f64,
    min_face_ratio: f64,
    max_face_ratio: f64,
    min_face_relative_size: f64,
    max_face_relative_size: f64,
    blur_threshold: f64,
}

impl Default for QualityChecker {
    fn default() -> Self {
        QualityChecker {
            min_brightness: 30.0,
            max_brightness: 80.0,
            min_contrast: 20.0,
            max_contrast: 80.0,
            min_sharpness: 50.0,
            min_eye_openness: 0.3,
            min_face_ratio: 0.5,
            max_face_ratio: 2.0,
            min_face_relative_size: 0.05,
            max_face_relative_size: 0.8,
            blur_threshold: 30.0,
        }
    }
}

impl QualityChecker {
    pub fn new() -> Self {
        QualityChecker::default()
    }

    pub fn check_quality(&self, image: &DynamicImage, face: &DetectedFace) -> Result<QualityScore> {
        let cropped = utils::crop_face(image, face)?;
        let brightness = utils::compute_brightness(&cropped);
        let contrast = utils::compute_contrast(&cropped);
        let sharpness = utils::compute_sharpness(&cropped);
        let blur_score = self.compute_blur_score(&cropped);
        let blur_detected = blur_score < self.blur_threshold;

        let face_size_valid = self.check_face_size(&face);
        let face_position_valid = utils::is_face_centered(face, image.width(), image.height());

        let eye_openness = if face.landmarks.len() >= 6 {
            utils::estimate_eye_openness(&cropped, &face.landmarks)
        } else {
            1.0
        };

        let (obstruction_detected, obstruction_type) = self.detect_obstructions(&cropped);

        let resolution_score = self.compute_resolution_score(&cropped);
        let histogram = self.analyze_histogram(&cropped);
        let geometry = self.analyze_geometry(&cropped, face, image);
        let texture = self.analyze_texture(&cropped);

        let overall_score = self.compute_overall_score(
            brightness,
            contrast,
            sharpness,
            blur_score,
            eye_openness,
            resolution_score,
            &histogram,
            &geometry,
            &texture,
        );

        let passes_quality_gate = overall_score >= 50.0
            && !blur_detected
            && brightness >= self.min_brightness
            && brightness <= self.max_brightness
            && contrast >= self.min_contrast
            && contrast <= self.max_contrast
            && face_size_valid
            && face_position_valid
            && eye_openness >= self.min_eye_openness
            && !obstruction_detected;

        Ok(QualityScore {
            overall_score,
            brightness,
            contrast,
            sharpness,
            blur_detected,
            blur_score,
            face_size_valid,
            face_position_valid,
            eye_openness,
            obstruction_detected,
            obstruction_type,
            resolution_score,
            passes_quality_gate,
            details: QualityDetails {
                histogram_analysis: histogram,
                face_geometry: geometry,
                texture_analysis: texture,
            },
        })
    }

    fn compute_blur_score(&self, image: &DynamicImage) -> f64 {
        utils::compute_laplacian_variance(image)
    }

    fn check_face_size(&self, face: &DetectedFace) -> bool {
        let w = face.bbox.width();
        let h = face.bbox.height();
        let ratio = if h > 0.0 { w / h } else { 0.0 };
        ratio >= self.min_face_ratio && ratio <= self.max_face_ratio
    }

    fn compute_resolution_score(&self, image: &DynamicImage) -> f64 {
        let (w, h) = image.dimensions();
        let total_pixels = w as f64 * h as f64;
        if total_pixels >= 100_000.0 {
            100.0
        } else if total_pixels >= 50_000.0 {
            75.0
        } else if total_pixels >= 10_000.0 {
            50.0
        } else {
            25.0
        }
    }

    fn detect_obstructions(&self, image: &DynamicImage) -> (bool, Option<String>) {
        let gray = image.to_luma8();
        let (w, h) = gray.dimensions();
        let total = (w * h) as f64;

        let mut black_pixels = 0u64;
        let mut white_pixels = 0u64;

        for y in 0..h {
            for x in 0..w {
                let p = gray.get_pixel(x, y)[0];
                if p < 20 {
                    black_pixels += 1;
                } else if p > 235 {
                    white_pixels += 1;
                }
            }
        }

        let black_ratio = black_pixels as f64 / total;
        let white_ratio = white_pixels as f64 / total;

        if black_ratio > 0.3 {
            return (true, Some("Mask".to_string()));
        }
        if white_ratio > 0.4 {
            return (true, Some("Overexposed".to_string()));
        }

        let mid_band = self.analyze_mid_band_obstruction(&gray);
        if mid_band > 0.3 {
            return (true, Some("Hand".to_string()));
        }

        (false, None)
    }

    fn analyze_mid_band_obstruction(&self, gray: &image::GrayImage) -> f64 {
        let (w, h) = gray.dimensions();
        let band_top = h / 3;
        let band_bottom = 2 * h / 3;

        let mut band_pixels = 0u64;
        let mut obstruction_pixels = 0u64;

        for y in band_top..band_bottom {
            for x in 0..w {
                band_pixels += 1;
                let p = gray.get_pixel(x, y)[0];
                if p < 30 || p > 225 {
                    obstruction_pixels += 1;
                }
            }
        }

        if band_pixels == 0 {
            return 0.0;
        }
        obstruction_pixels as f64 / band_pixels as f64
    }

    fn analyze_histogram(&self, image: &DynamicImage) -> HistogramAnalysis {
        let gray = image.to_luma8();
        let pixels: Vec<f64> = gray.as_raw().iter().map(|&p| p as f64).collect();
        let n = pixels.len() as f64;

        if n == 0.0 {
            return HistogramAnalysis {
                mean: 0.0,
                std_dev: 0.0,
                skewness: 0.0,
                entropy: 0.0,
                low_frequency_ratio: 0.0,
                high_frequency_ratio: 0.0,
            };
        }

        let mean: f64 = pixels.iter().sum::<f64>() / n;
        let variance: f64 = pixels.iter().map(|&p| (p - mean).powi(2)).sum::<f64>() / n;
        let std_dev = variance.sqrt();

        let skewness: f64 =
            pixels.iter().map(|&p| (p - mean).powi(3)).sum::<f64>() / (n * std_dev.powi(3));

        let mut hist = vec![0u64; 256];
        for &p in &pixels {
            hist[p as usize] += 1;
        }
        let entropy: f64 = hist
            .iter()
            .filter(|&&c| c > 0)
            .map(|&c| {
                let p = c as f64 / n;
                -p * p.log2()
            })
            .sum();

        let low_freq: u64 = hist[..64].iter().sum();
        let high_freq: u64 = hist[192..].iter().sum();
        let low_frequency_ratio = low_freq as f64 / n;
        let high_frequency_ratio = high_freq as f64 / n;

        HistogramAnalysis {
            mean,
            std_dev,
            skewness,
            entropy,
            low_frequency_ratio,
            high_frequency_ratio,
        }
    }

    fn analyze_geometry(
        &self,
        cropped: &DynamicImage,
        face: &DetectedFace,
        full_image: &DynamicImage,
    ) -> FaceGeometry {
        let face_w = face.bbox.width();
        let face_h = face.bbox.height();
        let face_ratio = if face_h > 0.0 { face_w / face_h } else { 1.0 };

        let asymmetry_score = if face.landmarks.len() >= 5 {
            let left_eye = face.landmarks[0];
            let right_eye = face.landmarks[1];
            let nose = face.landmarks[2];
            let left_mouth = face.landmarks[3];
            let right_mouth = face.landmarks[4];

            let eye_mid_x = (left_eye[0] + right_eye[0]) / 2.0;
            let mouth_mid_x = (left_mouth[0] + right_mouth[0]) / 2.0;
            let nose_offset = (nose[0] - eye_mid_x).abs();
            let mouth_offset = (mouth_mid_x - eye_mid_x).abs();
            (nose_offset + mouth_offset) / face_w
        } else {
            0.0
        };

        let img_area = full_image.width() as f64 * full_image.height() as f64;
        let face_area = face_w * face_h;
        let relative_size = if img_area > 0.0 {
            face_area / img_area
        } else {
            0.0
        };

        FaceGeometry {
            face_width: face_w,
            face_height: face_h,
            face_ratio,
            asymmetry_score,
            relative_size_to_image: relative_size,
        }
    }

    fn analyze_texture(&self, image: &DynamicImage) -> TextureAnalysis {
        let gray = image.to_luma8();
        let (w, h) = gray.dimensions();

        let lbp_variance = self.compute_lbp_variance(&gray);
        let frequency_energy = self.compute_frequency_energy(&gray);

        let mut local_contrast_sum = 0.0f64;
        let mut local_contrast_count = 0u64;
        let block_size = 8u32;

        for by in (0..h).step_by(block_size as usize) {
            for bx in (0..w).step_by(block_size as usize) {
                let mut block_sum = 0u64;
                let mut block_count = 0u64;
                for dy in 0..block_size.min(h - by) {
                    for dx in 0..block_size.min(w - bx) {
                        block_sum += gray.get_pixel(bx + dx, by + dy)[0] as u64;
                        block_count += 1;
                    }
                }
                if block_count > 0 {
                    let block_mean = block_sum as f64 / block_count as f64;
                    for dy in 0..block_size.min(h - by) {
                        for dx in 0..block_size.min(w - bx) {
                            let diff = gray.get_pixel(bx + dx, by + dy)[0] as f64 - block_mean;
                            local_contrast_sum += diff.abs();
                            local_contrast_count += 1;
                        }
                    }
                }
            }
        }

        let local_contrast = if local_contrast_count > 0 {
            local_contrast_sum / local_contrast_count as f64
        } else {
            0.0
        };

        let gradient_magnitude = self.compute_gradient_magnitude(&gray);

        TextureAnalysis {
            lbp_variance,
            frequency_energy,
            local_contrast,
            gradient_magnitude,
        }
    }

    fn compute_lbp_variance(&self, gray: &image::GrayImage) -> f64 {
        let (w, h) = gray.dimensions();
        let mut lbp_values = Vec::new();

        for y in 1..(h - 1) {
            for x in 1..(w - 1) {
                let center = gray.get_pixel(x, y)[0];
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
                    if gray.get_pixel(nx, ny)[0] >= center {
                        lbp |= 1 << i;
                    }
                }
                lbp_values.push(lbp as f64);
            }
        }

        if lbp_values.is_empty() {
            return 0.0;
        }

        let mean: f64 = lbp_values.iter().sum::<f64>() / lbp_values.len() as f64;
        lbp_values.iter().map(|&v| (v - mean).powi(2)).sum::<f64>() / lbp_values.len() as f64
    }

    fn compute_frequency_energy(&self, gray: &image::GrayImage) -> f64 {
        let (w, h) = gray.dimensions();
        let mut energy = 0.0f64;
        let mut count = 0u64;

        for y in 1..(h - 1) {
            for x in 1..(w - 1) {
                let center = gray.get_pixel(x, y)[0] as f64;
                let top = gray.get_pixel(x, y - 1)[0] as f64;
                let bottom = gray.get_pixel(x, y + 1)[0] as f64;
                let left = gray.get_pixel(x - 1, y)[0] as f64;
                let right = gray.get_pixel(x + 1, y)[0] as f64;

                let horizontal_diff = right - left;
                let vertical_diff = bottom - top;
                energy += horizontal_diff.powi(2) + vertical_diff.powi(2);
                count += 1;
            }
        }

        if count == 0 {
            return 0.0;
        }
        (energy / count as f64).sqrt()
    }

    fn compute_gradient_magnitude(&self, gray: &image::GrayImage) -> f64 {
        let (w, h) = gray.dimensions();
        let sobel_x: [[i32; 3]; 3] = [[-1, 0, 1], [-2, 0, 2], [-1, 0, 1]];
        let sobel_y: [[i32; 3]; 3] = [[-1, -2, -1], [0, 0, 0], [1, 2, 1]];

        let mut total_grad = 0.0f64;
        let mut count = 0u64;

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
                let mag = ((gx * gx + gy * gy) as f64).sqrt();
                total_grad += mag;
                count += 1;
            }
        }

        if count == 0 {
            return 0.0;
        }
        total_grad / count as f64
    }

    fn compute_overall_score(
        &self,
        brightness: f64,
        contrast: f64,
        sharpness: f64,
        blur_score: f64,
        eye_openness: f64,
        resolution_score: f64,
        histogram: &HistogramAnalysis,
        geometry: &FaceGeometry,
        texture: &TextureAnalysis,
    ) -> f64 {
        let brightness_score =
            self.normalize_range(brightness, self.min_brightness, self.max_brightness);
        let contrast_score = self.normalize_range(contrast, self.min_contrast, self.max_contrast);

        let sharpness_score = (sharpness / 200.0).min(1.0) * 100.0;
        let blur_score_norm = (blur_score / 200.0).min(1.0) * 100.0;

        let asymmetry_penalty = (geometry.asymmetry_score * 100.0).min(30.0);
        let entropy_score = (histogram.entropy / 8.0).min(1.0) * 100.0;

        let texture_score = (texture.gradient_magnitude / 50.0).min(1.0) * 100.0;

        let score = brightness_score * 0.10
            + contrast_score * 0.10
            + sharpness_score * 0.10
            + blur_score_norm * 0.15
            + eye_openness * 100.0 * 0.10
            + resolution_score * 0.10
            + entropy_score * 0.10
            + texture_score * 0.10
            + (100.0 - asymmetry_penalty) * 0.10
            + geometry.relative_size_to_image.min(0.5) / 0.5 * 100.0 * 0.05;

        score.max(0.0).min(100.0)
    }

    fn normalize_range(&self, value: f64, min: f64, max: f64) -> f64 {
        if value < min {
            return value / min * 50.0;
        }
        if value > max {
            return (1.0 - (value - max) / max).max(0.0) * 100.0;
        }
        100.0
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::RgbImage;

    #[test]
    fn test_quality_check_valid_face() {
        let img = DynamicImage::new_rgb8(200, 200);
        let face = DetectedFace {
            bbox: utils::BBox {
                x1: 40.0,
                y1: 40.0,
                x2: 160.0,
                y2: 160.0,
            },
            landmarks: vec![
                [70.0, 80.0],
                [130.0, 80.0],
                [100.0, 110.0],
                [80.0, 130.0],
                [120.0, 130.0],
            ],
            confidence: 0.95,
            quality_score: None,
            face_angle: 0.0,
            rotation_angle: 0.0,
        };

        let checker = QualityChecker::default();
        let score = checker.check_quality(&img, &face).unwrap();
        assert!(score.overall_score >= 0.0);
    }

    #[test]
    fn test_blur_detection() {
        let img = DynamicImage::new_rgb8(100, 100);
        let blur = QualityChecker::default().compute_blur_score(&img);
        assert!(blur >= 0.0);
    }
}
