use crate::validation::{ValidationResult, Validator};
use async_trait::async_trait;
use image::GenericImageView;

pub struct AuthenticityCheckEngine;

impl AuthenticityCheckEngine {
    fn extract_luminance_histogram(gray: &image::GrayImage) -> Vec<u32> {
        let mut hist = vec![0u32; 256];
        for pixel in gray.pixels() {
            hist[pixel[0] as usize] += 1;
        }
        hist
    }

    fn detect_hologram_pattern(gray: &image::GrayImage) -> (bool, f32) {
        let (w, h) = gray.dimensions();
        if w < 50 || h < 50 {
            return (false, 0.0);
        }

        let mut color_variations = Vec::new();
        for y in (0..h).step_by(2) {
            for x in (0..w).step_by(2) {
                let px = gray.get_pixel(x, y)[0];
                color_variations.push(px);
            }
        }

        if color_variations.len() < 100 {
            return (false, 0.0);
        }

        let mean: f32 = color_variations.iter().map(|&v| v as f32).sum::<f32>() / color_variations.len() as f32;
        let variance: f32 = color_variations.iter()
            .map(|&v| (v as f32 - mean).powi(2))
            .sum::<f32>() / color_variations.len() as f32;
        let std_dev = variance.sqrt();

        let region_count = color_variations.len();
        let high_freq = color_variations.iter()
            .filter(|&&v| (v as f32 - mean).abs() > std_dev)
            .count();

        let hologram_ratio = high_freq as f32 / region_count as f32;
        let detected = hologram_ratio > 0.15 && std_dev > 30.0;
        let confidence = if detected {
            (hologram_ratio * 100.0).min(0.98)
        } else {
            (1.0 - hologram_ratio).min(0.5)
        };

        (detected, confidence)
    }

    fn detect_uv_fluorescence(rgb: &image::RgbImage) -> (bool, f32) {
        let (w, h) = rgb.dimensions();
        let mut uv_pixels = 0u32;
        let mut total = 0u32;

        for y in (0..h).step_by(3) {
            for x in (0..w).step_by(3) {
                let pixel = rgb.get_pixel(x, y);
                let r = pixel[0] as i16;
                let g = pixel[1] as i16;
                let b = pixel[2] as i16;

                let uv_score = (r as i32 - g as i32 - b as i32).abs() as u32;
                if r > 200 && g < 100 && b < 100 {
                    uv_pixels += 1;
                } else if uv_score > 150 {
                    uv_pixels += 1;
                }
                total += 1;
            }
        }

        if total == 0 {
            return (false, 0.0);
        }

        let uv_ratio = uv_pixels as f32 / total as f32;
        let detected = uv_ratio > 0.05;
        let confidence = if detected {
            (uv_ratio * 3.0).min(0.95)
        } else {
            (1.0 - uv_ratio * 5.0).max(0.3)
        };

        (detected, confidence)
    }

    fn detect_ir_absorption(gray: &image::GrayImage) -> (bool, f32) {
        let hist = Self::extract_luminance_histogram(gray);
        let total: u32 = hist.iter().sum();
        if total == 0 {
            return (false, 0.5);
        }

        let dark_ratio = hist[..32].iter().sum::<u32>() as f32 / total as f32;
        let mid_ratio = hist[96..160].iter().sum::<u32>() as f32 / total as f32;

        let ir_pattern_score = (dark_ratio * 2.0 + mid_ratio * 0.5) / 2.5;
        let detected = ir_pattern_score > 0.3;
        let confidence = if detected { ir_pattern_score.min(0.9) } else { (1.0 - ir_pattern_score).max(0.2) };

        (detected, confidence)
    }

