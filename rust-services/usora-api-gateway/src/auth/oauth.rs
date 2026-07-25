use base64::{Engine, engine::general_purpose};
use rand::Rng;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TokenIntrospectionRequest {
    pub token: String,
    pub token_type_hint: Option<String>,
    pub client_id: Option<String>,
    pub client_secret: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TokenIntrospectionResponse {
    pub active: bool,
    pub sub: Option<String>,
    pub tenant_id: Option<String>,
    pub scope: Option<String>,
    pub client_id: Option<String>,
    pub token_type: Option<String>,
    pub exp: Option<i64>,
    pub iat: Option<i64>,
    pub nbf: Option<i64>,
    pub roles: Option<Vec<String>>,
    pub permissions: Option<Vec<String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PkceChallenge {
    pub challenge: String,
    pub challenge_method: String,
    pub verifier: String,
}

pub fn generate_pkce_pair() -> PkceChallenge {
    let verifier: String = (0..43)
        .map(|_| {
            let chars = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
            chars[rand::thread_rng().gen_range(0..chars.len())] as char
        })
        .collect();

    let challenge = {
        let mut hasher = Sha256::new();
        hasher.update(verifier.as_bytes());
        let result = hasher.finalize();
        general_purpose::URL_SAFE_NO_PAD.encode(result)
    };

    PkceChallenge {
        challenge,
        challenge_method: "S256".into(),
        verifier,
    }
}

pub fn verify_pkce(verifier: &str, challenge: &str, method: &str) -> bool {
    match method {
        "S256" => {
            let mut hasher = Sha256::new();
            hasher.update(verifier.as_bytes());
            let result = hasher.finalize();
            let computed = general_purpose::URL_SAFE_NO_PAD.encode(result);
            computed == challenge
        }
        "plain" => verifier == challenge,
        _ => false,
    }
}
