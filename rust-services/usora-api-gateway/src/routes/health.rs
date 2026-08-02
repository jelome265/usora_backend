use std::sync::OnceLock;
use std::time::Instant;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;
use prometheus::{Counter, Encoder, Histogram, TextEncoder, register_counter, register_histogram};
use serde_json::json;
use std::sync::Arc;

use crate::AppState;
use crate::utils;

fn start_time() -> &'static Instant {
    static START: OnceLock<Instant> = OnceLock::new();
    START.get_or_init(|| Instant::now())
}

fn health_counter() -> &'static Counter {
    static COUNTER: OnceLock<Counter> = OnceLock::new();
    COUNTER.get_or_init(|| {
        register_counter!("gateway_health_checks_total", "Total health check requests")
            .expect("failed to register health counter")
    })
}

fn health_latency() -> &'static Histogram {
    static LATENCY: OnceLock<Histogram> = OnceLock::new();
    LATENCY.get_or_init(|| {
        register_histogram!("gateway_health_latency_seconds", "Health check latency")
            .expect("failed to register health latency histogram")
    })
}

pub async fn health_check(
    State(state): State<Arc<AppState>>,
) -> impl IntoResponse {
    let start = Instant::now();
    health_counter().inc();

    let upstream_healthy = state.grpc_clients.check_health().await;

    let uptime = start_time().elapsed().as_secs_f64();

    let mut checks = std::collections::HashMap::new();
    checks.insert(
        "upstream".to_string(),
        if upstream_healthy {
            "healthy".to_string()
        } else {
            "unhealthy".to_string()
        },
    );
    checks.insert(
        "redis".to_string(),
        if state.redis.is_some() {
            "healthy".to_string()
        } else {
            "disabled".to_string()
        },
    );

    let overall = if upstream_healthy { "healthy" } else { "degraded" };

    health_latency().observe(start.elapsed().as_secs_f64());

    let response = json!({
        "status": overall,
        "version": env!("CARGO_PKG_VERSION"),
        "uptime_seconds": uptime,
        "checks": checks,
        "request_id": utils::uuid_v7(),
    });

    let status = if upstream_healthy {
        StatusCode::OK
    } else {
        StatusCode::SERVICE_UNAVAILABLE
    };

    (status, Json(response))
}

pub async fn metrics_handler() -> impl IntoResponse {
    let encoder = TextEncoder::new();
    let metric_families = prometheus::gather();
    let mut buffer = Vec::new();
    if let Err(e) = encoder.encode(&metric_families, &mut buffer) {
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("failed to encode metrics: {e}"),
        );
    }
    (
        StatusCode::OK,
        String::from_utf8_lossy(&buffer).to_string(),
    )
}