    fn detect_microprint(gray: &image::GrayImage) -> (bool, f32) {
        let (w, h) = gray.dimensions();
        if w < 100 || h < 100 {
            return (false, 0.3);
        }

        let mut fine_detail = 0u32;
        let mut total = 0u32;

        for y in 1..h - 1 {
            for x in 1..w - 1 {
                let c = gray.get_pixel(x, y)[0] as i16;
                let n = gray.get_pixel(x, y + 1)[0] as i16;
                let s = gray.get_pixel(x, y - 1)[0] as i16;
                let e = gray.get_pixel(x + 1, y)[0] as i16;
                let w_ = gray.get_pixel(x - 1, y)[0] as i16;

                let h_grad = (e - w_).abs();
                let v_grad = (s - n).abs();

                if h_grad > 30 || v_grad > 30 {
                    fine_detail += 1;
                }
                if h_grad > 5 || v_grad > 5 {
                    total += 1;
                }
            }
        }

        if total == 0 {
            return (false, 0.5);
        }

        let detail_ratio = fine_detail as f32 / total as f32;
        let detected = detail_ratio > 0.15;
        let confidence = if detected {
            (detail_ratio * 2.0).min(0.95)
        } else {
            (1.0 - detail_ratio * 0.5).max(0.3)
        };

        (detected, confidence)
    }

    fn detect_watermark(gray: &image::GrayImage) -> (bool, f32) {
        let hist = Self::extract_luminance_histogram(gray);
        let total: u32 = hist.iter().sum();
        if total == 0 {
            return (false, 0.3);
        }

        let mut smoothness = 0u32;
        for i in 1..255 {
            let diff = (hist[i] as i64 - hist[i - 1] as i64).unsigned_abs();
            if diff < total as u64 / 256 {
                smoothness += 1;
            }
        }

        let smooth_ratio = smoothness as f32 / 255.0;
        let detected = smooth_ratio > 0.6;
        let confidence = if detected {
            (smooth_ratio - 0.5).min(0.85)
        } else {
            (1.0 - smooth_ratio).max(0.3)
        };

        (detected, confidence)
    }

    fn detect_font_anomalies(gray: &image::GrayImage) -> (bool, f32) {
        let edge = imageproc::edges::canny(gray, 30.0, 100.0);
        let edge_pixels = edge.pixels().filter(|p| p[0] > 0).count() as f32;
        let total_pixels = gray.width() as f32 * gray.height() as f32;

        if total_pixels == 0.0 {
            return (false, 0.5);
        }

        let edge_density = edge_pixels / total_pixels;
        let normal_min = 0.02;
        let normal_max = 0.15;

        let within_normal = edge_density >= normal_min && edge_density <= normal_max;
        let deviation = if edge_density < normal_min {
            (normal_min - edge_density) / normal_min
        } else if edge_density > normal_max {
            (edge_density - normal_max) / normal_max
        } else {
            0.0
        };

        let confidence = (1.0 - deviation).max(0.3);
        (within_normal, confidence)
    }
}

