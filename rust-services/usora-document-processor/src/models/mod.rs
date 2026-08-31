use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExtractedField {
    pub name: String,
    pub value: String,
    pub confidence: f32,
    pub method: ExtractionMethod,
    pub raw_text: Option<String>,
    pub bounding_box: Option<BoundingBox>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BoundingBox {
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum ExtractionMethod {
    Ocr,
    Mrz,
    Barcode,
    Nfc,
    MlOcr,
}

impl std::fmt::Display for ExtractionMethod {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ExtractionMethod::Ocr => write!(f, "ocr"),
            ExtractionMethod::Mrz => write!(f, "mrz"),
            ExtractionMethod::Barcode => write!(f, "barcode"),
            ExtractionMethod::Nfc => write!(f, "nfc"),
            ExtractionMethod::MlOcr => write!(f, "ml_ocr"),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DocumentImage {
    pub id: Uuid,
    pub data: Vec<u8>,
    pub format: ImageFormat,
    pub width: u32,
    pub height: u32,
    pub color_space: ColorSpace,
    pub dpi: Option<f32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum ImageFormat {
    Png,
    Jpeg,
    Tiff,
    Bmp,
    Webp,
    Pdf,
}

impl ImageFormat {
    pub fn from_mime(mime: &str) -> Option<Self> {
        match mime {
            "image/png" => Some(ImageFormat::Png),
            "image/jpeg" | "image/jpg" => Some(ImageFormat::Jpeg),
            "image/tiff" => Some(ImageFormat::Tiff),
            "image/bmp" => Some(ImageFormat::Bmp),
            "image/webp" => Some(ImageFormat::Webp),
            "application/pdf" => Some(ImageFormat::Pdf),
            _ => None,
        }
    }

    pub fn from_ext(ext: &str) -> Option<Self> {
        match ext.to_lowercase().as_str() {
            "png" => Some(ImageFormat::Png),
            "jpg" | "jpeg" => Some(ImageFormat::Jpeg),
            "tiff" | "tif" => Some(ImageFormat::Tiff),
            "bmp" => Some(ImageFormat::Bmp),
            "webp" => Some(ImageFormat::Webp),
            "pdf" => Some(ImageFormat::Pdf),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum ColorSpace {
    Rgb,
    Grayscale,
    Cmyk,
    Ycbcr,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuthenticityScore {
    pub overall_score: f32,
    pub tamper_detection_score: f32,
    pub hologram_verification_score: f32,
    pub font_analysis_score: f32,
    pub uv_check_score: f32,
    pub digital_signature_score: f32,
    pub individual_checks: HashMap<String, f32>,
    // F-019: explicit, structured list of which entries in
    // `individual_checks` (by field name) are visible-light heuristics
    // only, not genuine forensic UV/IR/hologram verification -- see
    // validation/authenticity.rs's AuthenticityCheckEngine, which already
    // suffixes these field names with "_heuristic" and caps their
    // confidence, but that distinction previously never reached this
    // struct at all: individual_checks was always populated as an empty
    // HashMap, and hologram_verification_score/uv_check_score were always
    // hardcoded to 0.0 regardless of what the heuristics actually found.
    // A downstream consumer (risk-scoring-engine, gateway, frontend) can
    // now check this list directly instead of needing to know or guess
    // this service's internal field-naming convention -- this is the
    // structured signal the acceptance criterion ("risk engines
    // distinguish heuristic evidence from verified forensic evidence")
    // asks for.
    pub heuristic_only_checks: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DocumentValidation {
    pub is_valid: bool,
    pub is_tampered: bool,
    pub authenticity: AuthenticityScore,
    pub flags: Vec<String>,
    pub warnings: Vec<String>,
    pub validation_summary: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProcessedDocument {
    pub document_id: Uuid,
    pub tenant_id: String,
    pub verification_id: String,
    pub status: DocumentStatus,
    pub data: DocumentData,
    pub validation: Option<DocumentValidation>,
    pub processing_time_ms: f64,
    pub methods_used: Vec<ExtractionMethod>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum DocumentStatus {
    Pending,
    Processing,
    Completed,
    Failed(String),
    Rejected(String),
}

impl std::fmt::Display for DocumentStatus {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DocumentStatus::Pending => write!(f, "Pending"),
            DocumentStatus::Processing => write!(f, "Processing"),
            DocumentStatus::Completed => write!(f, "Completed"),
            DocumentStatus::Failed(reason) => write!(f, "Failed({})", reason),
            DocumentStatus::Rejected(reason) => write!(f, "Rejected({})", reason),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DocumentData {
    pub document_id: Uuid,
    pub fields: Vec<ExtractedField>,
    pub document_type: Option<String>,
    pub country_code: Option<String>,
    pub mrz_line: Option<String>,
    pub encoded_face: Option<Vec<u8>>,
    pub raw_fields: HashMap<String, String>,
    pub metadata: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProcessingMetrics {
    pub documents_processed: u64,
    pub success_count: u64,
    pub failure_count: u64,
    pub avg_processing_time_ms: f64,
    pub total_processing_time_ms: f64,
    pub extraction_methods_used: HashMap<String, u64>,
    pub validation_failures: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ValidationResult {
    pub field: String,
    pub passed: bool,
    pub confidence: f32,
    pub details: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MrzResult {
    pub document_type: String,
    pub issuing_country: String,
    pub document_number: String,
    pub expiry_date: String,
    pub date_of_birth: String,
    pub sex: String,
    pub nationality: String,
    pub surname: String,
    pub given_names: String,
    pub optional_data: Option<String>,
    pub raw_text: String,
    pub checksums_valid: bool,
    pub format: MrzFormat,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum MrzFormat {
    Td1,
    Td2,
    Td3,
    Unknown,
}

impl std::fmt::Display for MrzFormat {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            MrzFormat::Td1 => write!(f, "td1"),
            MrzFormat::Td2 => write!(f, "td2"),
            MrzFormat::Td3 => write!(f, "td3"),
            MrzFormat::Unknown => write!(f, "unknown"),
        }
    }
}
