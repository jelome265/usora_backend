use crate::auth::{require_service_auth, AuthState};
use crate::generated::usora::document::v1::{
    CrossReferenceRequest, DocumentAnalysisRequest, ForgeryDetectionRequest,
    MetadataExtractionRequest, SecurityFeaturesRequest, TemplateRequest,
};
use crate::grpc::DocumentAnalysisServiceImpl;
use axum::{
    extract::{Path, State},
    http::StatusCode,
    middleware,
    routing::get,
    Json,
};
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tonic::Request;

#[derive(Clone)]
pub struct AppState {
    pub service: Arc<DocumentAnalysisServiceImpl>,
}

pub fn router(state: AppState, auth: Arc<AuthState>) -> axum::Router {
    // SECURITY: every route under /api/v1/documents performs
    // document/biometric forensic analysis and previously had no
    // authentication at all — see auth.rs for the full finding. Those
    // routes are nested separately so `require_service_auth` wraps only
    // them; /metrics stays reachable for the cluster's Prometheus scraper
    // without a bearer token (it's restricted at the network layer
    // instead — see this chart's NetworkPolicy).
    let authenticated_routes = axum::Router::new()
        .route(
            "/api/v1/documents/analyze",
            axum::routing::post(analyze_document),
        )
        .route(
            "/api/v1/documents/forgery-check",
            axum::routing::post(forgery_check),
        )
        .route(
            "/api/v1/documents/metadata",
            axum::routing::post(extract_metadata),
        )
        .route(
            "/api/v1/documents/cross-reference",
            axum::routing::post(cross_reference),
        )
        .route(
            "/api/v1/documents/security-features",
            axum::routing::post(security_features),
        )
        .route(
            "/api/v1/documents/templates/{country}/{doc_type}",
            axum::routing::get(get_template),
        )
        .route_layer(middleware::from_fn_with_state(auth, require_service_auth))
        .with_state(state);

    axum::Router::new()
        .route("/metrics", get(metrics_handler))
        .merge(authenticated_routes)
        .layer(middleware::from_fn(track_rest_metrics))
}

async fn track_rest_metrics(
    req: axum::extract::Request,
    next: middleware::Next,
) -> axum::response::Response {
    let route = req.uri().path().to_string();
    let response = next.run(req).await;
    let status_class = match response.status().as_u16() {
        200..=299 => "2xx",
        300..=399 => "3xx",
        400..=499 => "4xx",
        500..=599 => "5xx",
        _ => "other",
    };
    crate::metrics::REST_REQUESTS_TOTAL
        .with_label_values(&[&route, status_class])
        .inc();
    response
}

async fn metrics_handler() -> Result<String, StatusCode> {
    crate::metrics::render().map_err(|e| {
        tracing::error!("failed to render metrics: {e}");
        StatusCode::INTERNAL_SERVER_ERROR
    })
}

#[derive(Debug, Serialize, Deserialize)]
pub struct AnalyzeRequest {
    pub document_id: String,
    pub tenant_id: String,
    pub verification_id: String,
    #[serde(with = "base64_serde")]
    pub document_image: Vec<u8>,
    pub document_type: String,
    pub country_code: String,
    pub run_forensics: Option<bool>,
    pub extract_metadata: Option<bool>,
}

async fn analyze_document(
    State(state): State<AppState>,
    Json(req): Json<AnalyzeRequest>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let grpc_req = DocumentAnalysisRequest {
        document_id: req.document_id,
        tenant_id: req.tenant_id,
        verification_id: req.verification_id,
        document_image: req.document_image,
        document_type: req.document_type,
        country_code: req.country_code,
        run_forensics: req.run_forensics.unwrap_or(true),
        extract_metadata: req.extract_metadata.unwrap_or(true),
        options: None,
    };

    match state.service.analyze_document(Request::new(grpc_req)).await {
        Ok(resp) => {
            let inner = resp.into_inner();
            Ok(Json(serde_json::json!({
                "document_id": inner.document_id,
                "status": inner.status,
                "overall_authenticity_score": inner.overall_authenticity_score,
                "processing_time_ms": inner.processing_time_ms,
                "warnings": inner.warnings,
            })))
        }
        Err(e) => {
            tracing::error!("analyze_document failed: {}", e);
            Err(StatusCode::INTERNAL_SERVER_ERROR)
        }
    }
}

#[derive(Debug, Serialize, Deserialize)]
pub struct ForgeryCheckRequest {
    pub document_id: String,
    pub tenant_id: String,
    #[serde(with = "base64_serde")]
    pub document_image: Vec<u8>,
    pub document_type: String,
    pub deep_analysis: Option<bool>,
}

async fn forgery_check(
    State(state): State<AppState>,
    Json(req): Json<ForgeryCheckRequest>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let grpc_req = ForgeryDetectionRequest {
        document_id: req.document_id,
        tenant_id: req.tenant_id,
        document_image: req.document_image,
        document_type: req.document_type,
        deep_analysis: req.deep_analysis.unwrap_or(false),
        options: None,
    };

    match state.service.detect_forgery(Request::new(grpc_req)).await {
        Ok(resp) => {
            let inner = resp.into_inner();
            Ok(Json(serde_json::json!({
                "document_id": inner.document_id,
                "is_forgery": inner.is_forgery,
                "forgery_confidence": inner.forgery_confidence,
                "forgery_indicators": inner.forgery_indicators,
                "model_scores": inner.model_scores,
            })))
        }
        Err(e) => {
            tracing::error!("detect_forgery failed: {}", e);
            Err(StatusCode::INTERNAL_SERVER_ERROR)
        }
    }
}