#[async_trait]
impl Validator for AuthenticityCheckEngine {
    fn name(&self) -> &'static str {
        "authenticity"
    }

    async fn validate(&self, image_data: &[u8]) -> anyhow::Result<Vec<ValidationResult>> {
        let img = image::load_from_memory(image_data)?;
        let gray = img.to_luma8();
        let rgb = img.to_rgb8();

        let mut results = Vec::new();

        // See note below on uv_fluorescence/ir_absorption: this is a local
        // pixel-variance heuristic on a standard visible-light capture, not
        // a genuine optical hologram-detection check (which would require
        // multi-angle or specialized capture). Capped and labeled the same way.
        const HOLOGRAM_HEURISTIC_MAX_CONFIDENCE: f32 = 0.4;
        let (hologram_detected, hologram_conf_raw) = Self::detect_hologram_pattern(&gray);
        let hologram_conf = hologram_conf_raw.min(HOLOGRAM_HEURISTIC_MAX_CONFIDENCE);
        results.push(ValidationResult {
            field: "hologram_heuristic".to_string(),
            passed: hologram_detected,
            confidence: hologram_conf,
            details: vec![
                if hologram_detected {
                    "Pixel-variance pattern suggestive of a hologram region (NOT a genuine optical hologram check)".to_string()
                } else {
                    "No hologram-like pixel-variance pattern found".to_string()
                },
                format!("Confidence: {:.2} (capped — heuristic, not a genuine hologram forensic check)", hologram_conf),
            ],
        });

        // NOTE: genuine UV fluorescence and IR absorption anti-forgery
        // features require a capture actually illuminated with UV/IR light;
        // they cannot be reliably determined from an ordinary visible-light
        // (RGB) photo. This pipeline does not currently receive or track
        // capture-illumination metadata, so these two checks are RGB-pixel
        // heuristics only — correlated with lighting/sensor conditions, not
        // a real forensic UV/IR signal. They are named and confidence-capped
        // accordingly so they cannot be mistaken for a true UV/IR check by
        // API consumers or reviewers. See
        // docs/architecture-security-review-2026-07-31.md §3.6 for the full
        // writeup and the product decision needed to properly gate these on
        // confirmed UV/IR capture metadata.
        const HEURISTIC_MAX_CONFIDENCE: f32 = 0.4;

        let (uv_detected, uv_conf_raw) = Self::detect_uv_fluorescence(&rgb);
        let uv_conf = uv_conf_raw.min(HEURISTIC_MAX_CONFIDENCE);
        results.push(ValidationResult {
            field: "uv_fluorescence_heuristic".to_string(),
            passed: uv_detected,
            confidence: uv_conf,
            details: vec![
                if uv_detected {
                    "Visible-light color heuristic suggestive of UV fluorescence (NOT a true UV-illuminated capture check)".to_string()
                } else {
                    "No UV-fluorescence-like color pattern found (visible-light heuristic only)".to_string()
                },
                format!("Confidence: {:.2} (capped — heuristic, not a genuine UV forensic check)", uv_conf),
            ],
        });

        let (ir_valid, ir_conf_raw) = Self::detect_ir_absorption(&gray);
        let ir_conf = ir_conf_raw.min(HEURISTIC_MAX_CONFIDENCE);
        results.push(ValidationResult {
            field: "ir_absorption_heuristic".to_string(),
            passed: ir_valid,
            confidence: ir_conf,
            details: vec![
                if ir_valid {
                    "Luminance-histogram heuristic within expected range (NOT a true IR-illuminated capture check)".to_string()
                } else {
                    "Luminance-histogram heuristic abnormal (visible-light heuristic only)".to_string()
                },
                format!("Confidence: {:.2} (capped — heuristic, not a genuine IR forensic check)", ir_conf),
            ],
        });

        let (microprint_detected, microprint_conf) = Self::detect_microprint(&gray);
        results.push(ValidationResult {
            field: "microprint".to_string(),
            passed: microprint_detected,
            confidence: microprint_conf,
            details: vec![
                if microprint_detected {
                    "Microprint detected".to_string()
                } else {
                    "Microprint not detected".to_string()
                },
                format!("Confidence: {:.2}", microprint_conf),
            ],
        });

        let (watermark_detected, watermark_conf) = Self::detect_watermark(&gray);
        results.push(ValidationResult {
            field: "watermark".to_string(),
            passed: watermark_detected,
            confidence: watermark_conf,
            details: vec![
                if watermark_detected {
                    "Watermark pattern detected".to_string()
                } else {
                    "No watermark found".to_string()
                },
                format!("Confidence: {:.2}", watermark_conf),
            ],
        });

        let (font_ok, font_conf) = Self::detect_font_anomalies(&gray);
        results.push(ValidationResult {
            field: "font_analysis".to_string(),
            passed: font_ok,
            confidence: font_conf,
            details: vec![
                if font_ok {
                    "Font characteristics normal".to_string()
                } else {
                    "Font characteristics abnormal".to_string()
                },
                format!("Confidence: {:.2}", font_conf),
            ],
        });

        Ok(results)
    }
}
