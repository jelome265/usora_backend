use std::future::Future;
use std::pin::Pin;
use std::task::{Context, Poll};

use axum::extract::Request;
use axum::response::Response;
use tower::{Layer, Service};

#[derive(Clone, Default)]
pub struct CorsLayer;

impl CorsLayer {
    pub fn new() -> Self {
        Self
    }
}

impl<S> Layer<S> for CorsLayer {
    type Service = CorsMiddleware<S>;

    fn layer(&self, inner: S) -> Self::Service {
        CorsMiddleware { inner }
    }
}

#[derive(Clone)]
pub struct CorsMiddleware<S> {
    inner: S,
}

impl<S> Service<Request> for CorsMiddleware<S>
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
        let is_preflight = req.method() == axum::http::Method::OPTIONS
            && req.headers().contains_key("Origin")
            && req.headers().contains_key("Access-Control-Request-Method");

        if is_preflight {
            let mut resp = Response::new(axum::body::Body::empty());
            let headers = resp.headers_mut();
            headers.insert(
                axum::http::header::ACCESS_CONTROL_ALLOW_ORIGIN,
                "*".parse().unwrap(),
            );
            headers.insert(
                axum::http::header::ACCESS_CONTROL_ALLOW_METHODS,
                "GET, POST, PUT, DELETE, PATCH, OPTIONS".parse().unwrap(),
            );
            headers.insert(
                axum::http::header::ACCESS_CONTROL_ALLOW_HEADERS,
                "Content-Type, Authorization, X-Tenant-ID, X-Request-ID"
                    .parse()
                    .unwrap(),
            );
            headers.insert(
                axum::http::header::ACCESS_CONTROL_MAX_AGE,
                "86400".parse().unwrap(),
            );
            return Box::pin(async move { Ok(resp) });
        }

        let fut = self.inner.call(req);

        Box::pin(async move {
            let mut resp = fut.await?;
            let headers = resp.headers_mut();
            if !headers.contains_key(axum::http::header::ACCESS_CONTROL_ALLOW_ORIGIN) {
                headers.insert(
                    axum::http::header::ACCESS_CONTROL_ALLOW_ORIGIN,
                    "*".parse().unwrap(),
                );
                headers.insert(
                    axum::http::header::ACCESS_CONTROL_EXPOSE_HEADERS,
                    "X-Request-ID, X-Tenant-ID".parse().unwrap(),
                );
            }
            Ok(resp)
        })
    }
}
