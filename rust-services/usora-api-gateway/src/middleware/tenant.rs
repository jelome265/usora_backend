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

/// Permission required to act on behalf of a tenant other than the one in
/// the caller's own verified JWT claim. Only present on trusted internal/
/// admin principals — never granted to ordinary tenant users.
const CROSS_TENANT_OVERRIDE_PERMISSION: &str = "tenant:cross_tenant_override";

fn resolve_tenant(req: &Request) -> Option<String> {
    // SECURITY: the tenant claim embedded in the verified JWT is always the
    // source of truth for an authenticated caller. It MUST be resolved
    // before any client-supplied header is even considered — a request
    // header is fully attacker-controlled and must never be trusted to
    // select which tenant's data a request operates against.
    let authenticated_user = req.extensions().get::<AuthenticatedUser>();

    if let Some(user) = authenticated_user {
        if let Some(ref tid) = user.tenant_id {
            // A caller may only override their own tenant via the
            // X-Tenant-ID header if they hold an explicit cross-tenant
            // override permission (e.g. a platform admin/support tool).
            // This is intentionally opt-in and narrow, not the default path.
            let has_override_permission = user
                .permissions
                .iter()
                .any(|p| p == CROSS_TENANT_OVERRIDE_PERMISSION);

            if has_override_permission {
                if let Some(header_val) = req
                    .headers()
                    .get("X-Tenant-ID")
                    .and_then(|v| v.to_str().ok())
                {
                    if !header_val.is_empty() {
                        // TODO(security-team, USORA-XXX, 2026-07-31): write this
                        // override to the immutable audit trail (who, target
                        // tenant, from where) before this ships to production.
                        return Some(header_val.to_string());
                    }
                }
            }

            return Some(tid.clone());
        }
    }

    // Unauthenticated / no tenant claim on the token: the X-Tenant-ID header
    // and the URL path segment are equally untrusted at this point, so we
    // do NOT fall back to the header here either — only a routing hint
    // from the URL path is used, which downstream authorization checks
    // must still independently validate against the caller's identity.
    let path = req.uri().path();
    let parts: Vec<&str> = path.split('/').filter(|s| !s.is_empty()).collect();
    if parts.len() >= 2 && parts[0] == "v1" {
        return Some(parts[1].to_string());
    }

    None
}
