use rustls::{Certificate, ServerConfig};
use std::sync::Arc;

use super::AuthenticatedUser;

pub struct MtlsValidator {
    ca_cert_path: String,
    ca_certs: Vec<Certificate>,
}

impl MtlsValidator {
    pub fn new(ca_cert_path: &str) -> anyhow::Result<Self> {
        let ca_certs = rustls_pemfile::certs(&mut std::fs::File::open(ca_cert_path)?)
            .collect::<Result<Vec<_>, _>>()?
            .into_iter()
            .map(Certificate)
            .collect();

        Ok(Self {
            ca_cert_path: ca_cert_path.to_string(),
            ca_certs,
        })
    }

    pub fn client_verifier(&self) -> anyhow::Result<Arc<rustls::server::ClientCertVerified>> {
        let mut root_store = rustls::RootCertStore::empty();
        for cert in &self.ca_certs {
            root_store.add(cert)?;
        }

        Ok(rustls::server::WebPkiClientVerifier::builder(Arc::new(root_store)).build()?)
    }

    pub fn extract_client_certificate(
        peer_certs: &[Certificate],
    ) -> anyhow::Result<AuthenticatedUser> {
        if peer_certs.is_empty() {
            return Err(anyhow::anyhow!("no client certificate provided"));
        }

        let der = &peer_certs[0].0;
        let cn = extract_cn_from_der(der).unwrap_or_else(|| "unknown".to_string());
        let tenant_id = extract_san_from_der(der).or_else(|| Some(cn.clone()));

        Ok(AuthenticatedUser {
            sub: cn,
            tenant_id,
            roles: vec!["client".to_string()],
            permissions: vec![],
            auth_method: super::AuthMethod::Mtls,
        })
    }
}

fn extract_cn_from_der(der: &[u8]) -> Option<String> {
    let Ok(pem) = std::str::from_utf8(der) else {
        return None;
    };
    if pem.contains("CN=") {
        let start = pem.find("CN=")?;
        let end = pem[start..].find(|c: char| c == ',' || c == '/' || c == '\n')?;
        Some(pem[start + 3..start + end].trim().to_string())
    } else {
        None
    }
}

fn extract_san_from_der(der: &[u8]) -> Option<String> {
    let Ok(pem) = std::str::from_utf8(der) else {
        return None;
    };
    if pem.contains("DNS:") {
        let start = pem.find("DNS:")?;
        let rest = &pem[start + 4..];
        let end = rest.find(|c: char| c == ',' || c == ' ' || c == '\n').unwrap_or(rest.len());
        let dns = rest[..end].trim();
        let parts: Vec<&str> = dns.split('.').collect();
        if !parts.is_empty() {
            return Some(parts[0].to_string());
        }
    }
    None
}
