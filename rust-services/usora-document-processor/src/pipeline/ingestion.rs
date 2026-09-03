use crate::models::{ColorSpace, DocumentImage, ImageFormat};
use crate::pipeline::{PipelineContext, PipelineStage};
use crate::utils;
use async_trait::async_trait;
use image::GenericImageView;
use std::path::{Path, PathBuf};
use uuid::Uuid;

const MAX_FILE_SIZE: u64 = 20 * 1024 * 1024;
const MIN_FILE_SIZE: u64 = 1024;
// F-024: the 20MB check above bounds the COMPRESSED file size, not what
// it decodes to -- a small, highly-compressible image (e.g. a mostly
// solid-color PNG) can decode into a bitmap many times larger than its
// file size, a classic decompression-bomb pattern. Nothing previously
// checked the DECODED width/height/pixel count at all before handing the
// image to every subsequent pipeline stage (preprocessing, OCR,
// authenticity checks), each of which allocates buffers proportional to
// image dimensions. These limits are generous for any real ID document
// scan (even a very high-DPI passport photo page is nowhere near this)
// while still bounding worst-case memory/CPU from a single decoded image.
const MAX_IMAGE_DIMENSION: u32 = 10_000;
const MAX_IMAGE_PIXELS: u64 = 40_000_000; // ~40 megapixels
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

    /// F-024: rejects a decoded image whose dimensions or total pixel
    /// count exceed the limits above -- must be called immediately after
    /// decode and BEFORE any further pipeline stage touches the image,
    /// since every subsequent stage allocates memory/CPU proportional to
    /// these dimensions.
    fn validate_dimensions(width: u32, height: u32) -> anyhow::Result<()> {
        if width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION {
            anyhow::bail!(
                "Image dimensions {}x{} exceed the maximum allowed dimension of {} pixels",
                width, height, MAX_IMAGE_DIMENSION
            );
        }
        let pixel_count = width as u64 * height as u64;
        if pixel_count > MAX_IMAGE_PIXELS {
            anyhow::bail!(
                "Image has {} total pixels ({}x{}), exceeding the maximum allowed {} pixels -- \
                 this file's compressed size does not reflect its decoded memory footprint",
                pixel_count, width, height, MAX_IMAGE_PIXELS
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
        Self::validate_dimensions(w, h)?;

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

#[cfg(test)]
mod tests {
    use super::*;

    /// F-024 regression: a small/moderate file with reasonable decoded
    /// dimensions must pass.
    #[test]
    fn validate_dimensions_accepts_reasonable_document_scan() {
        assert!(IngestionStage::validate_dimensions(2481, 3508).is_ok(), // A4 at 300dpi
            "a typical A4-at-300dpi document scan must not be rejected");
    }

    /// F-024 regression: the actual decompression-bomb scenario this
    /// finding is about -- a decoded image whose dimensions vastly
    /// exceed anything a real document scan would ever need must be
    /// rejected, independent of how small the original compressed file
    /// was.
    #[test]
    fn validate_dimensions_rejects_oversized_width_or_height() {
        assert!(IngestionStage::validate_dimensions(50_000, 100).is_err(),
            "a single oversized dimension must be rejected even if total pixel count is otherwise small");
        assert!(IngestionStage::validate_dimensions(100, 50_000).is_err());
    }

    #[test]
    fn validate_dimensions_rejects_excessive_total_pixel_count() {
        // Neither dimension alone exceeds MAX_IMAGE_DIMENSION, but their
        // product does -- this is the case a naive "check width < X AND
        // height < X" implementation would miss.
        assert!(IngestionStage::validate_dimensions(9_000, 9_000).is_err(),
            "9000x9000 (81 megapixels) must be rejected even though neither dimension alone exceeds the cap");
    }

    #[test]
    fn validate_dimensions_boundary_is_inclusive() {
        assert!(IngestionStage::validate_dimensions(MAX_IMAGE_DIMENSION, 1).is_ok(),
            "exactly at the dimension limit should still be accepted");
        assert!(IngestionStage::validate_dimensions(MAX_IMAGE_DIMENSION + 1, 1).is_err(),
            "one pixel over the dimension limit must be rejected");
    }
}

