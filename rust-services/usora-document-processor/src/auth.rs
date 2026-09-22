//! Internal-service bearer-token authentication for the REST API in
//! `routes/mod.rs`.
//!
//! PRE-EXISTING GAP, found and fixed while writing this service's Helm
//! chart: the REST router (`/api/v1/documents/*`) took `tenant_id`
//! directly from the JSON request body with no authentication check
//! anywhere in the request path — any caller able to reach this port
//! could run document forgery/security-feature analysis and claim any
//! `tenant_id` it liked. This is the same class of problem as findings
//! C2/C4 in docs/USORA-BACKEND-ENTERPRISE-AUDIT-2026-08-16.md: an
//! internal API surface that looked gated (it's not exposed by the public
//! gateway's route table) but had no actual enforcement of its own.
//!
//! Verification here uses a shared HMAC secret (HS256), matching the
//! pattern already used by usora-compliance-service's JwtTokenProvider —
//! this is deliberately the *lighter* of the two JWT patterns already
//! present in this codebase (the gateway uses full JWKS/RS256 with
//! rotation) because this endpoint is intended for service-to-service
//! calls from the orchestrator (usora-core), not end-user traffic; a
//! shared secret distributed to trusted internal callers via a mounted
//! Secret is an appropriate and common pattern for that case. Defense in
//! depth beyond this middleware — restricting which pods can reach this
//! port at all — is handled separately by the NetworkPolicy in this
//! chart's `networkpolicy.yaml`; this middleware exists so a
//! misconfigured or bypassed network policy is not the only thing
//! standing between this endpoint and an unauthenticated caller.

use axum::extract::{Request, State};
use axum::http::{header, StatusCode};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use jsonwebtoken::{decode, DecodingKey, Validation, Algorithm};
use serde::{Deserialize, Serialize};
use std::sync::Arc;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServiceClaims {
    pub sub: String,
    /// Required claim proving the caller was issued a token specifically
    /// scoped for calling internal compute services — a generic
    /// end-user-facing token must not be accepted here.
    pub scope: Vec<String>,
    pub exp: usize,
}

#[derive(Clone)]
pub struct AuthState {
    decoding_key: DecodingKey,
    validation: Validation,
}

impl AuthState {
    pub fn new(shared_secret: &str) -> Self {
        let mut validation = Validation::new(Algorithm::HS256);
        validation.set_required_spec_claims(&["exp", "sub"]);
        Self {
            decoding_key: DecodingKey::from_secret(shared_secret.as_bytes()),
            validation,
        }
    }

    fn verify(&self, token: &str) -> Result<ServiceClaims, jsonwebtoken::errors::Error> {
        decode::<ServiceClaims>(token, &self.decoding_key, &self.validation).map(|d| d.claims)
    }
}

pub async fn require_service_auth(
    State(auth): State<Arc<AuthState>>,
    req: Request,
    next: Next,
) -> Response {
    let token = req
        .headers()
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "));

    let Some(token) = token else {
        return (StatusCode::UNAUTHORIZED, "missing bearer token").into_response();
    };

    let claims = match auth.verify(token) {
        Ok(c) => c,
        Err(e) => {
            tracing::warn!("REST auth rejected token: {e}");
            return (StatusCode::UNAUTHORIZED, "invalid token").into_response();
        }
    };

    // SECURITY: signature validity alone is not authorization — the token
    // must also carry the specific scope this service requires. See the
    // equivalent principle enforced for compliance-service's dual
    // authorization (finding C2 in the audit doc): a validly signed token
    // for a *different* purpose must not be accepted here.
    if !claims.scope.iter().any(|s| s == "document-processor:invoke") {
        tracing::warn!(subject = %claims.sub, "REST auth rejected token missing required scope");
        return (StatusCode::FORBIDDEN, "token missing required scope").into_response();
    }

    next.run(req).await
}
