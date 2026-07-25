use crate::extraction::ExtractionEngine;
use crate::models::{BoundingBox, DocumentImage, ExtractedField, ExtractionMethod};
use async_trait::async_trait;
use image::GenericImageView;
use imageproc::edges::canny;
use imageproc::contours::find_contours;

pub struct BarcodeEngine;

impl BarcodeEngine {
    pub fn new() -> Self {
        Self
    }

    fn preprocess_for_barcode(gray: &image::GrayImage) -> image::GrayImage {
        let equalized = imageproc::contrast::adaptive_threshold(gray, 31);

        let dilated = imageproc::morphology::dilate(
            &equalized,
            imageproc::morphology::Square(3),
            1,
        );

        imageproc::filter::median_filter(&dilated, 1, 1)
    }

    fn detect_barcode_regions(gray: &image::GrayImage) -> Vec<BoundingBox> {
        let edges = canny(gray, 40.0, 120.0);
        let contours = find_contours::<i32>(&edges);

        let mut regions = Vec::new();
        for contour in &contours {
            let rect = contour.bounding_box();
            let area = rect.width() as u64 * rect.height() as u64;
            let aspect = rect.width() as f64 / rect.height() as f64;

            if area > 500 && (aspect > 3.0 || aspect < 0.33) {
                regions.push(BoundingBox {
                    x: rect.x() as f32,
                    y: rect.y() as f32,
                    width: rect.width() as f32,
                    height: rect.height() as f32,
                });
            }
        }
        regions
    }

    fn decode_pdf417(_region: &image::GrayImage) -> Option<String> {
        let h = _region.height();
        let w = _region.width();

        if w < 50 || h < 10 {
            return None;
        }

        let row_samples: Vec<u8> = (0..h).step_by(3).map(|y| {
            let row_sum: u32 = (0..w).map(|x| _region.get_pixel(x, y)[0] as u32).sum();
            (row_sum / w) as u8
        }).collect();

        if row_samples.len() < 5 {
            return None;
        }

        let avg = row_samples.iter().map(|&v| v as u32).sum::<u32>() / row_samples.len() as u32;
        let binary: Vec<u8> = row_samples.iter().map(|&v| if v < avg { 0u8 } else { 1u8 }).collect();

        let width_runs = self::decode_run_lengths(&binary);
        if width_runs.len() < 10 {
            return None;
        }

        let codewords = self::pdf417_codewords_from_runs(&width_runs);
        if codewords.is_empty() {
            return None;
        }

        let text = self::pdf417_codewords_to_text(&codewords);
        if text.is_empty() { None } else { Some(text) }
    }

    fn decode_qr(gray: &image::GrayImage) -> Option<String> {
        let binary = self::local_threshold(gray, 15);

        let finder_positions = self::detect_finder_patterns(&binary);
        if finder_positions.len() < 3 {
            return None;
        }

        let version = self::estimate_qr_version(&binary, &finder_positions);
        let module_size = self::estimate_module_size(&binary, &finder_positions);

        if module_size < 2 {
            return None;
        }

        let bits = self::sample_qr_matrix(&binary, &finder_positions, version, module_size);
        if bits.is_empty() {
            return None;
        }

        let decoded = self::decode_qr_data(&bits);
        decoded
    }

    fn decode_data_matrix(gray: &image::GrayImage) -> Option<String> {
        let binary = self::local_threshold(gray, 15);

        let corners = self::detect_data_matrix_corners(&binary)?;
        let l_shape = self::verify_l_shape(&binary, &corners)?;

        let module_size = self::data_matrix_module_size(&binary, &corners);
        if module_size < 1 {
            return None;
        }

        let rows = ((corners[2].1 - corners[0].1).abs() as f32 / module_size as f32) as u32;
        let cols = ((corners[1].0 - corners[0].0).abs() as f32 / module_size as f32) as u32;

        if rows < 8 || cols < 8 || rows > 144 || cols > 144 {
            return None;
        }

        if !l_shape { return None; }

        let bits = self::sample_data_matrix(&binary, &corners, rows, cols, module_size);
        let decoded = self::decode_dm_data(&bits);
        decoded
    }

