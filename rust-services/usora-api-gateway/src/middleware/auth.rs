use std::future::Future;
use std::pin::Pin;
use std::task::{Context, Poll};

use axum::extract::Request;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use tower::{Layer, Service};

use crate::auth::jwt::JwtValidator;

#[derive(Clone)]
pub struct AuthLayer {
    bypass_paths: Vec<String>,
    validator: JwtValidator,
}

impl AuthLayer {
    pub fn new() -> Self {
        Self {
            bypass_paths: vec!["/health".into(), "/metrics".into()],
            validator: JwtValidator::new(None, None),
        }
    }

    pub fn with_bypass_paths(paths: Vec<String>) -> Self {
        Self {
            bypass_paths: paths,
            validator: JwtValidator::new(None, None),
        }
    }
}

impl Default for AuthLayer {
    fn default() -> Self {
        Self::new()
    }
}

impl<S> Layer<S> for AuthLayer {
    type Service = AuthMiddleware<S>;

    fn layer(&self, inner: S) -> Self::Service {
        AuthMiddleware {
            inner,
            bypass_paths: self.bypass_paths.clone(),
            validator: self.validator.clone(),
        }
    }
}

#[derive(Clone)]
pub struct AuthMiddleware<S> {
    inner: S,
    bypass_paths: Vec<String>,
    validator: JwtValidator,
}

impl<S> Service<Request> for AuthMiddleware<S>
where
    S: Service<Request, Response = Response> + Clone + Send + 'static,
    S::Future: Send + 'static,
{
    type Response = Response;
    type Error = S::Error;
    type Future = Pin<Box<dyn Future<Output = Result<Self::Response, Self::Error>> + Send>>;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, req: Request) -> Self::Future {
        let path = req.uri().path().to_string();
        let bypass = self
            .bypass_paths
            .iter()
            .any(|p| path == *p || path.starts_with(p));

        // Clone the inner service (cheap -- tower services are designed for
        // this) so `self.inner` isn't consumed before we know whether we're
        // even going to call it, and so the same `req` value can be fully
        // validated and mutated (auth/tenant extensions attached) before the
        // single call to inner.call() -- the previous version called
        // self.inner.call(req) unconditionally before the auth check even
        // ran, then tried to reuse the already-moved `req` afterward, and
        // the inner service never actually saw the auth-derived extensions.
        let mut inner = self.inner.clone();

        if bypass {
            return Box::pin(async move { inner.call(req).await });
        }

        let validator = self.validator.clone();

        Box::pin(async move {
            let (mut parts, body) = req.into_parts();

            let auth_header = parts
                .headers
                .get("Authorization")
                .and_then(|v| v.to_str().ok())
                .map(|s| s.to_string());

            let token = match auth_header {
                Some(ref v) if v.starts_with("Bearer ") => v[7..].to_string(),
                _ => {
                    let resp = (
                        StatusCode::UNAUTHORIZED,
                        "Missing or invalid Authorization header",
                    )
                        .into_response();
                    return Ok(resp);
                }
            };

            match validator.validate_token(&token).await {
                Ok(claims) => {
                    let user = JwtValidator::extract_claims(&claims);
                    let tid = claims.tid.clone().unwrap_or_default();
                    parts.extensions.insert(user);

                    if !tid.is_empty() {
                        parts
                            .extensions
                            .insert(crate::middleware::tenant::TenantContext { tenant_id: tid });
                    }

                    let req = Request::from_parts(parts, body);
                    inner.call(req).await
                }
                Err(e) => {
                    tracing::warn!(error = %e, "JWT validation failed");
                    let resp =
                        (StatusCode::UNAUTHORIZED, format!("Invalid token: {e}")).into_response();
                    Ok(resp)
                }
            }
        })
    }
}
