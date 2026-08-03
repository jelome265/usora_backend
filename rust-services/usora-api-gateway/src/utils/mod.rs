use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use hmac::{Hmac, Mac};
use serde::Serialize;
use sha2::Sha256;
use uuid::Uuid;

use crate::models::ApiResponse;

pub fn uuid_v7() -> String {
    Uuid::now_v7().to_string()
}

pub fn hmac_sign(key: &[u8], data: &[u8]) -> Vec<u8> {
    let mut mac = Hmac::<Sha256>::new_from_slice(key)
        .expect("HMAC key length valid");
    mac.update(data);
    mac.finalize().into_bytes().to_vec()
}

pub fn hmac_verify(key: &[u8], data: &[u8], signature: &[u8]) -> bool {
    let mut mac = Hmac::<Sha256>::new_from_slice(key)
        .expect("HMAC key length valid");
    mac.update(data);
    mac.verify_slice(signature).is_ok()
}

pub fn compute_hash(data: &[u8]) -> String {
    use sha2::Digest;
    let mut hasher = Sha256::new();
    hasher.update(data);
    hex::encode(hasher.finalize())
}

pub fn json_response<T: Serialize>(data: T) -> Response {
    let request_id = uuid_v7();
    let resp = ApiResponse::success(data, request_id);
    (StatusCode::OK, Json(resp)).into_response()
}

pub fn json_error(status: StatusCode, message: impl Into<String>) -> Response {
    let request_id = uuid_v7();
    let resp = ApiResponse::<()>::error(message, request_id);
    (status, Json(resp)).into_response()
}

pub fn created_response<T: Serialize>(data: T) -> Response {
    let request_id = uuid_v7();
    let resp = ApiResponse::success(data, request_id);
    (StatusCode::CREATED, Json(resp)).into_response()
}

pub fn extract_request_id(req: &axum::extract::Request) -> String {
    req.headers()
        .get("X-Request-ID")
        .and_then(|v| v.to_str().ok())
        .map(|s| s.to_string())
        .unwrap_or_else(uuid_v7)
}
