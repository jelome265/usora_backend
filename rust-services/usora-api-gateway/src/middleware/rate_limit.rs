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
    local_fallback_divisor: u64,
    // SECURITY/AVAILABILITY (F-006): previously `layer()` below always
    // hardcoded `redis: None` on the constructed middleware, and nothing
    // in this codebase ever called `RateLimitMiddleware::with_redis` --
    // meaning the Redis-backed, cross-replica rate limiter was complete
    // dead code. Every gateway pod, at all times (not just during a
    // Redis outage), was enforcing rate limits purely locally, so the
    // "N replicas => up to Nx the intended budget" multiplication the
    // audit describes as a *degraded-mode* risk was actually true all the
    // time. This field is populated from AppState::new's `redis` and
    // threaded through so `layer()` can actually wire it up.
    redis: Option<redis::aio::ConnectionManager>,
}

impl RateLimitLayer {
    pub fn new(default_rps: u64, burst_size: u64, window_ms: u64, local_fallback_divisor: u64) -> Self {
        Self {
            default_rps,
            burst_size,
            window_ms,
            local_fallback_divisor: local_fallback_divisor.max(1),
            redis: None,
        }
    }

    /// Wire in the shared Redis connection so rate limiting is actually
    /// coordinated across replicas instead of silently falling back to
    /// the per-pod local limiter on every single request. See the `redis`
    /// field docs above for why this matters.
    pub fn with_redis(mut self, redis: redis::aio::ConnectionManager) -> Self {
        self.redis = Some(redis);
        self
    }
}

impl<S> Layer<S> for RateLimitLayer {
    type Service = RateLimitMiddleware<S>;

    fn layer(&self, inner: S) -> Self::Service {
        RateLimitMiddleware {
            inner,
            buckets: Arc::new(Mutex::new(LruCache::new(NonZeroUsize::new(10000).unwrap()))),
            redis: self.redis.clone(),
            default_rps: self.default_rps,
            burst_size: self.burst_size,
            window_ms: self.window_ms,
            local_fallback_divisor: self.local_fallback_divisor,
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
    local_fallback_divisor: u64,
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
        let local_fallback_divisor = self.local_fallback_divisor;

        let inner = self.inner.call(req);

        Box::pin(async move {
            let allowed = if let Some(ref redis_conn) = redis {
                let key = format!("rl:{bucket_key}");
                match redis_rate_limit_check(redis_conn, &key, default_rps, burst_size).await {
                    RedisRateLimitOutcome::Decided(allowed) => allowed,
                    RedisRateLimitOutcome::Unavailable => {
                        // SECURITY (F-006): previously a Redis command error
                        // here (as opposed to Redis simply not being
                        // configured) was swallowed via `.unwrap_or(0)` and
                        // treated as "count is zero", which unconditionally
                        // ALLOWED the request -- worse than the documented
                        // finding, since it wasn't even a per-pod local
                        // fallback, it was no rate limiting at all for as
                        // long as the Redis error persisted. A genuine
                        // Redis failure now falls back to the same
                        // conservative, deliberately-scaled-down per-pod
                        // local bucket used when Redis was never configured
                        // in the first place (see local_fallback_divisor).
                        rate_limit_degraded_total().inc();
                        local_fallback_check(
                            &buckets, &bucket_key, default_rps, burst_size, window_ms,
                            local_fallback_divisor,
                        ).await
                    }
                }
            } else {
                local_fallback_check(
                    &buckets, &bucket_key, default_rps, burst_size, window_ms,
                    local_fallback_divisor,
                ).await
            };

            if !allowed {
                let resp = (
                    axum::http::StatusCode::TOO_MANY_REQUESTS,
                    [("Retry-After", "1")],
                ).into_response();
                return Ok(resp);
            }

            inner.await
        })
    }
}

/// Per-pod, in-memory fallback used both when Redis was never configured
/// and when a configured Redis becomes unavailable mid-request. Always
/// scaled down by `local_fallback_divisor` (see RateLimitingConfig) since
/// this bucket has no visibility into any other replica's traffic --
/// using the full global ceiling per pod would let an attacker multiply
/// their effective budget by roughly the replica count during exactly the
/// kind of dependency degradation the finding describes.
async fn local_fallback_check(
    buckets: &Arc<Mutex<LruCache<String, TokenBucket>>>,
    bucket_key: &str,
    default_rps: u64,
    burst_size: u64,
    window_ms: u64,
    divisor: u64,
) -> bool {
    let scaled_rps = (default_rps / divisor).max(1);
    let scaled_burst = (burst_size / divisor).max(1);
    let mut buckets = buckets.lock().await;
    let bucket = buckets.get_or_insert_mut(bucket_key.to_string(), || {
        TokenBucket::new(scaled_rps, scaled_burst, window_ms)
    });
    bucket.consume_async(1).await
}

fn rate_limit_degraded_total() -> &'static prometheus::Counter {
    static COUNTER: std::sync::OnceLock<prometheus::Counter> = std::sync::OnceLock::new();
    COUNTER.get_or_init(|| {
        prometheus::register_counter!(
            "gateway_rate_limit_redis_degraded_total",
            "Requests where a configured Redis rate limiter failed and the gateway fell back to the local, per-pod limiter"
        ).expect("failed to register rate limit degradation counter")
    })
}

/// Outcome of a Redis-backed rate-limit check: either Redis was reachable
/// and gave a real answer (allowed or denied), or Redis itself is
/// unavailable and the caller must decide what to do (see
/// `local_fallback_check`) rather than this function silently picking a
/// default.
enum RedisRateLimitOutcome {
    Decided(bool),
    Unavailable,
}

async fn redis_rate_limit_check(
    conn: &redis::aio::ConnectionManager,
    key: &str,
    max_rps: u64,
    burst: u64,
) -> RedisRateLimitOutcome {
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

    // SECURITY (F-006): GET on a key that legitimately doesn't exist yet
    // (the first request in a new window) is expected and must read as
    // zero. A genuine connection/command failure must NOT be collapsed
    // into that same zero -- doing so (the previous `.unwrap_or(0)`)
    // meant a Redis outage silently disabled rate limiting entirely
    // rather than falling back to anything at all. Querying as
    // `Option<u64>` lets redis-rs's Nil-reply handling distinguish "key
    // absent" (`Ok(None)`, expected, treated as 0) from "command actually
    // failed" (`Err`, reported to the caller as Unavailable).
    let burst_count: Result<Option<u64>, _> = redis::cmd("GET")
        .arg(&burst_window_key)
        .query_async(&mut conn)
        .await;
    let burst_count = match burst_count {
        Ok(count) => count.unwrap_or(0),
        Err(e) => {
            tracing::warn!(error = %e, "Redis rate-limit GET failed -- falling back to local rate limiting");
            return RedisRateLimitOutcome::Unavailable;
        }
    };
    if burst_count >= burst {
        return RedisRateLimitOutcome::Decided(false);
    }

    let sustained_count: Result<Option<u64>, _> = redis::cmd("GET")
        .arg(&sustained_window_key)
        .query_async(&mut conn)
        .await;
    let sustained_count = match sustained_count {
        Ok(count) => count.unwrap_or(0),
        Err(e) => {
            tracing::warn!(error = %e, "Redis rate-limit GET failed -- falling back to local rate limiting");
            return RedisRateLimitOutcome::Unavailable;
        }
    };
    if sustained_count >= sustained_cap {
        return RedisRateLimitOutcome::Decided(false);
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

    RedisRateLimitOutcome::Decided(true)
}
