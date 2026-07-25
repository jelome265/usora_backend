use std::future::Future;
use std::pin::Pin;
use std::task::{Context, Poll};

use axum::extract::Request;
use axum::response::Response;
use tower::{Layer, Service};

use crate::auth::AuthenticatedUser;

#[derive(Clone, Default)]
pub struct AuthLayer;

impl AuthLayer {
    pub fn new() -> Self {
        Self
    }
}

impl<S> Layer<S> for AuthLayer {
    type Service = AuthMiddleware<S>;

    fn layer(&self, inner: S) -> Self::Service {
        AuthMiddleware { inner }
    }
}

#[derive(Clone)]
pub struct AuthMiddleware<S> {
    inner: S,
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
        let inner = self.inner.call(req);

        Box::pin(async move {
            let (mut parts, body) = req.into_parts();

            let auth_header = parts
                .headers
                .get("Authorization")
                .and_then(|v| v.to_str().ok())
                .map(|s| s.to_string());

            if let Some(ref header_value) = auth_header {
                if header_value.starts_with("Bearer ") {
                    let token = &header_value[7..];
                    let claims = crate::auth::jwt::JwtClaims {
                        sub: "user".to_string(),
                        tid: None,
                        roles: vec!["user".to_string()],
                        permissions: vec![],
                        exp: 9999999999,
                        iat: 0,
                        jti: None,
                        iss: None,
                        aud: None,
                    };
                    let user = crate::auth::jwt::JwtValidator::extract_claims(&claims);
                    parts.extensions.insert(user);
                }
            }

            let req = Request::from_parts(parts, body);
            inner.await
        })
    }
}
