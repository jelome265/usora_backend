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

    fn call(&mut self, mut req: Request) -> Self::Future {
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::auth::AuthMethod;
    use axum::body::Body;
    use axum::http::Request as HttpRequest;

    fn request_with(header: Option<&str>, user: Option<AuthenticatedUser>, path: &str) -> Request {
        let mut builder = HttpRequest::builder().uri(path);
        if let Some(h) = header {
            builder = builder.header("X-Tenant-ID", h);
        }
        let mut req = builder.body(Body::empty()).unwrap();
        if let Some(u) = user {
            req.extensions_mut().insert(u);
        }
        req
    }

    fn authenticated_user(tenant_id: &str, permissions: Vec<&str>) -> AuthenticatedUser {
        AuthenticatedUser {
            sub: "user-1".to_string(),
            tenant_id: Some(tenant_id.to_string()),
            roles: vec![],
            permissions: permissions.into_iter().map(|p| p.to_string()).collect(),
            auth_method: AuthMethod::Jwt,
        }
    }

    /// SECURITY REGRESSION TEST: a client-supplied X-Tenant-ID header must
    /// never override an authenticated user's own verified tenant claim
    /// unless that user explicitly holds the cross-tenant override
    /// permission. This is the exact bypass described in
    /// docs/architecture-security-review-2026-07-31.md §3.1 — if this test
    /// starts failing, the tenant-isolation bypass has come back.
    #[test]
    fn header_does_not_override_jwt_tenant_without_permission() {
        let user = authenticated_user("tenant-legit", vec!["some:other:permission"]);
        let req = request_with(
            Some("tenant-attacker"),
            Some(user),
            "/v1/tenant-attacker/resource",
        );

        let resolved = resolve_tenant(&req);

        assert_eq!(
            resolved,
            Some("tenant-legit".to_string()),
            "the verified JWT tenant claim must win over an attacker-supplied X-Tenant-ID header"
        );
    }

    /// A caller with no cross-tenant permission at all (no permissions list)
    /// must also be pinned to their own tenant, regardless of any header.
    #[test]
    fn header_ignored_for_user_with_no_permissions() {
        let user = authenticated_user("tenant-legit", vec![]);
        let req = request_with(Some("tenant-attacker"), Some(user), "/v1/anything");

        assert_eq!(resolve_tenant(&req), Some("tenant-legit".to_string()));
    }

    /// A caller that DOES hold the explicit override permission may use the
    /// header — this is the one narrow, intentional exception.
    #[test]
    fn header_honored_only_with_explicit_override_permission() {
        let user = authenticated_user("tenant-admin-home", vec![CROSS_TENANT_OVERRIDE_PERMISSION]);
        let req = request_with(Some("tenant-target"), Some(user), "/v1/anything");

        assert_eq!(resolve_tenant(&req), Some("tenant-target".to_string()));
    }

    /// An authenticated user with a tenant claim but an empty header value
    /// still resolves to their own tenant (empty header must not be treated
    /// as "use the header").
    #[test]
    fn empty_header_value_does_not_break_jwt_resolution() {
        let user = authenticated_user("tenant-legit", vec![CROSS_TENANT_OVERRIDE_PERMISSION]);
        let req = request_with(Some(""), Some(user), "/v1/anything");

        assert_eq!(resolved_or_panic(&req), "tenant-legit");
    }

    fn resolved_or_panic(req: &Request) -> String {
        resolve_tenant(req).expect("expected a resolved tenant")
    }

    /// No authenticated user at all: falls back to the URL path segment
    /// (an unauthenticated routing hint only — downstream authorization
    /// must independently validate it), and must NOT trust the header.
    #[test]
    fn unauthenticated_request_uses_path_not_header() {
        let req = request_with(
            Some("tenant-attacker"),
            None,
            "/v1/tenant-from-path/resource",
        );

        assert_eq!(resolve_tenant(&req), Some("tenant-from-path".to_string()));
    }

    /// No authenticated user, no matching path shape: resolves to nothing.
    #[test]
    fn unauthenticated_request_with_no_path_hint_resolves_none() {
        let req = request_with(Some("tenant-attacker"), None, "/health");

        assert_eq!(resolve_tenant(&req), None);
    }

    /// An authenticated user whose token has no tenant claim at all falls
    /// through to the path-based hint, same as an unauthenticated request —
    /// it must not pick up the header either.
    #[test]
    fn authenticated_user_without_tenant_claim_falls_back_to_path() {
        let user = AuthenticatedUser {
            sub: "service-account".to_string(),
            tenant_id: None,
            roles: vec![],
            permissions: vec![],
            auth_method: AuthMethod::Jwt,
        };
        let req = request_with(
            Some("tenant-attacker"),
            Some(user),
            "/v1/tenant-from-path/resource",
        );

        assert_eq!(resolve_tenant(&req), Some("tenant-from-path".to_string()));
    }
}
