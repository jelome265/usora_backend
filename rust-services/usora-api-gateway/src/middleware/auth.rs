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
    /// `validator` should be the single shared, JWKS-populated validator from
    /// `AppState` -- see AppState::new. Building a fresh
    /// `JwtValidator::new(None, None)` here (as this used to do) meant an
    /// empty key set and no issuer/audience enforcement on every request.
    pub fn new(validator: JwtValidator) -> Self {
        Self {
            bypass_paths: vec!["/health".into(), "/metrics".into()],
            validator,
        }
    }

    pub fn with_bypass_paths(paths: Vec<String>, validator: JwtValidator) -> Self {
        Self {
            bypass_paths: paths,
            validator,
        }
    }
}

// No Default impl: constructing an AuthLayer requires a real, JWKS-populated
// JwtValidator (see AppState::new) -- a default-constructed one with no keys
// would silently reject every token, which is exactly the bug this module
// exists to fix.

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

        // F-021: builds the response with an explicit request_id rather
        // than calling crate::utils::json_error (which generates its own
        // fresh, unrelated UUID internally) -- the whole point of a
        // correlation ID is that the ID in the response body and the ID
        // in the server-side log line for the same request must match,
        // so an operator (or the caller reporting a problem) can find the
        // detailed log entry from what the client actually received.
        fn auth_error_response(status: StatusCode, message: &str, request_id: String) -> Response {
            let body = crate::models::ApiResponse::<()>::error(message, request_id);
            (status, axum::Json(body)).into_response()
        }

        fn resolve_request_id(headers: &axum::http::HeaderMap) -> String {
            headers
                .get("X-Request-ID")
                .and_then(|v| v.to_str().ok())
                .map(|s| s.to_string())
                .unwrap_or_else(crate::utils::uuid_v7)
        }

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
                    let request_id = resolve_request_id(&parts.headers);
                    tracing::warn!(request_id = %request_id, "Request rejected: missing or malformed Authorization header");
                    return Ok(auth_error_response(
                        StatusCode::UNAUTHORIZED,
                        "Authentication required",
                        request_id,
                    ));
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
                    // F-021: previously returned format!("Invalid token:
                    // {e}") directly as the HTTP response body -- jwt::Error's
                    // Display impl for the Validation variant forwards
                    // jsonwebtoken's own internal error text verbatim
                    // (e.g. specific claim-validation failure reasons,
                    // signature/key details), which is exactly the
                    // "cryptographic/internal parser detail" the
                    // acceptance criterion says a client response must
                    // never contain. The full error is still logged
                    // server-side (unchanged) for actual debugging; the
                    // client now gets one of a small number of generic,
                    // stable messages that convey only what a legitimate
                    // caller needs to act on (token missing/invalid vs.
                    // expired vs. revoked), never why in cryptographic
                    // detail.
                    let request_id = resolve_request_id(&parts.headers);
                    let (client_message, error_code) = match &e {
                        crate::auth::jwt::jwt::Error::Expired => {
                            ("Authentication token has expired", "AUTH_TOKEN_EXPIRED")
                        }
                        crate::auth::jwt::jwt::Error::Revoked => (
                            "Authentication token has been revoked",
                            "AUTH_TOKEN_REVOKED",
                        ),
                        _ => ("Authentication token is invalid", "AUTH_TOKEN_INVALID"),
                    };
                    tracing::warn!(error = %e, error_code, request_id = %request_id, "JWT validation failed");
                    Ok(auth_error_response(
                        StatusCode::UNAUTHORIZED,
                        client_message,
                        request_id,
                    ))
                }
            }
        })
    }
}