    fn local_threshold(gray: &image::GrayImage, block_size: u32) -> image::GrayImage {
        let (w, h) = gray.dimensions();
        let mut binary = image::GrayImage::new(w, h);

        let half = block_size / 2;
        for y in 0..h {
            for x in 0..w {
                let mut sum = 0u32;
                let mut count = 0u32;
                for dy in 0..block_size {
                    for dx in 0..block_size {
                        let py = (y as i32 + dy as i32 - half as i32).max(0).min(h as i32 - 1) as u32;
                        let px = (x as i32 + dx as i32 - half as i32).max(0).min(w as i32 - 1) as u32;
                        sum += gray.get_pixel(px, py)[0] as u32;
                        count += 1;
                    }
                }
                let threshold = (sum / count) as u8;
                let pixel = gray.get_pixel(x, y)[0];
                binary.put_pixel(x, y, if pixel < threshold.saturating_sub(10) { image::Luma([0u8]) } else { image::Luma([255u8]) });
            }
        }
        binary
    }

    fn detect_finder_patterns(binary: &image::GrayImage) -> Vec<(u32, u32)> {
        let (w, h) = binary.dimensions();
        let mut patterns = Vec::new();
        let step = 4u32;

        for y in (0..h).step_by(step as usize) {
            for x in (0..w).step_by(step as usize) {
                let mut run = 0u32;
                let mut state = 0u8;
                let mut runs = [0u32; 5];

                for dx in x..(x + 30).min(w) {
                    let p = binary.get_pixel(dx, y)[0];
                    let bit = if p < 128 { 0u8 } else { 1u8 };

                    if bit != state {
                        if run > 0 && state == 1 {
                            if runs.iter().all(|&r| r > 0) {
                                break;
                            }
                        }
                        if run > 0 {
                            let idx = std::cmp::min(state as usize, 4);
                            runs[idx] = run;
                        }
                        state = bit;
                        run = 1;
                    } else {
                        run += 1;
                    }
                }

                if runs.iter().all(|&r| r > 0) {
                    let ratio = runs[0] as f64 / runs[2] as f64;
                    if (ratio - 1.0).abs() < 0.5 {
                        patterns.push((x, y));
                    }
                }
            }
        }
        patterns
    }

    fn estimate_qr_version(_binary: &image::GrayImage, finders: &[(u32, u32)]) -> u32 {
        if finders.len() < 3 {
            return 1;
        }
        let d1 = distance(finders[0], finders[1]);
        let d2 = distance(finders[0], finders[2]);
        let avg_dist = (d1 + d2) / 2.0;
        let version = ((avg_dist / 14.0) - 2.0).round().max(1.0).min(40.0) as u32;
        version
    }

    fn estimate_module_size(binary: &image::GrayImage, finders: &[(u32, u32)]) -> u32 {
        if finders.is_empty() {
            return 1;
        }
        let (fx, fy) = finders[0];
        let mut total = 0u32;
        let mut count = 0u32;
        for dx in fx..(fx + 20).min(binary.width()) {
            let p = binary.get_pixel(dx, fy)[0];
            if p < 128 {
                total += 1;
            } else if total > 0 {
                if count < 3 { count += 1; } else { break; }
            }
        }
        let module = if total > 0 { total / 7 }.max(1) else { 1 };
        module
    }

    fn sample_qr_matrix(_binary: &image::GrayImage, _finders: &[(u32, u32)], _version: u32, _module_size: u32) -> Vec<Vec<u8>> {
        let size = (_version * 4 + 17) as usize;
        let mut bits = vec![vec![0u8; size]; size];
        bits
    }

    fn decode_qr_data(_bits: &[Vec<u8>]) -> Option<String> {
        None
    }

    fn detect_data_matrix_corners(binary: &image::GrayImage) -> Option<[(u32, u32); 4]> {
        let (w, h) = binary.dimensions();
        let mut corners = [(0u32, 0u32); 4];
        let mut found = 0usize;

        for y in 0..h.min(50) {
            for x in 0..w.min(50) {
                if binary.get_pixel(x, y)[0] < 128 {
                    corners[0] = (x, y);
                    found = 1;
                    break;
                }
            }
            if found > 0 { break; }
        }
        if found == 0 { return None; }

        for x in (w.saturating_sub(50)..w).rev() {
            for y in 0..h.min(50) {
                if binary.get_pixel(x, y)[0] < 128 {
                    corners[1] = (x, y);
                    found = 2;
                    break;
                }
            }
            if found > 1 { break; }
        }
        if found < 2 { return None; }

        for y in (h.saturating_sub(50)..h).rev() {
            for x in 0..w.min(50) {
                if binary.get_pixel(x, y)[0] < 128 {
                    corners[2] = (x, y);
                    found = 3;
                    break;
                }
            }
            if found > 2 { break; }
        }
        if found < 3 { return None; }

        for y in (h.saturating_sub(50)..h).rev() {
            for x in (w.saturating_sub(50)..w).rev() {
                if binary.get_pixel(x, y)[0] < 128 {
                    corners[3] = (x, y);
                    found = 4;
                    break;
                }
            }
            if found > 3 { break; }
        }

        if found < 4 { None } else { Some(corners) }
    }

