pub mod jwks_client;
pub mod jwt;
pub mod mtls;
pub mod oauth;

use serde::{Deserialize, Serialize};
use std::fmt;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuthenticatedUser {
    pub sub: String,
    pub tenant_id: Option<String>,
    pub roles: Vec<String>,
    pub permissions: Vec<String>,
    pub auth_method: AuthMethod,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum AuthMethod {
    Jwt,
    Mtls,
    OAuth,
    None,
}

impl Default for AuthMethod {
    fn default() -> Self {
        Self::None
    }
}

impl fmt::Display for AuthMethod {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            AuthMethod::Jwt => write!(f, "jwt"),
            AuthMethod::Mtls => write!(f, "mtls"),
            AuthMethod::OAuth => write!(f, "oauth"),
            AuthMethod::None => write!(f, "none"),
        }
    }
}

impl AuthenticatedUser {
    pub fn new(sub: String, auth_method: AuthMethod) -> Self {
        Self {
            sub,
            tenant_id: None,
            roles: Vec::new(),
            permissions: Vec::new(),
            auth_method,
        }
    }

    pub fn has_role(&self, role: &str) -> bool {
        self.roles.iter().any(|r| r == role)
    }

    pub fn has_permission(&self, perm: &str) -> bool {
        self.permissions.iter().any(|p| p == perm)
    }
}
