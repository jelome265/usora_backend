pub mod ml_ocr;
pub mod tesseract;

use crate::models::BoundingBox;
use async_trait::async_trait;

#[derive(Debug, Clone)]
pub struct OcrResult {
    pub text: String,
    pub confidence: f32,
    pub regions: Vec<TextRegion>,
    pub language: Option<String>,
}

#[derive(Debug, Clone)]
pub struct TextRegion {
    pub text: String,
    pub confidence: f32,
    pub bounding_box: BoundingBox,
    pub region_type: RegionType,
}

#[derive(Debug, Clone)]
pub enum RegionType {
    Paragraph,
    Line,
    Word,
    Field { name: String },
}

#[async_trait]
pub trait OcrEngine: Send + Sync {
    fn name(&self) -> &'static str;
    async fn perform_ocr(&self, image_data: &[u8]) -> anyhow::Result<OcrResult>;
    fn is_available(&self) -> bool;
}
