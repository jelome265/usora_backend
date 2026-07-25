use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BiometricSubmission {
    pub submission_id: Uuid,
    pub user_id: Uuid,
    pub session_id: Uuid,
    pub images: Vec<FaceImage>,
    pub liveness_check: Option<LivenessCheck>,
    pub metadata: serde_json::Value,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FaceImage {
    pub image_id: Uuid,
    pub data: Vec<u8>,
    pub format: ImageFormat,
    pub source: ImageSource,
    pub capture_metadata: Option<CaptureMetadata>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum ImageFormat {
    Jpeg,
    Png,
    Webp,
    Bmp,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum ImageSource {
    LiveCapture,
    Uploaded,
    Document,
    VideoFrame,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CaptureMetadata {
    pub device_id: Option<String>,
    pub camera_type: Option<String>,
    pub resolution: Option<(u32, u32)>,
    pub frame_rate: Option<f32>,
    pub exposure_time: Option<f64>,
    pub iso: Option<u32>,
    pub focal_length: Option<f64>,
    pub has_flash: bool,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FaceMatchResult {
    pub match_id: Uuid,
    pub submission_id: Uuid,
    pub user_id: Uuid,
    pub verification_result: VerificationResult,
    pub match_details: MatchDetails,
    pub liveness_result: Option<LivenessResult>,
    pub quality_metrics: QualityMetrics,
    pub confidence_score: f64,
    pub processing_time_ms: u64,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum VerificationResult {
    Verified,
    Failed,
    Inconclusive,
    NeedsReview,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MatchDetails {
    pub match_type: MatchType,
    pub similarity_score: f64,
    pub threshold_applied: f64,
    pub matched_user_ids: Vec<Uuid>,
    pub top_k_scores: Vec<(Uuid, f64)>,
    pub gallery_size: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum MatchType {
    OneToOne,
    OneToMany,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LivenessCheck {
    pub check_type: LivenessCheckType,
    pub challenge: Option<serde_json::Value>,
    pub response: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum LivenessCheckType {
    Active,
    Passive,
    Both,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LivenessResult {
    pub is_live: bool,
    pub confidence: f64,
    pub spoof_type: SpoofType,
    pub details: LivenessDetails,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum SpoofType {
    None,
    Replay,
    Print3d,
    SiliconeMask,
    Deepfake,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LivenessDetails {
    pub texture_score: f64,
    pub motion_score: f64,
    pub depth_score: f64,
    pub color_distribution_score: f64,
    pub specular_reflection_score: f64,
    pub micro_expression_score: f64,
    pub action_verification: Option<ActionVerification>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ActionVerification {
    pub actions_requested: Vec<String>,
    pub actions_performed: Vec<String>,
    pub action_scores: Vec<f64>,
    pub overall_score: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QualityMetrics {
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
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditEvent {
    pub event_id: Uuid,
    pub event_type: AuditEventType,
    pub user_id: Uuid,
    pub submission_id: Option<Uuid>,
    pub details: serde_json::Value,
    pub ip_address: Option<String>,
    pub user_agent: Option<String>,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum AuditEventType {
    BiometricSubmission,
    FaceDetectionStarted,
    FaceDetectionCompleted,
    QualityCheckPassed,
    QualityCheckFailed,
    EmbeddingGenerated,
    MatchAttempted,
    MatchFound,
    MatchNotFound,
    LivenessCheckPassed,
    LivenessCheckFailed,
    VerificationCompleted,
    Error,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProcessingTask {
    pub task_id: Uuid,
    pub task_type: TaskType,
    pub payload: serde_json::Value,
    pub priority: u32,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum TaskType {
    VerifyFace,
    IdentifyFace,
    RegisterFace,
    LivenessCheck,
    BatchProcess,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskResult {
    pub task_id: Uuid,
    pub status: TaskStatus,
    pub result: Option<serde_json::Value>,
    pub error: Option<String>,
    pub processing_time_ms: u64,
    pub completed_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum TaskStatus {
    Pending,
    Processing,
    Completed,
    Failed,
    TimedOut,
}
