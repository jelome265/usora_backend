use crate::models::BoundingBox;
use crate::ocr::{OcrEngine, OcrResult, RegionType, TextRegion};
use async_trait::async_trait;
use image::GenericImageView;
use std::path::PathBuf;

pub struct TesseractOcr {
    data_path: PathBuf,
    languages: Vec<String>,
}

impl TesseractOcr {
    pub fn new(data_path: PathBuf) -> Self {
        Self {
            data_path,
            languages: vec!["eng".to_string()],
        }
    }

    pub fn with_languages(mut self, langs: Vec<String>) -> Self {
        self.languages = langs;
        self
    }

    fn preprocess_image(img: &image::DynamicImage) -> image::GrayImage {
        let gray = img.to_luma8();

        let adjusted = imageproc::contrast::adaptive_threshold(&gray, 41);

        let denoised = imageproc::filter::median_filter(&adjusted, 2, 2);

        let resized = image::imageops::resize(
            &denoised,
            denoised.width().max(1200),
            denoised.height().max(1600),
            image::imageops::FilterType::Lanczos3,
        );

        resized
    }

    fn run_tesseract(data_path: &PathBuf, processed: &image::GrayImage) -> anyhow::Result<String> {
        let mut ocr =
            tesseract::Tesseract::new(Some("eng"), Some(data_path.to_string_lossy().to_string()))?;

        ocr.set_image_from_bytes(processed.as_raw())?;
        ocr.set_page_seg_mode(3);
        ocr.set_variable("tessedit_char_whitelist", "");
        ocr.set_variable("user_defined_dpi", "300");
        let text = ocr.get_text()?;

        Ok(text)
    }

    fn analyze_layout(_processed: &image::GrayImage) -> Vec<TextRegion> {
        Vec::new()
    }

    fn estimate_confidence(text: &str) -> f32 {
        if text.is_empty() {
            return 0.0;
        }
        let total = text.len() as f32;
        let non_alpha = text
            .chars()
            .filter(|c| c.is_ascii_alphanumeric() || c.is_whitespace())
            .count() as f32;
        (non_alpha / total).min(1.0)
    }
}

#[async_trait]
impl OcrEngine for TesseractOcr {
    fn name(&self) -> &'static str {
        "tesseract"
    }

    async fn perform_ocr(&self, image_data: &[u8]) -> anyhow::Result<OcrResult> {
        let img = image::load_from_memory(image_data)?;
        let processed = Self::preprocess_image(&img);
        let text = Self::run_tesseract(&self.data_path, &processed)?;
        let confidence = Self::estimate_confidence(&text);
        let regions = Self::analyze_layout(&processed);

        Ok(OcrResult {
            text,
            confidence,
            regions,
            language: Some("eng".to_string()),
        })
    }

    fn is_available(&self) -> bool {
        self.data_path.join("eng.traineddata").exists()
    }
}
