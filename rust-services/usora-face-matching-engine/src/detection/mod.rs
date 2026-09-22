pub mod face_detector;
pub mod quality_check;

use async_trait::async_trait;
use serde::{Deserialize, Serialize};

use crate::utils::BBox;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DetectedFace {
    pub bbox: BBox,
    pub landmarks: Vec<[f64; 2]>,
    pub confidence: f32,
    pub quality_score: Option<QualityScore>,
    pub face_angle: f64,
    pub rotation_angle: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QualityScore {
    pub overall_score: f64,
    pub brightness: f64,
    pub contrast: f64,
    pub sharpness: f64,
    pub blur_detected: bool,
    pub blur_score: f64,
    pub face_size_valid: bool,
    pub face_position_valid: bool,
    pub eye_openness: f64,
    pub obstruction_detected: bool,
    pub obstruction_type: Option<String>,
    pub resolution_score: f64,
    pub passes_quality_gate: bool,
    pub details: QualityDetails,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QualityDetails {
    pub histogram_analysis: HistogramAnalysis,
    pub face_geometry: FaceGeometry,
    pub texture_analysis: TextureAnalysis,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HistogramAnalysis {
    pub mean: f64,
    pub std_dev: f64,
    pub skewness: f64,
    pub entropy: f64,
    pub low_frequency_ratio: f64,
    pub high_frequency_ratio: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FaceGeometry {
    pub face_width: f64,
    pub face_height: f64,
    pub face_ratio: f64,
    pub asymmetry_score: f64,
    pub relative_size_to_image: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TextureAnalysis {
    pub lbp_variance: f64,
    pub frequency_energy: f64,
    pub local_contrast: f64,
    pub gradient_magnitude: f64,
}

#[async_trait]
pub trait FaceDetector: Send + Sync {
    async fn detect_faces(
        &self,
        image: &image::DynamicImage,
    ) -> anyhow::Result<Vec<DetectedFace>>;

    async fn detect_single_face(
        &self,
        image: &image::DynamicImage,
    ) -> anyhow::Result<Option<DetectedFace>>;

    fn min_face_size(&self) -> u32;
    fn confidence_threshold(&self) -> f32;
}
