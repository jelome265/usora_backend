use arc_swap::ArcSwap;
use dashmap::DashSet;
use jsonwebtoken::{decode, decode_header, Algorithm, DecodingKey, Validation};
use lru::LruCache;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
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

/// A cache entry pairs the validated claims with the JWKS epoch that was
/// current when validation happened (see `jwks_epoch` on `JwtValidator`).
/// A hit is only trustworthy if the epoch still matches -- otherwise the
/// key set has rotated since this token was verified and the entry must
/// be treated as a miss, forcing full re-validation against the current
/// keys.
#[derive(Debug, Clone)]
struct CachedValidation {
    claims: JwtClaims,
    jwks_epoch: u64,
}

#[derive(Clone)]
pub struct JwtValidator {
    validation: Validation,
    jwks: Arc<ArcSwap<HashMap<String, DecodingKey>>>,
    // SECURITY (F-003): previously keyed by the raw bearer token, which
    // meant every cache entry -- and any heap/core dump of this process --
    // held live, still-valid bearer tokens in the clear. Keyed instead by
    // a SHA-256 fingerprint of the token, which is sufficient to recognize
    // a repeat request without retaining a credential that can be replayed
    // if disclosed.
    cache: Arc<Mutex<LruCache<[u8; 32], CachedValidation>>>,
    // SECURITY (F-003): incremented on every JWKS rotation (see
    // `update_jwks`) so cached validations are automatically invalidated
    // the moment the key set changes, rather than staying trusted until
    // `exp` or LRU eviction regardless of key state.
    jwks_epoch: Arc<AtomicU64>,
    // SECURITY (F-003): explicit revocation list for `jti`s that must stop
    // being accepted before their natural expiry (compromised token,
    // emergency session/user revocation, credential rotation). This is an
    // in-memory, single-instance set -- it does NOT survive a restart and
    // does NOT propagate across gateway replicas. A production-ready
    // implementation needs a shared store (e.g. Redis) so revocation is
    // effective cluster-wide; tracked as an explicit follow-up, not solved
    // here, but this at least gives the gateway a real enforcement point
    // to wire that into.
    revoked_jtis: Arc<DashSet<String>>,
    // SECURITY/AVAILABILITY (F-005): true once at least one JWKS fetch has
    // ever succeeded. A gateway that has never loaded a valid key set
    // rejects every token (correctly, fail-closed) but was previously
    // reported healthy/serving regardless -- letting Kubernetes route
    // real traffic to a replica that could not authenticate anyone. This
    // flag is the source of truth main.rs's gRPC health reporter uses to
    // decide when to start reporting SERVING. It only ever goes false ->
    // true: once a key set has loaded, later refresh failures correctly
    // keep the last-known-good keys in place (see spawn_refresh_task) and
    // must not flip readiness back off for what is normal, recoverable
    // JWKS staleness.
    ready: Arc<AtomicBool>,
    // AVAILABILITY (F-005): wall-clock time of the last successful JWKS
    // load, so an operator/alert can detect a key set that is technically
    // present but has gone stale for far longer than jwks_refresh_secs
    // would ever explain (remediation item 5: alarm on key-set staleness).
    last_jwks_success: Arc<std::sync::Mutex<Option<std::time::Instant>>>,
}

