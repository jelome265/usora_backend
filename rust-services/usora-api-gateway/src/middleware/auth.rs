use std::future::Future;
use std::pin::Pin;
use std::task::{Context, Poll};

use axum::extract::Request;
use axum::response::{IntoResponse, Response};
use http::StatusCode;
use tower::{Layer, Service};

use crate::auth::jwt::JwtValidator;
use crate::auth::AuthenticatedUser;

#[derive(Clone, Default)]
pub struct AuthLayer {
    bypass_paths: Vec<String>,
}

impl AuthLayer {
    pub fn new() -> Self {
        Self {
            bypass_paths: vec!["/health".into(), "/metrics".into()],
        }
    }

    pub fn with_bypass_paths(paths: Vec<String>) -> Self {
        Self { bypass_paths: paths }
    }
}

impl<S> Layer<S> for AuthLayer {
    type Service = AuthMiddleware<S>;

    fn layer(&self, inner: S) -> Self::Service {
        AuthMiddleware {
            inner,
            bypass_paths: self.bypass_paths.clone(),
        }
    }
}

#[derive(Clone)]
pub struct AuthMiddleware<S> {
    inner: S,
    bypass_paths: Vec<String>,
}

impl<S> Service<Request> for AuthMiddleware<S>
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
        let path = req.uri().path().to_string();
        let bypass = self.bypass_paths.iter().any(|p| path == *p || path.starts_with(p));

        if bypass {
            let fut = self.inner.call(req);
            return Box::pin(async move { fut.await });
        }

        let inner = self.inner.call(req);

        Box::pin(async move {
            let (mut parts, body) = req.into_parts();

            let auth_header = parts
                .headers
                .get("Authorization")
                .and_then(|v| v.to_str().ok())
                .map(|s| s.to_string());

            let token = match auth_header {
                Some(ref v) if v.starts_with("Bearer ") => &v[7..],
                _ => {
                    let resp = (StatusCode::UNAUTHORIZED, "Missing or invalid Authorization header").into_response();
                    return Ok(resp);
                }
            };

            match JwtValidator::validate(token) {
                Ok(claims) => {
                    let user = JwtValidator::extract_claims(&claims);
                    let tid = claims.tid.clone().unwrap_or_default();
                    parts.extensions.insert(user);

                    if !tid.is_empty() {
                        parts.extensions.insert(crate::middleware::tenant::TenantContext { tenant_id: tid });
                    }

                    let req = Request::from_parts(parts, body);
                    inner.await
                }
                Err(e) => {
                    tracing::warn!(error = %e, "JWT validation failed");
                    let resp = (StatusCode::UNAUTHORIZED, format!("Invalid token: {e}")).into_response();
                    Ok(resp)
                }
            }
        })
    }
}
