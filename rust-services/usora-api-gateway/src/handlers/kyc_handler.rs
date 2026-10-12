use axum::extract::{Multipart, State};
use axum::http::StatusCode;
use axum::Json;
use std::sync::Arc;

use crate::models::*;
use crate::proto;
use crate::utils;
use crate::AppState;

fn format_verification_status(status: i32) -> String {
    proto::identity::VerificationStatus::try_from(status)
        .map(|s| format!("{:?}", s))
        .unwrap_or_else(|_| "UNKNOWN".to_string())
        .to_lowercase()
}

pub async fn submit_document(
    State(state): State<Arc<AppState>>,
    mut multipart: Multipart,
) -> Result<axum::response::Response, (StatusCode, Json<ApiResponse<()>>)> {
    let request_id = utils::uuid_v7();

    let mut verification_id = String::new();
    let mut document_type = String::new();
    let mut document_data = Vec::new();
    let mut filename = String::new();
    let mut content_type = String::new();

    while let Ok(Some(field)) = multipart.next_field().await {
        let name = field.name().unwrap_or("").to_string();
        match name.as_str() {
            "verification_id" => verification_id = field.text().await.unwrap_or_default(),
            "document_type" => document_type = field.text().await.unwrap_or_default(),
            "filename" => filename = field.file_name().unwrap_or("").to_string(),
            "content_type" => content_type = field.content_type().unwrap_or("").to_string(),
            "document" => document_data = field.bytes().await.unwrap_or_default().to_vec(),
            _ => {}
        }
    }

    if verification_id.is_empty() || document_data.is_empty() {
        return Err((
            StatusCode::BAD_REQUEST,
            Json(ApiResponse::error(
                "verification_id and document are required",
                request_id,
            )),
        ));
    }

    let doc_type = match document_type.to_lowercase().as_str() {
        "passport" => 1,
        "drivers_license" => 2,
        "national_id" => 3,
        "residence_permit" => 4,
        "visa" => 5,
        _ => 0,
    };

    let req = proto::identity::SubmitDocumentRequest {
        verification_id,
        tenant_id: String::new(),
        document_type: doc_type,
        document_data,
        filename,
        content_type,
        metadata: Default::default(),
    };

    let mut client = state.grpc_clients.identity.clone();
    let response = client.submit_document(req).await.map_err(|e| {
        (
            StatusCode::BAD_GATEWAY,
            Json(ApiResponse::error(
                format!("document submission failed: {e}"),
                request_id.clone(),
            )),
        )
    })?;

    let inner = response.into_inner();
    let resp = SubmitDocumentResponse {
        document_id: inner.document_id,
        status: format_verification_status(inner.status),
        accepted: inner.accepted,
    };

    Ok(utils::created_response(resp))
}

pub async fn submit_biometric(
    State(state): State<Arc<AppState>>,
    mut multipart: Multipart,
) -> Result<axum::response::Response, (StatusCode, Json<ApiResponse<()>>)> {
    let request_id = utils::uuid_v7();

    let mut verification_id = String::new();
    let mut face_image = Vec::new();
    let mut liveness_video = Vec::new();

    while let Ok(Some(field)) = multipart.next_field().await {
        let name = field.name().unwrap_or("").to_string();
        match name.as_str() {
            "verification_id" => verification_id = field.text().await.unwrap_or_default(),
            "face_image" => face_image = field.bytes().await.unwrap_or_default().to_vec(),
            "liveness_video" => liveness_video = field.bytes().await.unwrap_or_default().to_vec(),
            _ => {}
        }
    }

    if verification_id.is_empty() {
        return Err((
            StatusCode::BAD_REQUEST,
            Json(ApiResponse::error(
                "verification_id is required",
                request_id,
            )),
        ));
    }

    if face_image.is_empty() {
        return Err((
            StatusCode::BAD_REQUEST,
            Json(ApiResponse::error("face_image is required", request_id)),
        ));
    }

    let req = proto::identity::SubmitBiometricRequest {
        verification_id,
        tenant_id: String::new(),
        face_image,
        liveness_video,
        quality_scores: Default::default(),
        metadata: Default::default(),
    };

    let mut client = state.grpc_clients.identity.clone();
    let response = client.submit_biometric(req).await.map_err(|e| {
        (
            StatusCode::BAD_GATEWAY,
            Json(ApiResponse::error(
                format!("biometric submission failed: {e}"),
                request_id.clone(),
            )),
        )
    })?;

    let inner = response.into_inner();
    let resp = SubmitBiometricResponse {
        biometric_id: inner.biometric_id,
        status: format_verification_status(inner.status),
        accepted: inner.accepted,
        liveness_confidence: inner.liveness_confidence,
    };

    Ok(utils::created_response(resp))
}
