use std::future::Future;
use std::pin::Pin;
use std::task::{Context, Poll};

use axum::extract::Request;
use axum::response::Response;
use tower::{Layer, Service};

use crate::auth::AuthenticatedUser;

#[derive(Debug, Clone)]
pub struct TenantContext {
    pub tenant_id: String,
}

impl TenantContext {
    pub fn new(tenant_id: String) -> Self {
        Self { tenant_id }
    }
}

#[derive(Clone, Default)]
pub struct TenantLayer;

impl TenantLayer {
    pub fn new() -> Self {
        Self
    }
}

impl<S> Layer<S> for TenantLayer {
    type Service = TenantMiddleware<S>;

    fn layer(&self, inner: S) -> Self::Service {
        TenantMiddleware { inner }
    }
}

#[derive(Clone)]
pub struct TenantMiddleware<S> {
    inner: S,
}

impl<S> Service<Request> for TenantMiddleware<S>
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
        let tenant_id = resolve_tenant(&req);

        if let Some(tid) = tenant_id {
            req.extensions_mut().insert(TenantContext::new(tid));
        }

        let fut = self.inner.call(req);
        Box::pin(async move { fut.await })
    }
}

fn resolve_tenant(req: &Request) -> Option<String> {
    if let Some(header_val) = req
        .headers()
        .get("X-Tenant-ID")
        .and_then(|v| v.to_str().ok())
    {
        if !header_val.is_empty() {
            return Some(header_val.to_string());
        }
    }

    if let Some(user) = req.extensions().get::<AuthenticatedUser>() {
        if let Some(ref tid) = user.tenant_id {
            return Some(tid.clone());
        }
    }

    let path = req.uri().path();
    let parts: Vec<&str> = path.split('/').filter(|s| !s.is_empty()).collect();
    if parts.len() >= 2 && parts[0] == "v1" {
        return Some(parts[1].to_string());
    }

    None
}
