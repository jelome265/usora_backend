use crate::validation::{ValidationResult, Validator};
use async_trait::async_trait;
use image::GenericImageView;
use imageproc::edges::canny;

pub struct TamperDetectionEngine;

impl TamperDetectionEngine {
    fn detect_photo_substitution(gray: &image::GrayImage) -> (bool, f32) {
        let (w, h) = gray.dimensions();
        if w < 100 || h < 100 {
            return (false, 0.5);
        }

        let face_region_y = h / 3;
        let face_region_h = h / 3;
        let face_region = image::imageops::crop_imm(
            gray,
            0,
            face_region_y,
            w,
            face_region_h.min(h - face_region_y),
        );

        let mut boundary_edges = 0u32;
        let mut total_boundary = 0u32;

        let border_y = face_region_h.min(h - face_region_y).saturating_sub(2);
        let check_height = 3u32;

        for y_offset in 0..check_height {
            for x in 0..w {
                let y = face_region_y + border_y + y_offset;
                if y >= h {
                    break;
                }
                let px = gray.get_pixel(x, y)[0];
                if px < 50 || px > 200 {
                    boundary_edges += 1;
                }
                total_boundary += 1;
            }
        }

        if total_boundary == 0 {
            return (false, 0.5);
        }

        let edge_ratio = boundary_edges as f32 / total_boundary as f32;
        let substituted = edge_ratio > 0.4;
        let confidence = if substituted {
            (edge_ratio * 1.5).min(0.95)
        } else {
            (1.0 - edge_ratio * 2.0).max(0.3)
        };

        (substituted, confidence)
    }

    fn detect_date_tampering(gray: &image::GrayImage) -> (bool, f32) {
        let (w, h) = gray.dimensions();
        if w < 200 || h < 200 {
            return (false, 0.5);
        }

        let date_region_y = h * 7 / 8;
        let date_region_h = h / 10;
        let date_region = image::imageops::crop_imm(
            gray,
            w / 4,
            date_region_y,
            w / 2,
            date_region_h.min(h - date_region_y),
        );

        let edge = canny(&date_region, 30.0, 80.0);
        let edge_pixels = edge.pixels().filter(|p| p[0] > 0).count() as f32;
        let total_pixels = edge.width() as f32 * edge.height() as f32;

        if total_pixels == 0.0 {
            return (false, 0.5);
        }

        let edge_density = edge_pixels / total_pixels;

        let edge_threshold = imageproc::edges::canny(gray, 30.0, 80.0);
        let global_edge_pixels = edge_threshold.pixels().filter(|p| p[0] > 0).count() as f32;
        let global_total = w as f32 * h as f32;
        let global_density = if global_total > 0.0 {
            global_edge_pixels / global_total
        } else {
            0.05
        };

        let ratio_vs_global = if global_density > 0.0 {
            edge_density / global_density
        } else {
            1.0
        };

        let tampered = (ratio_vs_global > 2.5 || ratio_vs_global < 0.3) && edge_density > 0.01;
        let confidence = if tampered {
            ((ratio_vs_global - 1.0).abs() * 0.4).min(0.95)
        } else {
            (1.0 - (ratio_vs_global - 1.0).abs() * 0.2).max(0.3)
        };

        (tampered, confidence)
    }

    fn detect_ela_artifacts(rgb: &image::RgbImage) -> (bool, f32) {
        let (w, h) = rgb.dimensions();
        if w < 50 || h < 50 {
            return (false, 0.5);
        }

        let mut quality = image::DynamicImage::ImageRgb8(rgb.clone());
        let mut jpeg_buf = Vec::new();
        if quality
            .write_to(
                &mut std::io::Cursor::new(&mut jpeg_buf),
                image::ImageFormat::Jpeg,
            )
            .is_err()
        {
            return (false, 0.5);
        }

        let recompressed = match image::load_from_memory(&jpeg_buf) {
            Ok(img) => img.to_rgb8(),
            Err(_) => return (false, 0.5),
        };

        let mut ela_map = vec![0u32; 256];

        for y in 0..h {
            for x in 0..w {
                let orig = rgb.get_pixel(x, y);
                let recomp = recompressed.get_pixel(x, y);
                let diff = (orig[0] as i16 - recomp[0] as i16).abs()
                    + (orig[1] as i16 - recomp[1] as i16).abs()
                    + (orig[2] as i16 - recomp[2] as i16).abs();
                let ela_val = (diff / 3).min(255) as usize;
                ela_map[ela_val] += 1;
            }
        }

        let total: u32 = ela_map.iter().sum();
        if total == 0 {
            return (false, 0.5);
        }

        let high_ela: u32 = ela_map[100..].iter().sum();
        let high_ela_ratio = high_ela as f32 / total as f32;

        let tampered = high_ela_ratio > 0.05;
        let confidence = if tampered {
            (high_ela_ratio * 5.0).min(0.98)
        } else {
            (1.0 - high_ela_ratio * 10.0).max(0.3)
        };

        (tampered, confidence)
    }

