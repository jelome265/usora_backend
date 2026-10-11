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
            constant_time_eq(computed.as_bytes(), challenge.as_bytes())
        }
        // SECURITY: the "plain" PKCE method (RFC 7636) sends the verifier
        // itself as the challenge, so it provides no protection at all
        // against authorization-code interception -- the entire point of
        // PKCE is defeated. OAuth 2.1 explicitly disallows "plain" for this
        // reason; this gateway only accepts S256. Not currently called
        // anywhere in the codebase, but hardened now so it's safe by
        // default whenever it is wired into a real authorization flow.
        _ => false,
    }
}

/// Constant-time byte comparison to avoid a timing side-channel on the
/// PKCE challenge check (and any other future caller). Short-circuits only
/// on length mismatch, which is not itself sensitive information here.
fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff: u8 = 0;
    for (x, y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}
