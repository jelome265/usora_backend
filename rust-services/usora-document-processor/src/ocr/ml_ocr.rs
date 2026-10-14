use crate::models::BoundingBox;
use crate::ocr::{OcrEngine, OcrResult, RegionType, TextRegion};
use async_trait::async_trait;
use image::GenericImageView;
use ndarray::Array3;
use std::path::PathBuf;

pub struct MlOcrEngine {
    model_path: PathBuf,
    model_loaded: bool,
    input_width: u32,
    input_height: u32,
}

impl MlOcrEngine {
    pub fn new(model_path: PathBuf) -> Self {
        let model_path_full = model_path.join("document_ocr.onnx");
        let model_loaded = model_path_full.exists();
        Self {
            model_path,
            model_loaded,
            input_width: 640,
            input_height: 640,
        }
    }

    pub fn with_input_size(mut self, w: u32, h: u32) -> Self {
        self.input_width = w;
        self.input_height = h;
        self
    }

    pub fn load_model(&mut self) -> anyhow::Result<()> {
        let model_path = self.model_path.join("document_ocr.onnx");
        if !model_path.exists() {
            anyhow::bail!("ONNX model not found at {:?}", model_path);
        }
        self.model_loaded = true;
        Ok(())
    }

    fn preprocess_for_model(
        img: &image::DynamicImage,
        target_w: u32,
        target_h: u32,
    ) -> anyhow::Result<(Array3<f32>, f32, u32, u32)> {
        let (orig_w, orig_h) = img.dimensions();
        let scale = (target_w as f32 / orig_w as f32).min(target_h as f32 / orig_h as f32);
        let new_w = (orig_w as f32 * scale) as u32;
        let new_h = (orig_h as f32 * scale) as u32;

        let resized = img.resize_exact(
            new_w.max(1),
            new_h.max(1),
            image::imageops::FilterType::Lanczos3,
        );

        let mut padded = image::DynamicImage::new_rgb8(target_w, target_h);
        let (pw, ph) = padded.dimensions();
        let paste_x = (pw - new_w) / 2;
        let paste_y = (ph - new_h) / 2;
        image::imageops::overlay(&mut padded, &resized, paste_x as i64, paste_y as i64);

        let rgb = padded.to_rgb8();
        let (w, h) = rgb.dimensions();
        let mut array = Array3::<f32>::zeros((3, h as usize, w as usize));

        for y in 0..h {
            for x in 0..w {
                let pixel = rgb.get_pixel(x, y);
                array[[0, y as usize, x as usize]] = (pixel[0] as f32 / 255.0 - 0.485) / 0.229;
                array[[1, y as usize, x as usize]] = (pixel[1] as f32 / 255.0 - 0.456) / 0.224;
                array[[2, y as usize, x as usize]] = (pixel[2] as f32 / 255.0 - 0.406) / 0.225;
            }
        }

        Ok((array, scale, paste_x, paste_y))
    }

    fn run_inference_tract(
        model_path: &PathBuf,
        input: Array3<f32>,
    ) -> anyhow::Result<tract_onnx::prelude::TValue> {
        use tract_onnx::prelude::*;

        let model = onnx()
            .model_for_path(model_path.join("document_ocr.onnx"))?
            .with_input_fact(
                0,
                InferenceFact::dt_shape(f32::datum_type(), tvec!(1, 3, 640, 640)),
            )?
            .into_optimized()?
            .into_runnable()?;

        let tensor = Tensor::from_shape(&[1, 3, 640, 640], &input.as_slice()?)?;
        let result = model.run(tvec!(tensor.into()))?;
        Ok(result[0].clone())
    }

    fn run_inference(model_path: &PathBuf, input: Array3<f32>) -> anyhow::Result<Vec<Vec<f32>>> {
        if !model_path.join("document_ocr.onnx").exists() {
            anyhow::bail!(
                "ONNX model file not found at {:?}/document_ocr.onnx",
                model_path
            );
        }
        let result = Self::run_inference_tract(model_path, input)?;
        let output = result.as_slice::<f32>()?;
        let batch_size = 1;
        let num_classes = output.len() / batch_size;
        let mut results = Vec::with_capacity(batch_size);
        for b in 0..batch_size {
            let start = b * num_classes;
            let end = start + num_classes;
            results.push(output[start..end].to_vec());
        }
        Ok(results)
    }

    fn decode_output(
        output: Vec<Vec<f32>>,
        _scale: f32,
        _pad_x: u32,
        _pad_y: u32,
    ) -> (Vec<TextRegion>, String) {
        let mut regions = Vec::new();
        let mut full_text = String::new();

        for (i, row) in output.iter().enumerate() {
            if row.is_empty() {
                continue;
            }

            let max_idx = row
                .iter()
                .enumerate()
                .max_by(|a, b| a.1.partial_cmp(b.1).unwrap_or(std::cmp::Ordering::Equal))
                .map(|(idx, _)| idx)
                .unwrap_or(0);

            let confidence = row[max_idx];

            if confidence < 0.3 {
                continue;
            }

            let char_str = Self::map_index_to_char(max_idx);

            if i % 4 == 0 && !char_str.is_empty() {
                let x = _pad_x as f32;
                let y = (i as f32 / 4.0) * 32.0;
                let w = 32.0;
                let h = 32.0;

                regions.push(TextRegion {
                    text: char_str.clone(),
                    confidence,
                    bounding_box: BoundingBox {
                        x: x / _scale,
                        y: y / _scale,
                        width: w / _scale,
                        height: h / _scale,
                    },
                    region_type: RegionType::Field {
                        name: format!("field_{}", i),
                    },
                });

                if !full_text.is_empty() {
                    full_text.push(' ');
                }
                full_text.push_str(&char_str);
            }
        }

        if full_text.is_empty() {
            full_text = "ML_OCR_NO_TEXT_DETECTED".to_string();
        }

        (regions, full_text)
    }

    fn map_index_to_char(idx: usize) -> String {
        const CHARS: &[u8] =
            b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 .-/:()&+,'";
        if idx < CHARS.len() {
            String::from_utf8_lossy(&[CHARS[idx]]).to_string()
        } else {
            String::new()
        }
    }
}

#[async_trait]
impl OcrEngine for MlOcrEngine {
    fn name(&self) -> &'static str {
        "ml_ocr"
    }

    async fn perform_ocr(&self, image_data: &[u8]) -> anyhow::Result<OcrResult> {
        let img = image::load_from_memory(image_data)?;

        let (input, scale, pad_x, pad_y) =
            Self::preprocess_for_model(&img, self.input_width, self.input_height)?;

        let output = Self::run_inference(&self.model_path, input)?;

        let (regions, full_text) = Self::decode_output(output, scale, pad_x, pad_y);

        let avg_confidence = if regions.is_empty() {
            0.0
        } else {
            regions.iter().map(|r| r.confidence).sum::<f32>() / regions.len() as f32
        };

        Ok(OcrResult {
            text: full_text,
            confidence: avg_confidence,
            regions,
            language: Some("eng".to_string()),
        })
    }

    fn is_available(&self) -> bool {
        self.model_loaded
    }
}

impl Default for MlOcrEngine {
    fn default() -> Self {
        Self::new(PathBuf::from("./models"))
    }
}