    fn detect_jpeg_ghost(gray: &image::GrayImage) -> (bool, f32) {
        let (w, h) = gray.dimensions();
        if w < 32 || h < 32 {
            return (false, 0.5);
        }

        let mut block_diff_sum = 0.0f64;
        let mut block_count = 0u32;

        for y in (0..h - 8).step_by(8) {
            for x in (0..w - 8).step_by(8) {
                let mut block = Vec::new();
                for dy in 0..8 {
                    for dx in 0..8 {
                        block.push(gray.get_pixel(x + dx, y + dy)[0] as f64);
                    }
                }
                let mean = block.iter().sum::<f64>() / 64.0;
                let mut var = 0.0f64;
                for &v in &block {
                    var += (v - mean).powi(2);
                }
                var /= 64.0;
                block_diff_sum += var.sqrt();
                block_count += 1;
            }
        }

        if block_count == 0 {
            return (false, 0.5);
        }

        let avg_block_std = block_diff_sum / block_count as f64;

        let mut global_mean = 0.0f64;
        let mut global_count = 0u64;
        for pixel in gray.pixels() {
            global_mean += pixel[0] as f64;
            global_count += 1;
        }
        global_mean /= global_count as f64;

        let mut global_var = 0.0f64;
        for pixel in gray.pixels() {
            global_var += (pixel[0] as f64 - global_mean).powi(2);
        }
        global_var /= global_count as f64;
        let global_std = global_var.sqrt();

        if global_std < 1.0 {
            return (false, 0.5);
        }

        let blockiness = avg_block_std / global_std;
        let ghost_detected = blockiness > 2.5 || blockiness < 0.5;

        let confidence = if ghost_detected {
            ((blockiness - 1.0).abs() * 0.5).min(0.95)
        } else {
            (1.0 - (blockiness - 1.0).abs() * 0.3).max(0.3)
        };

        (ghost_detected, confidence)
    }

    fn detect_color_consistency(rgb: &image::RgbImage) -> (bool, f32) {
        let (w, h) = rgb.dimensions();
        if w < 10 || h < 10 {
            return (false, 0.5);
        }

        let mut mean_r = 0.0f64;
        let mut mean_g = 0.0f64;
        let mut mean_b = 0.0f64;
        let mut count = 0u64;

        for y in (0..h).step_by(5) {
            for x in (0..w).step_by(5) {
                let p = rgb.get_pixel(x, y);
                mean_r += p[0] as f64;
                mean_g += p[1] as f64;
                mean_b += p[2] as f64;
                count += 1;
            }
        }

        if count == 0 {
            return (false, 0.5);
        }

        mean_r /= count as f64;
        mean_g /= count as f64;
        mean_b /= count as f64;

        let mut variance = 0.0f64;
        for y in (0..h).step_by(5) {
            for x in (0..w).step_by(5) {
                let p = rgb.get_pixel(x, y);
                variance += (p[0] as f64 - mean_r).powi(2)
                    + (p[1] as f64 - mean_g).powi(2)
                    + (p[2] as f64 - mean_b).powi(2);
            }
        }
        variance /= (count * 3) as f64;
        let std_dev = variance.sqrt();

        let mut quadrant_means = Vec::new();
        let regions = [
            (0, 0, w / 2, h / 2),
            (w / 2, 0, w, h / 2),
            (0, h / 2, w / 2, h),
            (w / 2, h / 2, w, h),
        ];
        for &(x1, y1, x2, y2) in &regions {
            let mut q_mean = 0.0f64;
            let mut q_count = 0u64;
            for y in y1..y2 {
                for x in x1..x2 {
                    let p = rgb.get_pixel(x, y);
                    q_mean += p[0] as f64 + p[1] as f64 + p[2] as f64;
                    q_count += 1;
                }
            }
            if q_count > 0 {
                q_mean /= q_count as f64;
                quadrant_means.push(q_mean);
            }
        }

        let mut quad_var = 0.0f64;
        let quad_mean: f64 = quadrant_means.iter().sum::<f64>() / quadrant_means.len() as f64;
        for &q in &quadrant_means {
            quad_var += (q - quad_mean).powi(2);
        }
        quad_var /= quadrant_means.len() as f64;
        let quad_std = quad_var.sqrt();

        let inconsistent = quad_std > std_dev * 0.5 && std_dev > 20.0;
        let confidence = if inconsistent {
            (quad_std / std_dev).min(0.95)
        } else {
            (1.0 - quad_std / (std_dev + 1.0)).max(0.3)
        };

        (!inconsistent, confidence)
    }

