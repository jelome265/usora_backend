use std::collections::HashMap;
use std::sync::Arc;
use arc_swap::ArcSwap;
use jsonwebtoken::{decode, decode_header, DecodingKey, Validation, Algorithm};
use lru::LruCache;
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;

use super::AuthenticatedUser;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JwtClaims {
    pub sub: String,
    pub tid: Option<String>,
    pub roles: Vec<String>,
    pub permissions: Vec<String>,
    pub exp: usize,
    pub iat: usize,
    pub jti: Option<String>,
    pub iss: Option<String>,
    pub aud: Option<Vec<String>>,
}

#[derive(Clone)]
pub struct JwtValidator {
    validation: Validation,
    jwks: Arc<ArcSwap<HashMap<String, DecodingKey>>>,
    cache: Arc<Mutex<LruCache<String, JwtClaims>>>,
}

impl JwtValidator {
    pub fn new(issuer: Option<String>, audience: Option<String>) -> Self {
        let mut validation = Validation::new(Algorithm::RS256);
        validation.leeway = 30;
        validation.validate_exp = true;
        if let Some(iss) = issuer {
            validation.set_issuer(&[iss]);
        }
        if let Some(aud) = audience {
            validation.set_audience(&[aud]);
        }

        Self {
            validation,
            jwks: Arc::new(ArcSwap::new(Arc::new(HashMap::new()))),
            cache: Arc::new(Mutex::new(LruCache::new(1000.try_into().unwrap()))),
        }
    }

    pub async fn validate_token(&self, token: &str) -> Result<JwtClaims, jwt::Error> {
        {
            let mut cache = self.cache.lock().await;
            // Clone out of the cache immediately (rather than holding a
            // borrow across the possible `pop` below) to keep the
            // lock-scoped borrow checking simple and unambiguous.
            let cached_claims = cache.get(token).cloned();

            if let Some(claims) = cached_claims {
                // SECURITY: the LRU cache has no time-based eviction, only a
                // capacity limit — so a cache hit alone does not mean the
                // token is still valid. Re-check `exp` against wall-clock
                // time on every hit; otherwise an expired token can keep
                // validating successfully until it happens to be evicted.
                let now = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_secs() as usize)
                    .unwrap_or(usize::MAX);

                if claims.exp > now {
                    return Ok(claims);
                }

                // Expired: drop the stale entry so it doesn't keep getting
                // hit (and treated as a cache "hit") on every subsequent call.
                cache.pop(token);
            }
        }

        let header = decode_header(token)?;

        let kid = header.kid.clone().unwrap_or_default();
        let jwks = self.jwks.load();
        let key = jwks.get(&kid).ok_or(jwt::Error::MissingKey)?;

        let token_data = decode::<JwtClaims>(token, key, &self.validation)?;
        let claims = token_data.claims;

        {
            let mut cache = self.cache.lock().await;
            cache.put(token.to_string(), claims.clone());
        }

        Ok(claims)
    }

    pub fn extract_claims(claims: &JwtClaims) -> AuthenticatedUser {
        AuthenticatedUser {
            sub: claims.sub.clone(),
            tenant_id: claims.tid.clone(),
            roles: claims.roles.clone(),
            permissions: claims.permissions.clone(),
            auth_method: super::AuthMethod::Jwt,
        }
    }

    pub fn check_permissions(claims: &JwtClaims, required: &[&str]) -> bool {
        required.iter().all(|r| claims.permissions.iter().any(|p| p == r) || claims.roles.iter().any(|p| p == r))
    }

    pub async fn update_jwks(&self, jwks_map: HashMap<String, DecodingKey>) {
        self.jwks.store(Arc::new(jwks_map));
    }

    pub fn validation(&self) -> &Validation {
        &self.validation
    }
}

pub mod jwt {
    use thiserror::Error;

    #[derive(Debug, Error)]
    pub enum Error {
        #[error("JWT validation failed: {0}")]
        Validation(#[from] jsonwebtoken::errors::Error),
        #[error("Missing or invalid JWKS key")]
        MissingKey,
        #[error("Token expired")]
        Expired,
        #[error("Invalid audience")]
        InvalidAudience,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_claims(exp: usize) -> JwtClaims {
        JwtClaims {
            sub: "user-1".to_string(),
            tid: Some("tenant-1".to_string()),
            roles: vec![],
            permissions: vec![],
            exp,
            iat: 0,
            jti: None,
            iss: None,
            aud: None,
        }
    }

    fn now_secs() -> usize {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs() as usize
    }

    /// A cache entry whose `exp` is still in the future must be served
    /// straight from cache.
    #[tokio::test]
    async fn cache_hit_returns_claims_when_not_expired() {
        let validator = JwtValidator::new(None, None);
        let token = "not-a-real-jwt-but-only-used-as-a-cache-key";
        let claims = sample_claims(now_secs() + 3600);

        {
            let mut cache = validator.cache.lock().await;
            cache.put(token.to_string(), claims.clone());
        }

        let result = validator.validate_token(token).await;
        assert!(result.is_ok(), "expected a cache hit for a non-expired entry");
        assert_eq!(result.unwrap().sub, "user-1");
    }

    /// SECURITY REGRESSION TEST: a cache entry whose `exp` has already
    /// passed must NOT be served from cache — this is the bug described in
    /// docs/architecture-security-review-2026-07-31.md §3.4. Since the
    /// cache key here isn't a real JWT, falling through past the cache
    /// necessarily hits `decode_header` and fails — which is exactly what
    /// we want to observe: the expired entry was not trusted.
    #[tokio::test]
    async fn expired_cache_entry_is_not_trusted() {
        let validator = JwtValidator::new(None, None);
        let token = "not-a-real-jwt-but-only-used-as-a-cache-key";
        let expired_claims = sample_claims(now_secs().saturating_sub(3600));

        {
            let mut cache = validator.cache.lock().await;
            cache.put(token.to_string(), expired_claims);
        }

        let result = validator.validate_token(token).await;
        assert!(
            result.is_err(),
            "an expired cache entry must not be returned as valid — it should \
             fall through to real re-validation (which fails here because the \
             cache key isn't a real JWT), not be trusted as-is"
        );
    }

    /// After an expired hit, the stale entry should be evicted from the
    /// cache rather than lingering.
    #[tokio::test]
    async fn expired_cache_entry_is_evicted_after_hit() {
        let validator = JwtValidator::new(None, None);
        let token = "not-a-real-jwt-but-only-used-as-a-cache-key";
        let expired_claims = sample_claims(now_secs().saturating_sub(3600));

        {
            let mut cache = validator.cache.lock().await;
            cache.put(token.to_string(), expired_claims);
        }

        let _ = validator.validate_token(token).await;

        let mut cache = validator.cache.lock().await;
        assert!(
            cache.get(token).is_none(),
            "expired entry should have been popped from the cache"
        );
    }
}
