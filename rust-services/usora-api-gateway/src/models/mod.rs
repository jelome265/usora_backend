use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApiResponse<T: Serialize> {
    pub success: bool,
    pub data: Option<T>,
    pub error: Option<String>,
    pub request_id: String,
    pub timestamp: DateTime<Utc>,
}

impl<T: Serialize> ApiResponse<T> {
    pub fn success(data: T, request_id: String) -> Self {
        Self {
            success: true,
            data: Some(data),
            error: None,
            request_id,
            timestamp: Utc::now(),
        }
    }

    pub fn error(error: impl Into<String>, request_id: String) -> Self {
        Self {
            success: false,
            data: None,
            error: Some(error.into()),
            request_id,
            timestamp: Utc::now(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ErrorResponse {
    pub error: String,
    pub code: u16,
    pub request_id: String,
    pub timestamp: DateTime<Utc>,
}

impl ErrorResponse {
    pub fn new(error: impl Into<String>, code: u16, request_id: String) -> Self {
        Self {
            error: error.into(),
            code,
            request_id,
            timestamp: Utc::now(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PaginatedResponse<T> {
    pub items: Vec<T>,
    pub total_count: i32,
    pub page_token: Option<String>,
    pub next_page_token: Option<String>,
}

impl<T> PaginatedResponse<T> {
    pub fn new(items: Vec<T>, total_count: i32) -> Self {
        Self {
            items,
            total_count,
            page_token: None,
            next_page_token: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HealthStatus {
    pub status: String,
    pub version: String,
    pub uptime_seconds: f64,
    pub checks: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VerifyIdentityRequest {
    pub tenant_id: String,
    pub user_reference: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VerifyIdentityResponse {
    pub verification_id: String,
    pub status: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StartKycRequest {
    pub tenant_id: String,
    pub user_reference: String,
    pub callback_url: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StartKycResponse {
    pub verification_id: String,
    pub session_url: String,
    pub status: String,
    pub expires_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SubmitDocumentRequest {
    pub verification_id: String,
    pub document_type: String,
    pub filename: String,
    pub content_type: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SubmitDocumentResponse {
    pub document_id: String,
    pub status: String,
    pub accepted: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SubmitBiometricRequest {
    pub verification_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SubmitBiometricResponse {
    pub biometric_id: String,
    pub status: String,
    pub accepted: bool,
    pub liveness_confidence: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TenantConfigResponse {
    pub tenant_id: String,
    pub name: String,
    pub domain: String,
    pub status: String,
    pub settings: HashMap<String, String>,
    pub features: Vec<String>,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateTenantRequest {
    pub name: String,
    pub domain: String,
    pub settings: HashMap<String, String>,
    pub features: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateTenantRequest {
    pub name: Option<String>,
    pub domain: Option<String>,
    pub settings: Option<HashMap<String, String>>,
    pub features: Option<Vec<String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VerificationsListResponse {
    pub verifications: Vec<VerificationSummary>,
    pub total_count: i32,
    pub next_page_token: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VerificationSummary {
    pub verification_id: String,
    pub tenant_id: String,
    pub user_reference: String,
    pub status: String,
    pub risk_score: f32,
    pub created_at: String,
}
