use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::Json;
use serde::Deserialize;
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

fn format_timestamp(ts: Option<prost_types::Timestamp>) -> String {
    ts.map(|t| format!("{}.{:09}", t.seconds, t.nanos))
        .unwrap_or_default()
}

#[derive(Deserialize)]
pub struct ListVerificationsQuery {
    pub user_reference: Option<String>,
    pub status: Option<String>,
    pub page_size: Option<i32>,
    pub page_token: Option<String>,
}

pub async fn verify_identity(
    State(state): State<Arc<AppState>>,
    Json(body): Json<VerifyIdentityRequest>,
) -> Result<axum::response::Response, (StatusCode, Json<ApiResponse<()>>)> {
    let request_id = utils::uuid_v7();

    let req = proto::identity::VerifyIdentityRequest {
        tenant_id: body.tenant_id,
        user_reference: body.user_reference,
        verification_id: String::new(),
        documents: vec![],
        biometric: None,
        metadata: Default::default(),
    };

    let mut client = state.grpc_clients.identity.clone();
    let response = client.verify_identity(req).await.map_err(|e| {
        (
            StatusCode::BAD_GATEWAY,
            Json(ApiResponse::error(
                format!("identity verification failed: {e}"),
                request_id.clone(),
            )),
        )
    })?;

    let verification = response.into_inner().verification.ok_or_else(|| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(ApiResponse::<()>::error(
                "no verification returned",
                request_id.clone(),
            )),
        )
    })?;

    let resp = VerifyIdentityResponse {
        verification_id: verification.verification_id,
        status: format_verification_status(verification.status),
    };

    Ok(utils::json_response(resp))
}

pub async fn get_verification_status(
    State(state): State<Arc<AppState>>,
    Path(verification_id): Path<String>,
    Query(query): Query<ListVerificationsQuery>,
) -> Result<axum::response::Response, (StatusCode, Json<ApiResponse<()>>)> {
    let request_id = utils::uuid_v7();
    let tenant_id = query.user_reference.clone().unwrap_or_default();

    let req = proto::identity::GetVerificationStatusRequest {
        verification_id,
        tenant_id,
    };

    let mut client = state.grpc_clients.identity.clone();
    let response = client.get_verification_status(req).await.map_err(|e| {
        (
            StatusCode::BAD_GATEWAY,
            Json(ApiResponse::error(
                format!("failed to get verification status: {e}"),
                request_id.clone(),
            )),
        )
    })?;

    let verification = response.into_inner().verification.ok_or_else(|| {
        (
            StatusCode::NOT_FOUND,
            Json(ApiResponse::<()>::error(
                "verification not found",
                request_id.clone(),
            )),
        )
    })?;

    let resp = VerifyIdentityResponse {
        verification_id: verification.verification_id,
        status: format_verification_status(verification.status),
    };

    Ok(utils::json_response(resp))
}

pub async fn list_verifications(
    State(state): State<Arc<AppState>>,
    Query(query): Query<ListVerificationsQuery>,
) -> Result<axum::response::Response, (StatusCode, Json<ApiResponse<()>>)> {
    let request_id = utils::uuid_v7();

    let status = query
        .status
        .map(|s| match s.to_lowercase().as_str() {
            "pending" => 1,
            "in_progress" => 2,
            "completed" => 3,
            "failed" => 4,
            _ => 0,
        })
        .unwrap_or(0);

    let req = proto::identity::ListVerificationsRequest {
        tenant_id: query.user_reference.unwrap_or_default(),
        user_reference: String::new(),
        status,
        page_size: query.page_size.unwrap_or(20),
        page_token: query.page_token.unwrap_or_default(),
    };

    let mut client = state.grpc_clients.identity.clone();
    let response = client.list_verifications(req).await.map_err(|e| {
        (
            StatusCode::BAD_GATEWAY,
            Json(ApiResponse::error(
                format!("failed to list verifications: {e}"),
                request_id.clone(),
            )),
        )
    })?;

    let inner = response.into_inner();
    let verifications: Vec<VerificationSummary> = inner
        .verifications
        .into_iter()
        .map(|v| VerificationSummary {
            verification_id: v.verification_id,
            tenant_id: v.tenant_id,
            user_reference: v.user_reference,
            status: format_verification_status(v.status),
            risk_score: v.risk_score,
            created_at: format_timestamp(v.created_at),
        })
        .collect();

    Ok(utils::json_response(VerificationsListResponse {
        verifications,
        total_count: inner.total_count,
        next_page_token: if inner.next_page_token.is_empty() {
            None
        } else {
            Some(inner.next_page_token)
        },
    }))
}

pub async fn start_kyc(
    State(state): State<Arc<AppState>>,
    Json(body): Json<StartKycRequest>,
) -> Result<axum::response::Response, (StatusCode, Json<ApiResponse<()>>)> {
    let request_id = utils::uuid_v7();

    let req = proto::identity::StartKycRequest {
        tenant_id: body.tenant_id,
        user_reference: body.user_reference,
        workflow_id: String::new(),
        callback_url: body.callback_url.unwrap_or_default(),
        metadata: Default::default(),
    };

    let mut client = state.grpc_clients.identity.clone();
    let response = client.start_kyc(req).await.map_err(|e| {
        (
            StatusCode::BAD_GATEWAY,
            Json(ApiResponse::error(
                format!("failed to start KYC: {e}"),
                request_id.clone(),
            )),
        )
    })?;

    let inner = response.into_inner();
    let resp = StartKycResponse {
        verification_id: inner.verification_id,
        session_url: inner.session_url,
        status: format_verification_status(inner.status),
        expires_at: format_timestamp(inner.expires_at),
    };

    Ok(utils::created_response(resp))
}