    fn verify_l_shape(binary: &image::GrayImage, _corners: &[(u32, u32); 4]) -> Option<bool> {
        let (fx, fy) = _corners[0];
        let mut solid_h = 0u32;
        for dx in fx..(fx + 30).min(binary.width()) {
            if binary.get_pixel(dx, fy)[0] < 128 {
                solid_h += 1;
            } else {
                break;
            }
        }
        let mut solid_v = 0u32;
        for dy in fy..(fy + 30).min(binary.height()) {
            if binary.get_pixel(fx, dy)[0] < 128 {
                solid_v += 1;
            } else {
                break;
            }
        }
        Some(solid_h > 5 && solid_v > 5)
    }

    fn data_matrix_module_size(_binary: &image::GrayImage, corners: &[(u32, u32); 4]) -> u32 {
        let dx = if corners[1].0 > corners[0].0 { corners[1].0 - corners[0].0 } else { 10 };
        let dy = if corners[2].1 > corners[0].1 { corners[2].1 - corners[0].1 } else { 10 };
        (dx.min(dy) / 20).max(1)
    }

    fn sample_data_matrix(_binary: &image::GrayImage, _corners: &[(u32, u32); 4], _rows: u32, _cols: u32, _module_size: u32) -> Vec<Vec<u8>> {
        vec![vec![0u8; _cols as usize]; _rows as usize]
    }

    fn decode_dm_data(_bits: &[Vec<u8>]) -> Option<String> {
        None
    }

    fn decode_run_lengths(binary: &[u8]) -> Vec<u32> {
        let mut runs = Vec::new();
        if binary.is_empty() {
            return runs;
        }
        let mut current = binary[0];
        let mut count = 1u32;
        for &b in &binary[1..] {
            if b == current {
                count += 1;
            } else {
                runs.push(count);
                current = b;
                count = 1;
            }
        }
        runs.push(count);
        runs
    }

    fn pdf417_codewords_from_runs(_runs: &[u32]) -> Vec<u16> {
        Vec::new()
    }

    fn pdf417_codewords_to_text(_codewords: &[u16]) -> String {
        String::new()
    }
}

fn distance(a: (u32, u32), b: (u32, u32)) -> f64 {
    let dx = a.0 as f64 - b.0 as f64;
    let dy = a.1 as f64 - b.1 as f64;
    (dx * dx + dy * dy).sqrt()
}

#[async_trait]
impl ExtractionEngine for BarcodeEngine {
    fn name(&self) -> &'static str {
        "barcode"
    }

    async fn extract(&self, image: &DocumentImage) -> anyhow::Result<Vec<ExtractedField>> {
        let img = image::load_from_memory(&image.data)?;
        let gray = img.to_luma8();
        let processed = Self::preprocess_for_barcode(&gray);

        let mut all_decoded: Vec<(&str, String)> = Vec::new();

        if let Some(text) = Self::decode_pdf417(&processed) {
            all_decoded.push(("PDF417", text));
        }

        if let Some(text) = Self::decode_qr(&processed) {
            all_decoded.push(("QR_CODE", text));
        }

        if let Some(text) = Self::decode_data_matrix(&processed) {
            all_decoded.push(("DATA_MATRIX", text));
        }

        let regions = Self::detect_barcode_regions(&gray);
        let mut region_iter = regions.iter();

        let fields: Vec<ExtractedField> = all_decoded
            .into_iter()
            .enumerate()
            .flat_map(|(i, (barcode_type, data))| {
                let bbox = region_iter.next().cloned();
                vec![
                    ExtractedField {
                        name: format!("barcode_{}_type", i),
                        value: barcode_type.to_string(),
                        confidence: 0.85,
                        method: ExtractionMethod::Barcode,
                        raw_text: Some(data.clone()),
                        bounding_box: bbox.clone(),
                    },
                    ExtractedField {
                        name: format!("barcode_{}_data", i),
                        value: data,
                        confidence: 0.90,
                        method: ExtractionMethod::Barcode,
                        raw_text: None,
                        bounding_box: bbox,
                    },
                ]
            })
            .collect();

        if fields.is_empty() {
            anyhow::bail!("No barcodes found in document image");
        }

        Ok(fields)
    }

    fn supported_formats(&self) -> Vec<&'static str> {
        vec!["image/png", "image/jpeg", "image/tiff", "image/bmp"]
    }
}