    fn detect_noise_inconsistency(gray: &image::GrayImage) -> (bool, f32) {
        let (w, h) = gray.dimensions();
        if w < 16 || h < 16 {
            return (false, 0.5);
        }

        let mut grid_noise = Vec::new();
        let tile_size = 32u32;

        for gy in (0..h).step_by(tile_size as usize) {
            for gx in (0..w).step_by(tile_size as usize) {
                let tw = tile_size.min(w - gx);
                let th = tile_size.min(h - gy);

                let mut sum = 0.0f64;
                let mut count = 0u64;
                for y in gy..gy + th {
                    for x in gx..gx + tw {
                        sum += gray.get_pixel(x, y)[0] as f64;
                        count += 1;
                    }
                }
                if count > 0 {
                    grid_noise.push(sum / count as f64);
                }
            }
        }

        if grid_noise.len() < 4 {
            return (false, 0.5);
        }

        let mean = grid_noise.iter().sum::<f64>() / grid_noise.len() as f64;
        let variance =
            grid_noise.iter().map(|&v| (v - mean).powi(2)).sum::<f64>() / grid_noise.len() as f64;
        let std_dev = variance.sqrt();

        let inconsistent = std_dev > 25.0;
        let confidence = if inconsistent {
            (std_dev / 50.0).min(0.95)
        } else {
            (1.0 - std_dev / 50.0).max(0.3)
        };

        (!inconsistent, confidence)
    }
}

#[async_trait]
impl Validator for TamperDetectionEngine {
    fn name(&self) -> &'static str {
        "tamper_detection"
    }

    async fn validate(&self, image_data: &[u8]) -> anyhow::Result<Vec<ValidationResult>> {
        let img = image::load_from_memory(image_data)?;
        let gray = img.to_luma8();
        let rgb = img.to_rgb8();

        let mut results = Vec::new();

        let (substituted, photo_conf) = Self::detect_photo_substitution(&gray);
        results.push(ValidationResult {
            field: "tamper_photo_substitution".to_string(),
            passed: !substituted,
            confidence: photo_conf,
            details: vec![
                if substituted {
                    "Photo substitution detected".to_string()
                } else {
                    "Photo appears authentic".to_string()
                },
                format!("Confidence: {:.2}", photo_conf),
            ],
        });

        let (date_tampered, date_conf) = Self::detect_date_tampering(&gray);
        results.push(ValidationResult {
            field: "tamper_date_tampering".to_string(),
            passed: !date_tampered,
            confidence: date_conf,
            details: vec![
                if date_tampered {
                    "Date field tampering detected".to_string()
                } else {
                    "Date field appears authentic".to_string()
                },
                format!("Confidence: {:.2}", date_conf),
            ],
        });

        let (ela_tampered, ela_conf) = Self::detect_ela_artifacts(&rgb);
        results.push(ValidationResult {
            field: "tamper_ela_artifacts".to_string(),
            passed: !ela_tampered,
            confidence: ela_conf,
            details: vec![
                if ela_tampered {
                    "ELA artifacts detected - possible manipulation".to_string()
                } else {
                    "No ELA artifacts found".to_string()
                },
                format!("Confidence: {:.2}", ela_conf),
            ],
        });

        let (ghost_detected, ghost_conf) = Self::detect_jpeg_ghost(&gray);
        results.push(ValidationResult {
            field: "tamper_jpeg_ghost".to_string(),
            passed: !ghost_detected,
            confidence: ghost_conf,
            details: vec![
                if ghost_detected {
                    "JPEG ghost detected - possible composite image".to_string()
                } else {
                    "No JPEG ghost artifacts found".to_string()
                },
                format!("Confidence: {:.2}", ghost_conf),
            ],
        });

        let (color_consistent, color_conf) = Self::detect_color_consistency(&rgb);
        results.push(ValidationResult {
            field: "tamper_color_consistency".to_string(),
            passed: color_consistent,
            confidence: color_conf,
            details: vec![
                if color_consistent {
                    "Color distribution consistent across document".to_string()
                } else {
                    "Color inconsistency detected across document".to_string()
                },
                format!("Confidence: {:.2}", color_conf),
            ],
        });

        let (noise_consistent, noise_conf) = Self::detect_noise_inconsistency(&gray);
        results.push(ValidationResult {
            field: "tamper_noise_consistency".to_string(),
            passed: noise_consistent,
            confidence: noise_conf,
            details: vec![
                if noise_consistent {
                    "Noise pattern consistent across document".to_string()
                } else {
                    "Noise inconsistency detected - possible splicing".to_string()
                },
                format!("Confidence: {:.2}", noise_conf),
            ],
        });

        Ok(results)
    }
}
