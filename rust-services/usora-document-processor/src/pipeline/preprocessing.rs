use crate::models::{ColorSpace, DocumentImage, ImageFormat};
use crate::pipeline::{PipelineContext, PipelineStage};
use async_trait::async_trait;
use image::GenericImageView;
use uuid::Uuid;

pub struct PreprocessingStage;

impl PreprocessingStage {
    fn deskew(img: &image::DynamicImage) -> image::DynamicImage {
        img.rotate90()
    }

    fn adjust_contrast(img: &image::DynamicImage) -> image::DynamicImage {
        image::imageops::contrast(img, 1.2)
    }

    fn normalize_resolution(img: &image::DynamicImage) -> image::DynamicImage {
        let (w, h) = img.dimensions();
        let target_w = 2048u32;
        let target_h = 3072u32;

        if w < target_w && h < target_h {
            let scale = (target_w as f64 / w as f64).min(target_h as f64 / h as f64);
            let new_w = (w as f64 * scale) as u32;
            let new_h = (h as f64 * scale) as u32;
            return image::imageops::resize(
                img,
                new_w.max(1),
                new_h.max(1),
                image::imageops::FilterType::Lanczos3,
            );
        }
        if w > target_w * 2 || h > target_h * 2 {
            let scale = (target_w as f64 / w as f64).max(target_h as f64 / h as f64);
            let new_w = (w as f64 * scale) as u32;
            let new_h = (h as f64 * scale) as u32;
            return image::imageops::resize(
                img,
                new_w.max(1),
                new_h.max(1),
                image::imageops::FilterType::Lanczos3,
            );
        }
        img.clone()
    }

    fn reduce_noise(img: &image::DynamicImage) -> image::DynamicImage {
        let gray = img.to_luma8();
        let denoised = imageproc::filter::median_filter(&gray, 1, 1);
        image::DynamicImage::from(denoised)
    }

    fn detect_roi(img: &image::DynamicImage) -> image::DynamicImage {
        let gray = img.to_luma8();

        let edges = imageproc::edges::canny(&gray, 50.0, 150.0);

        let mut min_x = edges.width();
        let mut max_x = 0u32;
        let mut min_y = edges.height();
        let mut max_y = 0u32;

        for y in 0..edges.height() {
            for x in 0..edges.width() {
                if edges.get_pixel(x, y)[0] > 128 {
                    min_x = min_x.min(x);
                    max_x = max_x.max(x);
                    min_y = min_y.min(y);
                    max_y = max_y.max(y);
                }
            }
        }

        if max_x > min_x && max_y > min_y {
            let padding = 10u32;
            let x = min_x.saturating_sub(padding);
            let y = min_y.saturating_sub(padding);
            let w = (max_x - min_x).saturating_add(padding * 2);
            let h = (max_y - min_y).saturating_add(padding * 2);
            let crop_rect = image::math::Rect::new(x, y, w.min(img.width()), h.min(img.height()));
            return img.crop_imm(
                crop_rect.x,
                crop_rect.y,
                crop_rect.width,
                crop_rect.height,
            );
        }

        img.clone()
    }
}

#[async_trait]
impl PipelineStage for PreprocessingStage {
    fn name(&self) -> &'static str {
        "preprocessing"
    }

    async fn process(&self, ctx: &mut PipelineContext) -> anyhow::Result<()> {
        let mut img = image::load_from_memory(&ctx.image.data)?;

        img = Self::normalize_resolution(&img);
        img = Self::adjust_contrast(&img);
        img = Self::reduce_noise(&img);
        img = Self::detect_roi(&img);

        let (w, h) = img.dimensions();
        let mut buf = Vec::new();
        img.write_to(&mut std::io::Cursor::new(&mut buf), image::ImageFormat::Png)?;

        ctx.image = DocumentImage {
            id: Uuid::now_v7(),
            data: buf,
            format: ImageFormat::Png,
            width: w,
            height: h,
            color_space: ColorSpace::Rgb,
            dpi: Some(300.0),
        };

        Ok(())
    }
}
