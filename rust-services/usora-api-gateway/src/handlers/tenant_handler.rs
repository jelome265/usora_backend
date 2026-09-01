use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::Json;
use std::sync::Arc;

use crate::models::*;
use crate::proto;
use crate::utils;
use crate::AppState;

fn format_tenant_status(status: i32) -> String {
    proto::tenant::TenantStatus::try_from(status)
        .map(|s| format!("{:?}", s))
        .unwrap_or_else(|_| "UNKNOWN".to_string())
        .to_lowercase()
}

fn format_timestamp(ts: Option<prost_types::Timestamp>) -> String {
    ts.map(|t| format!("{}.{:09}", t.seconds, t.nanos))
        .unwrap_or_default()
}

pub async fn get_tenant(
    State(state): State<Arc<AppState>>,
    Path(tenant_id): Path<String>,
) -> Result<axum::response::Response, (StatusCode, Json<ApiResponse<()>>)> {
    let request_id = utils::uuid_v7();

    let req = proto::tenant::GetTenantConfigRequest {
        tenant_id: tenant_id.clone(),
    };

    let mut client = state.grpc_clients.tenant.clone();
    let response = client.get_tenant_config(req).await.map_err(|e| {
        (
            StatusCode::BAD_GATEWAY,
            Json(ApiResponse::error(
                format!("failed to get tenant: {e}"),
                request_id.clone(),
            )),
        )
    })?;

    let tenant = response.into_inner().tenant.ok_or_else(|| {
        (
            StatusCode::NOT_FOUND,
            Json(ApiResponse::<()>::error(
                format!("tenant {tenant_id} not found"),
                request_id.clone(),
            )),
        )
    })?;

    let resp = TenantConfigResponse {
        tenant_id: tenant.tenant_id,
        name: tenant.name,
        domain: tenant.domain,
        status: format_tenant_status(tenant.status),
        settings: tenant.settings,
        features: tenant.features,
        created_at: format_timestamp(tenant.created_at),
    };

    Ok(utils::json_response(resp))
}

pub async fn create_tenant(
    State(state): State<Arc<AppState>>,
    Json(body): Json<CreateTenantRequest>,
) -> Result<axum::response::Response, (StatusCode, Json<ApiResponse<()>>)> {
    let request_id = utils::uuid_v7();

    let req = proto::tenant::ProvisionTenantRequest {
        name: body.name,
        domain: body.domain,
        settings: body.settings,
        features: body.features,
        max_users: 0,
        max_verifications: 0,
        metadata: None,
    };

    let mut client = state.grpc_clients.tenant.clone();
    let response = client.provision_tenant(req).await.map_err(|e| {
        (
            StatusCode::BAD_GATEWAY,
            Json(ApiResponse::error(
                format!("failed to create tenant: {e}"),
                request_id.clone(),
            )),
        )
    })?;

    let tenant = response.into_inner().tenant.ok_or_else(|| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(ApiResponse::<()>::error(
                "no tenant returned from provisioning",
                request_id.clone(),
            )),
        )
    })?;

    let resp = TenantConfigResponse {
        tenant_id: tenant.tenant_id,
        name: tenant.name,
        domain: tenant.domain,
        status: format_tenant_status(tenant.status),
        settings: tenant.settings,
        features: tenant.features,
        created_at: format_timestamp(tenant.created_at),
    };

    Ok(utils::created_response(resp))
}

pub async fn update_tenant(
    State(state): State<Arc<AppState>>,
    Path(tenant_id): Path<String>,
    Json(body): Json<UpdateTenantRequest>,
) -> Result<axum::response::Response, (StatusCode, Json<ApiResponse<()>>)> {
    let request_id = utils::uuid_v7();

    let req = proto::tenant::UpdateTenantConfigRequest {
        tenant_id,
        name: body.name.unwrap_or_default(),
        domain: body.domain.unwrap_or_default(),
        settings: body.settings.unwrap_or_default(),
        features: body.features.unwrap_or_default(),
        max_users: 0,
        max_verifications: 0,
        metadata: None,
    };

    let mut client = state.grpc_clients.tenant.clone();
    let response = client.update_tenant_config(req).await.map_err(|e| {
        (
            StatusCode::BAD_GATEWAY,
            Json(ApiResponse::error(
                format!("failed to update tenant: {e}"),
                request_id.clone(),
            )),
        )
    })?;

    let tenant = response.into_inner().tenant.ok_or_else(|| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(ApiResponse::<()>::error(
                "no tenant returned from update",
                request_id.clone(),
            )),
        )
    })?;

    let resp = TenantConfigResponse {
        tenant_id: tenant.tenant_id,
        name: tenant.name,
        domain: tenant.domain,
        status: format_tenant_status(tenant.status),
        settings: tenant.settings,
        features: tenant.features,
        created_at: format_timestamp(tenant.created_at),
    };

    Ok(utils::json_response(resp))
}

pub async fn delete_tenant(
    State(state): State<Arc<AppState>>,
    Path(tenant_id): Path<String>,
) -> Result<axum::response::Response, (StatusCode, Json<ApiResponse<()>>)> {
    let request_id = utils::uuid_v7();

    let req = proto::tenant::DeleteTenantRequest { tenant_id };

    let mut client = state.grpc_clients.tenant.clone();
    let response = client.delete_tenant(req).await.map_err(|e| {
        (
            StatusCode::BAD_GATEWAY,
            Json(ApiResponse::error(
                format!("failed to delete tenant: {e}"),
                request_id.clone(),
            )),
        )
    })?;

    let inner = response.into_inner();
    if inner.success {
        Ok(utils::json_response(serde_json::json!({"deleted": true})))
    } else {
        Err((
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(ApiResponse::error("failed to delete tenant", request_id)),
        ))
    }
}
