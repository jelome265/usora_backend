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
    default_rps: u64,
    burst_size: u64,
    window_ms: u64,
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
        let bucket_key = req
            .headers()
            .get("X-Tenant-ID")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("default")
            .to_string();

        let allowed = {
            let mut buckets = self.buckets.blocking_lock();
            let bucket = buckets.get_or_insert(&bucket_key, || {
                TokenBucket::new(self.default_rps, self.burst_size, self.window_ms)
            });
            bucket.consume(1)
        };

        if !allowed {
            return Box::pin(async move {
                Ok(axum::http::StatusCode::TOO_MANY_REQUESTS.into_response())
            });
        }

        let fut = self.inner.call(req);
        Box::pin(async move { fut.await })
    }
}
