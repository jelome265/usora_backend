use std::sync::Arc;
use tower::ServiceExt;
use axum::body::Body;
use axum::http::{Request, StatusCode, Method};
use http_body_util::BodyExt;
use serde_json::Value;

use usora_api_gateway::config::Config;
use usora_api_gateway::routes;
use usora_api_gateway::middleware::tenant::TenantContext;
use usora_api_gateway::auth::jwt::JwtClaims;
use usora_api_gateway::auth::{AuthenticatedUser, AuthMethod};
use usora_api_gateway::rate_limit::token_bucket::TokenBucket;
use usora_api_gateway::utils::{hmac_sign, hmac_verify};

#[tokio::test]
async fn test_health_endpoint() {
    let config = Config::default();
    let state = Arc::new(
        usora_api_gateway::AppState::new(config)
            .await
            .expect("failed to create AppState"),
    );

    let app = routes::create_router(state);

    let response = app
        .oneshot(
            Request::builder()
                .uri("/health")
                .method(Method::GET)
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let collected = response.into_body().collect().await.unwrap();
    let body = collected.to_bytes();
    let json: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["status"], "degraded");
    assert!(json["uptime_seconds"].as_f64().is_some());
    assert!(json["request_id"].as_str().unwrap().len() > 0);
}

#[tokio::test]
async fn test_404_on_unknown_route() {
    let config = Config::default();
    let state = Arc::new(
        usora_api_gateway::AppState::new(config)
            .await
            .expect("failed to create AppState"),
    );

    let app = routes::create_router(state);

    let response = app
        .oneshot(
            Request::builder()
                .uri("/api/v1/nonexistent")
                .method(Method::GET)
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn test_token_bucket_consume() {
    let bucket = TokenBucket::new(10, 20, 1000);

    for _ in 0..20 {
        assert!(bucket.consume_async(1).await);
    }

    assert!(!bucket.consume_async(1).await);
}

#[tokio::test]
async fn test_token_bucket_refill() {
    let bucket = TokenBucket::new(1000, 100, 1000);

    for _ in 0..100 {
        assert!(bucket.consume_async(1).await);
    }

    assert!(!bucket.consume_async(1).await);

    tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;

    assert!(bucket.consume_async(1).await);
}

#[tokio::test]
async fn test_jwt_claims_serde() {
    let claims = JwtClaims {
        sub: "user123".to_string(),
        tid: Some("tenant-abc".to_string()),
        roles: vec!["admin".to_string()],
        permissions: vec!["read".to_string()],
        exp: 9999999999,
        iat: 0,
        jti: Some("jti-123".to_string()),
        iss: Some("usora".to_string()),
        aud: Some(vec!["api".to_string()]),
    };

    let serialized = serde_json::to_string(&claims).unwrap();
    let deserialized: JwtClaims = serde_json::from_str(&serialized).unwrap();

    assert_eq!(deserialized.sub, "user123");
    assert_eq!(deserialized.tid, Some("tenant-abc".to_string()));
    assert_eq!(deserialized.roles, vec!["admin".to_string()]);
}

#[tokio::test]
async fn test_authenticated_user_has_role() {
    let user = AuthenticatedUser {
        sub: "user123".to_string(),
        tenant_id: Some("tenant-abc".to_string()),
        roles: vec!["admin".to_string(), "operator".to_string()],
        permissions: vec!["read".to_string(), "write".to_string()],
        auth_method: AuthMethod::Jwt,
    };

    assert!(user.has_role("admin"));
    assert!(!user.has_role("superadmin"));
    assert!(user.has_permission("write"));
    assert!(!user.has_permission("delete"));
}

#[tokio::test]
async fn test_tenant_context() {
    let ctx = TenantContext::new("tenant-xyz".to_string());
    assert_eq!(ctx.tenant_id, "tenant-xyz");
}

#[tokio::test]
async fn test_uuid_v7_format() {
    let id = uuid::Uuid::now_v7();
    let id_str = id.to_string();

    assert_eq!(id_str.len(), 36);
    assert_eq!(id.get_version_num(), 7);
}

#[tokio::test]
async fn test_hmac_roundtrip() {
    let key = b"test_key_32_bytes_long_1234567890abc";
    let data = b"important_data";

    let sig = hmac_sign(key, data);
    assert!(hmac_verify(key, data, &sig));

    let tampered = b"tampered_data";
    assert!(!hmac_verify(key, tampered, &sig));
}

#[tokio::test]
async fn test_metrics_endpoint() {
    let config = Config::default();
    let state = Arc::new(
        usora_api_gateway::AppState::new(config)
            .await
            .expect("failed to create AppState"),
    );

    let app = routes::create_router(state);

    let response = app
        .oneshot(
            Request::builder()
                .uri("/metrics")
                .method(Method::GET)
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let collected = response.into_body().collect().await.unwrap();
    let body = collected.to_bytes();
    let body_str = String::from_utf8_lossy(&body);
    assert!(body_str.contains("gateway_health_checks_total") || body_str.starts_with('#'));
}

#[tokio::test]
async fn test_config_defaults() {
    let config = Config::default();

    assert_eq!(config.bind_address, "0.0.0.0:8443");
    assert_eq!(config.grpc_bind_address, "0.0.0.0:9090");
    assert_eq!(config.rate_limiting.default_rps, 100);
    assert_eq!(config.rate_limiting.burst_size, 200);
}

#[tokio::test]
async fn test_pkce_verification() {
    use usora_api_gateway::auth::oauth::{generate_pkce_pair, verify_pkce};

    let pkce = generate_pkce_pair();
    assert_eq!(pkce.challenge_method, "S256");
    assert!(pkce.verifier.len() >= 43);
    assert!(verify_pkce(&pkce.verifier, &pkce.challenge, "S256"));
    assert!(!verify_pkce("wrong_verifier", &pkce.challenge, "S256"));
    // "plain" PKCE is deliberately never accepted (see verify_pkce's
    // security comment) -- OAuth 2.1 disallows it since it provides no
    // protection against authorization-code interception. This must
    // hold even when verifier and challenge match exactly.
    assert!(!verify_pkce(&pkce.verifier, &pkce.verifier, "plain"));
}
