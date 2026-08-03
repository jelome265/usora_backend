use std::future::Future;
use std::num::NonZeroUsize;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};

use axum::extract::Request;
use axum::response::{IntoResponse, Response};
use lru::LruCache;
use tokio::sync::Mutex;
use tower::{Layer, Service};

use crate::rate_limit::token_bucket::TokenBucket;

#[derive(Clone)]
pub struct RateLimitLayer {
    default_rps: u64,
    burst_size: u64,
    window_ms: u64,
}

impl RateLimitLayer {
    pub fn new(default_rps: u64, burst_size: u64, window_ms: u64) -> Self {
        Self { default_rps, burst_size, window_ms }
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
        let tenant_id = req
            .headers()
            .get("X-Tenant-ID")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("default")
            .to_string();

        let client_ip = req
            .headers()
            .get("X-Forwarded-For")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("unknown")
            .to_string();

        let endpoint = req.uri().path().to_string();
        let bucket_key = format!("{tenant_id}:{client_ip}:{endpoint}");

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
                ).into_response();
                return Ok(resp);
            }

            inner.await
        })
    }
}

async fn redis_rate_limit_check(
    conn: &redis::aio::ConnectionManager,
    key: &str,
    // TODO(rate-limiting owner): max_rps is currently unused -- this
    // function only enforces `burst` as a flat per-second cap, so a low
    // max_rps configured alongside a high burst would allow sustained
    // traffic at the burst rate indefinitely, not the intended
    // steady-state rate. Needs a proper token-bucket/sliding-window
    // design (see TokenBucket in this crate's in-memory fallback path)
    // implemented atomically in Redis, not just this warning silenced.
    _max_rps: u64,
    burst: u64,
) -> bool {
    let mut conn = conn.clone();
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as u64;
    let window_ms = 1000u64;
    let window_key = format!("{key}:{}", now / window_ms);

    let count: u64 = redis::cmd("GET")
        .arg(&window_key)
        .query_async(&mut conn)
        .await
        .unwrap_or(0);

    if count >= burst {
        return false;
    }

    let _: Result<(), _> = redis::cmd("INCR")
        .arg(&window_key)
        .query_async(&mut conn)
        .await;

    let _: Result<(), _> = redis::cmd("EXPIRE")
        .arg(&window_key)
        .arg(2u64)
        .query_async(&mut conn)
        .await;

    true
}
