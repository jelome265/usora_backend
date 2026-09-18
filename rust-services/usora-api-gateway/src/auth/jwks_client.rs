//! Fetches and parses a standard JWKS (JSON Web Key Set) document from
//! identity-service, producing the `kid -> DecodingKey` map that
//! `JwtValidator` needs to actually verify token signatures.
//!
//! Before this module existed, `JwtValidator::update_jwks` was dead code --
//! nothing ever called it, so the gateway's JWKS map was always empty and
//! every token failed with `MissingKey` regardless of validity (see
//! auth/jwt.rs and middleware/auth.rs history). This closes that gap.

use jsonwebtoken::DecodingKey;
use serde::Deserialize;
use std::collections::HashMap;

#[derive(Debug, Deserialize)]
struct Jwk {
    kid: Option<String>,
    kty: String,
    #[serde(rename = "use")]
    #[serde(default)]
    key_use: Option<String>,
    n: Option<String>,
    e: Option<String>,
}

#[derive(Debug, Deserialize)]
struct JwkSet {
    keys: Vec<Jwk>,
}

#[derive(Debug, thiserror::Error)]
pub enum JwksError {
    #[error("failed to fetch JWKS from {url}: {source}")]
    Request { url: String, source: reqwest::Error },
    #[error("JWKS response from {url} was not valid JSON: {source}")]
    Parse { url: String, source: reqwest::Error },
    #[error("JWKS document from {0} contained no usable keys")]
    Empty(String),
}

/// Fetches the JWKS document at `url` and returns a map of key ID -> decoding
/// key, keeping only RSA signing keys (this gateway only ever verifies
/// RS256-signed tokens, matching identity-service's signing algorithm).
/// Keys this gateway can't use (wrong `kty`, or a `use` explicitly marked
/// something other than "sig") are skipped rather than causing the whole
/// fetch to fail, since a JWKS document may legitimately contain unrelated
/// keys (e.g. encryption keys) alongside signing keys.
pub async fn fetch_jwks(
    client: &reqwest::Client,
    url: &str,
) -> Result<HashMap<String, DecodingKey>, JwksError> {
    let response = client
        .get(url)
        .send()
        .await
        .map_err(|source| JwksError::Request {
            url: url.to_string(),
            source,
        })?;

    let jwk_set: JwkSet = response.json().await.map_err(|source| JwksError::Parse {
        url: url.to_string(),
        source,
    })?;

    let mut keys = HashMap::new();
    for jwk in jwk_set.keys {
        if jwk.kty != "RSA" {
            tracing::debug!(kty = %jwk.kty, "skipping non-RSA JWKS entry");
            continue;
        }
        if let Some(use_) = &jwk.key_use {
            if use_ != "sig" {
                tracing::debug!(key_use = %use_, "skipping JWKS entry not marked for signing");
                continue;
            }
        }

        let (Some(n), Some(e)) = (jwk.n.as_deref(), jwk.e.as_deref()) else {
            tracing::warn!("skipping RSA JWKS entry missing n/e components");
            continue;
        };

        match DecodingKey::from_rsa_components(n, e) {
            Ok(key) => {
                // identity-service always sets a kid (see JwtTokenProvider),
                // but fall back to a fixed key rather than silently
                // dropping an otherwise-usable key if one is ever missing.
                let kid = jwk.kid.clone().unwrap_or_else(|| "default".to_string());
                keys.insert(kid, key);
            }
            Err(e) => {
                tracing::warn!(error = %e, "failed to parse RSA JWKS entry, skipping");
            }
        }
    }

    if keys.is_empty() {
        return Err(JwksError::Empty(url.to_string()));
    }

    Ok(keys)
}

/// Spawns a background task that periodically re-fetches the JWKS and pushes
/// any successfully-parsed key set into `validator`. Fetch failures are
/// logged and simply skipped (the previous key set stays in place) rather
/// than propagated -- a transient network blip against identity-service
/// should not take down token validation gateway-wide, only a missing key
/// for a *specific* kid should (which `JwtValidator::validate_token` already
/// handles by rejecting that one token).
pub fn spawn_refresh_task(
    validator: crate::auth::jwt::JwtValidator,
    client: reqwest::Client,
    url: String,
    interval_secs: u64,
) {
    tokio::spawn(async move {
        let mut interval =
            tokio::time::interval(std::time::Duration::from_secs(interval_secs.max(1)));
        // The first tick fires immediately; we already did an initial fetch
        // synchronously at startup (see AppState::new), so skip it here to
        // avoid a redundant fetch right after boot.
        interval.tick().await;

        loop {
            interval.tick().await;
            match fetch_jwks(&client, &url).await {
                Ok(keys) => {
                    let count = keys.len();
                    validator.update_jwks(keys).await;
                    tracing::info!(key_count = count, "refreshed JWKS from identity-service");
                }
                Err(e) => {
                    tracing::warn!(error = %e, "failed to refresh JWKS -- keeping previously loaded keys");
                }
            }
        }
    });
}