fn fingerprint(token: &str) -> [u8; 32] {
    let mut hasher = Sha256::new();
    hasher.update(token.as_bytes());
    hasher.finalize().into()
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
            jwks_epoch: Arc::new(AtomicU64::new(0)),
            revoked_jtis: Arc::new(DashSet::new()),
            ready: Arc::new(AtomicBool::new(false)),
            last_jwks_success: Arc::new(std::sync::Mutex::new(None)),
        }
    }

    /// Revoke a `jti` immediately, regardless of remaining `exp`. Intended
    /// for emergency session/user revocation and compromised-credential
    /// response. See the `revoked_jtis` field docs for the current
    /// single-instance limitation.
    pub fn revoke_jti(&self, jti: &str) {
        self.revoked_jtis.insert(jti.to_string());
    }

    pub async fn validate_token(&self, token: &str) -> Result<JwtClaims, jwt::Error> {
        let key = fingerprint(token);
        let current_epoch = self.jwks_epoch.load(Ordering::Acquire);

        {
            let mut cache = self.cache.lock().await;
            let cached = cache.get(&key).cloned();

            if let Some(entry) = cached {
                // SECURITY (F-003): a cache hit is trustworthy only if
                // none of exp, JWKS epoch, or revocation state have moved
                // since this token was last verified. Any one of these
                // failing means we fall through to full re-validation
                // rather than returning previously-trusted claims.
                let now = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_secs() as usize)
                    .unwrap_or(usize::MAX);

                let still_current_key_set = entry.jwks_epoch == current_epoch;
                let not_expired = entry.claims.exp > now;
                let not_revoked = entry
                    .claims
                    .jti
                    .as_deref()
                    .map(|jti| !self.revoked_jtis.contains(jti))
                    .unwrap_or(true);

                if still_current_key_set && not_expired && not_revoked {
                    return Ok(entry.claims);
                }

                // Stale on any axis: drop it so it isn't hit again.
                cache.pop(&key);
            }
        }

        let header = decode_header(token)?;

        let kid = header.kid.clone().unwrap_or_default();
        let jwks = self.jwks.load();
        let signing_key = jwks.get(&kid).ok_or(jwt::Error::MissingKey)?;

        let token_data = decode::<JwtClaims>(token, signing_key, &self.validation)?;
        let claims = token_data.claims;

        if let Some(jti) = claims.jti.as_deref() {
            if self.revoked_jtis.contains(jti) {
                return Err(jwt::Error::Revoked);
            }
        }

        {
            let mut cache = self.cache.lock().await;
            cache.put(
                key,
                CachedValidation {
                    claims: claims.clone(),
                    jwks_epoch: current_epoch,
                },
            );
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
        required.iter().all(|r| {
            claims.permissions.iter().any(|p| p == r) || claims.roles.iter().any(|p| p == r)
        })
    }

    pub async fn update_jwks(&self, jwks_map: HashMap<String, DecodingKey>) {
        self.jwks.store(Arc::new(jwks_map));
        // SECURITY (F-003): bump the epoch on every rotation so every
        // cached validation -- even ones for keys that are still
        // present in the new map -- is forced to re-validate at least
        // once against the current key set. This is deliberately
        // coarse (rotate-anything invalidates everything) rather than
        // trying to diff old/new key sets, since a missed diff would
        // silently reintroduce the trust gap this exists to close.
        self.jwks_epoch.fetch_add(1, Ordering::AcqRel);

        // AVAILABILITY (F-005): every call here represents a successful
        // fetch (fetch_jwks errors out on an empty/unparseable document
        // rather than ever calling this with nothing usable -- see
        // jwks_client.rs), so this is always safe to treat as "ready" and
        // "just refreshed", never a reason to go back to not-ready.
        self.ready.store(true, Ordering::Release);
        if let Ok(mut last) = self.last_jwks_success.lock() {
            *last = Some(std::time::Instant::now());
        }
    }

    /// True once at least one JWKS fetch has ever succeeded. See the
    /// `ready` field docs -- this is what main.rs's gRPC health reporter
    /// gates SERVING on.
    pub fn is_ready(&self) -> bool {
        self.ready.load(Ordering::Acquire)
    }

    /// How long it's been since the last successful JWKS load, if there
    /// has ever been one. `None` means no successful load has happened
    /// yet (equivalent to `is_ready() == false`).
    pub fn jwks_age(&self) -> Option<std::time::Duration> {
        self.last_jwks_success.lock().ok()?.map(|t| t.elapsed())
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
        #[error("Token has been revoked")]
        Revoked,
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

    fn sample_claims_with_jti(exp: usize, jti: &str) -> JwtClaims {
        let mut claims = sample_claims(exp);
        claims.jti = Some(jti.to_string());
        claims
    }

    fn now_secs() -> usize {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs() as usize
    }

    async fn seed_cache(validator: &JwtValidator, token: &str, claims: JwtClaims, jwks_epoch: u64) {
        let mut cache = validator.cache.lock().await;
        cache.put(fingerprint(token), CachedValidation { claims, jwks_epoch });
    }

    /// A cache entry whose `exp` is still in the future, at the current
    /// JWKS epoch, and not revoked must be served straight from cache.
    #[tokio::test]
    async fn cache_hit_returns_claims_when_not_expired() {
        let validator = JwtValidator::new(None, None);
        let token = "not-a-real-jwt-but-only-used-as-a-cache-key";
        let claims = sample_claims(now_secs() + 3600);
        seed_cache(&validator, token, claims, 0).await;

        let result = validator.validate_token(token).await;
        assert!(
            result.is_ok(),
            "expected a cache hit for a non-expired entry"
        );
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
        seed_cache(&validator, token, expired_claims, 0).await;

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
        seed_cache(&validator, token, expired_claims, 0).await;

        let _ = validator.validate_token(token).await;

        let mut cache = validator.cache.lock().await;
        assert!(
            cache.get(&fingerprint(token)).is_none(),
            "expired entry should have been popped from the cache"
        );
    }

    /// SECURITY REGRESSION TEST (F-003): a token cached under one JWKS
    /// epoch must NOT be trusted once the key set has rotated, even if
    /// `exp` is still far in the future. The rotated epoch forces a miss,
    /// which then fails for the right reason here (fake token can't
    /// really be decoded) rather than being silently accepted.
    #[tokio::test]
    async fn cache_entry_from_stale_jwks_epoch_is_not_trusted() {
        let validator = JwtValidator::new(None, None);
        let token = "not-a-real-jwt-but-only-used-as-a-cache-key";
        let claims = sample_claims(now_secs() + 3600);
        seed_cache(&validator, token, claims, 0).await;

        // Simulate a JWKS rotation happening after this entry was cached.
        validator.update_jwks(HashMap::new()).await;

        let result = validator.validate_token(token).await;
        assert!(
            result.is_err(),
            "a cache entry from a prior JWKS epoch must not be trusted after rotation"
        );
    }

    /// SECURITY REGRESSION TEST (F-003): a revoked `jti` must be rejected
    /// even while otherwise unexpired and cached under the current epoch.
    #[tokio::test]
    async fn revoked_jti_is_rejected_from_cache() {
        let validator = JwtValidator::new(None, None);
        let token = "not-a-real-jwt-but-only-used-as-a-cache-key";
        let claims = sample_claims_with_jti(now_secs() + 3600, "session-abc");
        seed_cache(&validator, token, claims, 0).await;

        validator.revoke_jti("session-abc");

        let result = validator.validate_token(token).await;
        assert!(
            result.is_err(),
            "a revoked jti must not be served from cache even if unexpired"
        );
    }

    /// The cache must never be keyed by the raw bearer token string —
    /// only by its fingerprint — so a heap inspection of the cache can't
    /// recover a live, replayable credential. The key type is `[u8; 32]`
    /// (a fixed-size SHA-256 digest), which structurally cannot contain
    /// the original token; this test just confirms the fingerprint used
    /// to store and look up an entry is deterministic and differs from
    /// the raw token bytes.
    #[test]
    fn cache_key_is_a_fingerprint_not_the_raw_token() {
        let token = "a-sensitive-bearer-token-value";
        let fp = fingerprint(token);
        assert_ne!(
            fp.as_slice(),
            token.as_bytes(),
            "the cache key must not equal the raw token bytes"
        );
        assert_eq!(
            fp,
            fingerprint(token),
            "fingerprinting must be deterministic"
        );
    }
}
