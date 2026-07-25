use std::collections::HashMap;
use std::sync::Arc;
use arc_swap::ArcSwap;
use jsonwebtoken::{decode, decode_header, DecodingKey, Validation, Algorithm, Header};
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
            if let Some(claims) = cache.get(token) {
                return Ok(claims.clone());
            }
        }

        let header = decode_header(token).map_err(|e| {
            jwt::Error::from(jsonwebtoken::errors::Error::from(
                std::io::Error::new(std::io::ErrorKind::InvalidData, e.to_string()),
            ))
        })?;

        let kid = header.kid.clone().unwrap_or_default();
        let jwks = self.jwks.load();
        let key = jwks.get(&kid).ok_or_else(|| {
            jwt::Error::from(jsonwebtoken::errors::Error::from(
                std::io::Error::new(std::io::ErrorKind::NotFound, "key not found in JWKS"),
            ))
        })?;

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