#[derive(Debug, Serialize, Deserialize)]
pub struct MetadataRequest {
    pub document_id: String,
    pub tenant_id: String,
    #[serde(with = "base64_serde")]
    pub document_image: Vec<u8>,
    pub strip_exif: Option<bool>,
}

async fn extract_metadata(
    State(state): State<AppState>,
    Json(req): Json<MetadataRequest>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let grpc_req = MetadataExtractionRequest {
        document_id: req.document_id,
        tenant_id: req.tenant_id,
        document_image: req.document_image,
        strip_exif: req.strip_exif.unwrap_or(true),
    };

    match state.service.extract_metadata(Request::new(grpc_req)).await {
        Ok(resp) => {
            let inner = resp.into_inner();
            Ok(Json(serde_json::json!({
                "document_id": inner.document_id,
                "file_format": inner.file_format,
                "image_width": inner.image_width,
                "image_height": inner.image_height,
                "file_size_kb": inner.file_size_kb,
                "color_space": inner.color_space,
                "dpi": inner.dpi,
                "exif_data": inner.exif_data,
            })))
        }
        Err(e) => {
            tracing::error!("extract_metadata failed: {}", e);
            Err(StatusCode::INTERNAL_SERVER_ERROR)
        }
    }
}

#[derive(Debug, Serialize, Deserialize)]
pub struct CrossReferenceRequest {
    pub document_id: String,
    pub tenant_id: String,
    pub extracted_data: Option<serde_json::Value>,
    #[serde(with = "base64_serde")]
    pub document_image: Vec<u8>,
}

async fn cross_reference(
    State(state): State<AppState>,
    Json(req): Json<CrossReferenceRequest>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let grpc_req = CrossReferenceRequest {
        document_id: req.document_id,
        tenant_id: req.tenant_id,
        extracted_data: req.extracted_data.map(|_| Default::default()),
        document_image: req.document_image,
    };

    match state
        .service
        .validate_cross_reference(Request::new(grpc_req))
        .await
    {
        Ok(resp) => {
            let inner = resp.into_inner();
            Ok(Json(serde_json::json!({
                "document_id": inner.document_id,
                "all_fields_consistent": inner.all_fields_consistent,
                "field_consistencies": inner.field_consistencies,
                "overall_consistency_score": inner.overall_consistency_score,
            })))
        }
        Err(e) => {
            tracing::error!("validate_cross_reference failed: {}", e);
            Err(StatusCode::INTERNAL_SERVER_ERROR)
        }
    }
}

#[derive(Debug, Serialize, Deserialize)]
pub struct SecurityFeaturesRequest {
    pub document_id: String,
    pub tenant_id: String,
    #[serde(with = "base64_serde")]
    pub document_image: Vec<u8>,
    pub document_type: String,
    pub check_uv: Option<bool>,
    pub check_ir: Option<bool>,
    pub check_hologram: Option<bool>,
    pub check_microprint: Option<bool>,
    pub check_watermark: Option<bool>,
}

async fn security_features(
    State(state): State<AppState>,
    Json(req): Json<SecurityFeaturesRequest>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let grpc_req = SecurityFeaturesRequest {
        document_id: req.document_id,
        tenant_id: req.tenant_id,
        document_image: req.document_image,
        document_type: req.document_type,
        check_uv: req.check_uv.unwrap_or(true),
        check_ir: req.check_ir.unwrap_or(true),
        check_hologram: req.check_hologram.unwrap_or(true),
        check_microprint: req.check_microprint.unwrap_or(true),
        check_watermark: req.check_watermark.unwrap_or(true),
    };

    match state
        .service
        .check_security_features(Request::new(grpc_req))
        .await
    {
        Ok(resp) => {
            let inner = resp.into_inner();
            Ok(Json(serde_json::json!({
                "document_id": inner.document_id,
                "all_features_present": inner.all_features_present,
                "overall_security_score": inner.overall_security_score,
            })))
        }
        Err(e) => {
            tracing::error!("check_security_features failed: {}", e);
            Err(StatusCode::INTERNAL_SERVER_ERROR)
        }
    }
}

async fn get_template(
    State(state): State<AppState>,
    Path((country, doc_type)): Path<(String, String)>,
) -> Result<Json<serde_json::Value>, StatusCode> {
    let grpc_req = TemplateRequest {
        tenant_id: String::new(),
        country_code: country,
        document_type: doc_type,
    };

    match state
        .service
        .get_document_template(Request::new(grpc_req))
        .await
    {
        Ok(resp) => {
            let inner = resp.into_inner();
            Ok(Json(serde_json::json!({
                "template_id": inner.template_id,
                "country_code": inner.country_code,
                "document_type": inner.document_type,
                "layout": inner.layout,
                "fields": inner.fields,
                "version": inner.version,
            })))
        }
        Err(e) => {
            tracing::error!("get_document_template failed: {}", e);
            Err(StatusCode::INTERNAL_SERVER_ERROR)
        }
    }
}

mod base64_serde {
    use serde::{Deserialize, Deserializer, Serializer};
    // PRE-EXISTING COMPILE-BLOCKER, found and fixed alongside the auth/
    // metrics work in this file: Cargo.toml declares base64 = "0.22", but
    // the free functions base64::encode/base64::decode used here were
    // removed as of base64 0.21 in favor of the Engine trait. This module
    // would not have compiled as checked in.
    use base64::Engine;

    pub fn serialize<S>(bytes: &[u8], serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        serializer.serialize_str(&base64::engine::general_purpose::STANDARD.encode(bytes))
    }

    pub fn deserialize<'de, D>(deserializer: D) -> Result<Vec<u8>, D::Error>
    where
        D: Deserializer<'de>,
    {
        let s = String::deserialize(deserializer)?;
        base64::engine::general_purpose::STANDARD
            .decode(&s)
            .map_err(serde::de::Error::custom)
    }
}
