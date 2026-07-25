pub mod api_v1;
pub mod health;

use std::sync::Arc;
use axum::Router;
use axum::routing::get;
use tower_http::cors::CorsLayer;
use tower_http::normalize_path::NormalizePathLayer;
use tower_http::limit::RequestBodyLimitLayer;
use tower_http::trace::TraceLayer;
use tower_http::set_header::SetResponseHeaderLayer;
use axum::http::HeaderValue;

use crate::AppState;
use crate::middleware::auth::AuthLayer;
use crate::middleware::rate_limit::RateLimitLayer;
use crate::middleware::tenant::TenantLayer;

pub fn create_router(state: Arc<AppState>) -> Router<Arc<AppState>> {
    let api_routes = api_v1::routes();

    let rate_cfg = &state.config.rate_limiting;

    let app = Router::new()
        .nest("/api/v1", api_routes)
        .route("/health", get(health::health_check))
        .route("/metrics", get(health::metrics_handler))
        .layer(TenantLayer::new())
        .layer(AuthLayer::new())
        .layer(RateLimitLayer::new(rate_cfg.default_rps, rate_cfg.burst_size, rate_cfg.window_ms))
        .layer(TraceLayer::new_for_http())
        .layer(NormalizePathLayer::normalize_path_trailing_slash())
        .layer(RequestBodyLimitLayer::new(50 * 1024 * 1024))
        .layer(
            SetResponseHeaderLayer::overriding(
                axum::http::header::SERVER,
                HeaderValue::from_static("usora-api-gateway/0.1.0"),
            ),
        )
        .layer(
            CorsLayer::new()
                .allow_origin(tower_http::cors::Any)
                .allow_methods(tower_http::cors::Any)
                .allow_headers(tower_http::cors::Any),
        )
        .with_state(state);

    app
}
