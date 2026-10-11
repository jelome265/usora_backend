pub mod api_v1;
pub mod health;

use std::sync::Arc;
use axum::Router;
use axum::routing::get;
use tower_http::cors::{AllowOrigin, CorsLayer};
use tower_http::normalize_path::NormalizePathLayer;
use tower_http::limit::RequestBodyLimitLayer;
use tower_http::trace::TraceLayer;
use tower_http::set_header::SetResponseHeaderLayer;
use axum::http::{HeaderValue, Method, header};

use crate::AppState;
use crate::middleware::auth::AuthLayer;
use crate::middleware::rate_limit::RateLimitLayer;
use crate::middleware::tenant::TenantLayer;

pub fn create_router(state: Arc<AppState>) -> Router<()> {
    let api_routes = api_v1::routes();

    let rate_cfg = &state.config.rate_limiting;
    let cors_layer = build_cors_layer(&state.config.cors.allowed_origins);

    let app = Router::new()
        .nest("/api/v1", api_routes)
        .route("/health", get(health::health_check))
        .route("/metrics", get(health::metrics_handler))
        // SECURITY: ordering matters. route_layer wraps outer-to-inner in
        // the reverse of call order below (the last one added runs first),
        // so this list executes as: RateLimit(innermost, runs LAST) <-
        // Tenant <- Auth(outermost, runs FIRST). That means Auth resolves
        // and verifies the caller, Tenant resolves the verified tenant from
        // that, and only then does RateLimit run — so it can key on the
        // *verified* tenant/user identity instead of raw, spoofable request
        // headers. Do not reorder without re-reading this comment; the
        // original ordering (RateLimit first) was finding C6 in
        // docs/USORA-BACKEND-ENTERPRISE-AUDIT-2026-08-16.md.
        .route_layer({
            let layer = RateLimitLayer::new(
                rate_cfg.default_rps,
                rate_cfg.burst_size,
                rate_cfg.window_ms,
                rate_cfg.local_fallback_divisor,
            );
            // F-006: actually wire in the shared Redis connection so rate
            // limiting is coordinated across replicas -- previously this
            // was never done anywhere, so the layer silently ran fully
            // local (per-pod) rate limiting at all times, not just when
            // Redis was genuinely unavailable.
            match &state.redis {
                Some(redis) => layer.with_redis(redis.clone()),
                None => layer,
            }
        })
        .route_layer(TenantLayer::new())
        .route_layer(AuthLayer::new(state.jwt_validator.clone()))
        .layer(TraceLayer::new_for_http())
        .layer(NormalizePathLayer::trim_trailing_slash())
        .layer(RequestBodyLimitLayer::new(50 * 1024 * 1024))
        .layer(
            SetResponseHeaderLayer::overriding(
                axum::http::header::SERVER,
                HeaderValue::from_static("usora-api-gateway"),
            ),
        )
        .layer(cors_layer)
        .with_state(state);

    app
}

/// SECURITY: builds a strict, explicit CORS policy from configuration.
/// Never falls back to `Any` for origins — an empty/misconfigured allowlist
/// means no cross-origin browser access is permitted, not "allow everyone".
/// See docs/USORA-BACKEND-ENTERPRISE-AUDIT-2026-08-16.md finding C5.
fn build_cors_layer(allowed_origins: &[String]) -> CorsLayer {
    if allowed_origins.is_empty() {
        tracing::warn!(
            "CORS_ALLOWED_ORIGINS is not set — no cross-origin browser access will be permitted. \
             Server-to-server callers are unaffected."
        );
    }

    let origins: Vec<HeaderValue> = allowed_origins
        .iter()
        .filter_map(|origin| match HeaderValue::from_str(origin) {
            Ok(v) => Some(v),
            Err(e) => {
                tracing::error!("Ignoring invalid CORS origin '{origin}': {e}");
                None
            }
        })
        .collect();

    CorsLayer::new()
        .allow_origin(AllowOrigin::list(origins))
        .allow_methods([Method::GET, Method::POST, Method::PUT, Method::PATCH, Method::DELETE])
        .allow_headers([header::AUTHORIZATION, header::CONTENT_TYPE, header::ACCEPT])
        .max_age(std::time::Duration::from_secs(600))
}
