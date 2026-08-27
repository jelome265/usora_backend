use std::future::Future;
use std::num::NonZeroUsize;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};

use axum::extract::connect_info::ConnectInfo;
use axum::extract::Request;
use axum::response::{IntoResponse, Response};
use lru::LruCache;
use tokio::sync::Mutex;
use tower::{Layer, Service};

use crate::auth::AuthenticatedUser;
use crate::middleware::tenant::TenantContext;
use crate::rate_limit::token_bucket::TokenBucket;

#[derive(Clone)]
pub struct RateLimitLayer {
    default_rps: u64,
    burst_size: u64,
    window_ms: u64,
}

impl RateLimitLayer {
    pub fn new(default_rps: u64, burst_size: u64, window_ms: u64) -> Self {
        Self {
            default_rps,
            burst_size,
            window_ms,
        }
    }
}

impl<S> Layer<S> for RateLimitLayer {
    type Service = RateLimitMiddleware<S>;

    fn layer(&self, inner: S) -> Self::Service {
        RateLimitMiddleware {
            inner,
            buckets: Arc::new(Mutex::new(LruCache::new(NonZeroUsize::new(10000).unwrap()))),
            redis: None,
            default_rps: self.default_rps,
            burst_size: self.burst_size,
            window_ms: self.window_ms,
        }
    }
}

#[derive(Clone)]
pub struct RateLimitMiddleware<S> {
    inner: S,
    buckets: Arc<Mutex<LruCache<String, TokenBucket>>>,
    redis: Option<redis::aio::ConnectionManager>,
    default_rps: u64,
    burst_size: u64,
    window_ms: u64,
}

impl<S> RateLimitMiddleware<S> {
    pub fn with_redis(mut self, redis: redis::aio::ConnectionManager) -> Self {
        self.redis = Some(redis);
        self
    }
}

impl<S> Service<Request> for RateLimitMiddleware<S>
where
    S: Service<Request, Response = Response> + Send + 'static,
    S::Future: Send + 'static,
{
    type Response = Response;
    type Error = S::Error;
    type Future = Pin<Box<dyn Future<Output = Result<Self::Response, Self::Error>> + Send>>;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, req: Request) -> Self::Future {
        // SECURITY: bucket key must be built from values verified by the
        // layers that run before this one (Auth, then Tenant — see the
        // route-layer ordering in routes/mod.rs), never from raw,
        // client-supplied headers. X-Tenant-ID and X-Forwarded-For are both
        // fully attacker-controlled and were previously used directly here,
        // letting a caller get a fresh rate-limit bucket on every request
        // for the price of two spoofed headers. See
        // docs/USORA-BACKEND-ENTERPRISE-AUDIT-2026-08-16.md finding C6.
        let tenant_id = req
            .extensions()
            .get::<TenantContext>()
            .map(|t| t.tenant_id.clone())
            .unwrap_or_else(|| "unauthenticated".to_string());

        // Prefer the verified caller identity (JWT subject) over IP when
        // available, so a single abusive credential can't spread its
        // requests across many source IPs to dodge the limit; fall back to
        // the real TCP peer address (never a client-supplied header) for
        // unauthenticated requests.
        let client_key = req
            .extensions()
            .get::<AuthenticatedUser>()
            .map(|u| format!("user:{}", u.sub))
            .or_else(|| {
                req.extensions()
                    .get::<ConnectInfo<std::net::SocketAddr>>()
                    .map(|ci| format!("ip:{}", ci.0.ip()))
            })
            .unwrap_or_else(|| "unknown".to_string());

        let endpoint = req.uri().path().to_string();
        let bucket_key = format!("{tenant_id}:{client_key}:{endpoint}");

        let buckets = self.buckets.clone();
        let redis = self.redis.clone();
        let default_rps = self.default_rps;
        let burst_size = self.burst_size;
        let window_ms = self.window_ms;

        let inner = self.inner.call(req);

        Box::pin(async move {
            let allowed = if let Some(ref redis_conn) = redis {
                let key = format!("rl:{bucket_key}");
                redis_rate_limit_check(redis_conn, &key, default_rps, burst_size).await
            } else {
                let mut buckets = buckets.lock().await;
                let bucket = buckets.get_or_insert_mut(bucket_key.clone(), || {
                    TokenBucket::new(default_rps, burst_size, window_ms)
                });
                bucket.consume_async(1).await
            };

            if !allowed {
                let resp = (
                    axum::http::StatusCode::TOO_MANY_REQUESTS,
                    [("Retry-After", "1")],
                )
                    .into_response();
                return Ok(resp);
            }

            inner.await
        })
    }
}

async fn redis_rate_limit_check(
    conn: &redis::aio::ConnectionManager,
    key: &str,
    max_rps: u64,
    burst: u64,
) -> bool {
    let mut conn = conn.clone();
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64;

    // Two-tier enforcement, both must pass:
    //   1. a 1s window capped at `burst` — absorbs short spikes
    //   2. a 60s window capped at `max_rps * 60` — enforces the intended
    //      sustained steady-state rate, which the old implementation
    //      ignored entirely (max_rps was accepted as a parameter and never
    //      used). Without tier 2, a low max_rps configured alongside a high
    //      burst allowed sustained traffic at the burst rate indefinitely.
    // This is still an approximate fixed-window counter (not a true
    // sliding window), which can allow up to ~2x the configured rate right
    // at a window boundary — acceptable for abuse resistance, not for
    // billing-grade metering. A proper sliding-window/GCRA implementation
    // is tracked as a follow-up (see H5 in the audit doc).
    let burst_window_ms = 1_000u64;
    let burst_window_key = format!("{key}:b:{}", now / burst_window_ms);

    let sustained_window_ms = 60_000u64;
    let sustained_window_key = format!("{key}:s:{}", now / sustained_window_ms);
    let sustained_cap = max_rps.saturating_mul(60).max(1);

    let burst_count: u64 = redis::cmd("GET")
        .arg(&burst_window_key)
        .query_async(&mut conn)
        .await
        .unwrap_or(0);
    if burst_count >= burst {
        return false;
    }

    let sustained_count: u64 = redis::cmd("GET")
        .arg(&sustained_window_key)
        .query_async(&mut conn)
        .await
        .unwrap_or(0);
    if sustained_count >= sustained_cap {
        return false;
    }

    let _: Result<(), _> = redis::cmd("INCR")
        .arg(&burst_window_key)
        .query_async(&mut conn)
        .await;
    let _: Result<(), _> = redis::cmd("EXPIRE")
        .arg(&burst_window_key)
        .arg(2u64)
        .query_async(&mut conn)
        .await;

    let _: Result<(), _> = redis::cmd("INCR")
        .arg(&sustained_window_key)
        .query_async(&mut conn)
        .await;
    let _: Result<(), _> = redis::cmd("EXPIRE")
        .arg(&sustained_window_key)
        .arg(120u64)
        .query_async(&mut conn)
        .await;

    true
}
