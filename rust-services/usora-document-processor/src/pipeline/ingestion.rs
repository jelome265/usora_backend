use crate::models::{ColorSpace, DocumentImage, ImageFormat};
use crate::pipeline::{PipelineContext, PipelineStage};
use crate::utils;
use async_trait::async_trait;
use image::GenericImageView;
use std::path::{Path, PathBuf};
use uuid::Uuid;

const MAX_FILE_SIZE: u64 = 20 * 1024 * 1024;
const MIN_FILE_SIZE: u64 = 1024;
const ALLOWED_FORMATS: &[&str] = &[
    "image/png",
    "image/jpeg",
    "image/tiff",
    "image/bmp",
    "image/webp",
    "application/pdf",
];

pub struct IngestionStage;

impl IngestionStage {
    fn validate_format(mime_type: &str) -> anyhow::Result<()> {
        if !ALLOWED_FORMATS.contains(&mime_type) {
            anyhow::bail!("Unsupported file format: {}", mime_type);
        }
        Ok(())
    }

    fn validate_size(data: &[u8]) -> anyhow::Result<()> {
        let len = data.len() as u64;
        if len < MIN_FILE_SIZE {
            anyhow::bail!(
                "File too small: {} bytes, minimum is {} bytes",
                len,
                MIN_FILE_SIZE
            );
        }
        if len > MAX_FILE_SIZE {
            anyhow::bail!(
                "File too large: {} bytes, maximum is {} bytes",
                len,
                MAX_FILE_SIZE
            );
        }
        Ok(())
    }

    fn detect_format(data: &[u8]) -> ImageFormat {
        let mime = utils::detect_file_type(data);
        ImageFormat::from_mime(mime).unwrap_or(ImageFormat::Png)
    }

    fn save_temp(data: &[u8], temp_dir: &Path) -> anyhow::Result<PathBuf> {
        std::fs::create_dir_all(temp_dir)?;
        let hash = utils::sha256_hash(data);
        let filename = format!("{}.bin", hash);
        let path = temp_dir.join(&filename);
        if !path.exists() {
            std::fs::write(&path, data)?;
        }
        Ok(path)
    }

    fn extract_metadata(img: &image::DynamicImage) -> std::collections::HashMap<String, String> {
        let mut metadata = std::collections::HashMap::new();
        metadata.insert(
            "width".to_string(),
            img.width().to_string(),
        );
        metadata.insert(
            "height".to_string(),
            img.height().to_string(),
        );
        metadata
    }
}

#[async_trait]
impl PipelineStage for IngestionStage {
    fn name(&self) -> &'static str {
        "ingestion"
    }

    async fn process(&self, ctx: &mut PipelineContext) -> anyhow::Result<()> {
        let data = &ctx.image.data;
        Self::validate_size(data)?;

        let mime = utils::detect_file_type(data);
        Self::validate_format(mime)?;

        let format = Self::detect_format(data);

        let img = image::load_from_memory(data)?;
        let (w, h) = img.dimensions();

        let temp_dir = std::env::temp_dir().join("usora-document-processor");
        let _temp_path = Self::save_temp(data, &temp_dir)?;

        let metadata = Self::extract_metadata(&img);

        ctx.image = DocumentImage {
            id: Uuid::now_v7(),
            data: data.to_vec(),
            format,
            width: w,
            height: h,
            color_space: ColorSpace::Rgb,
            dpi: None,
        };

        ctx.image.id = Uuid::now_v7();

        Ok(())
    }
}
