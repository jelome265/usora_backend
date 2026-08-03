use rustls::pki_types::CertificateDer;
use rustls::server::danger::ClientCertVerifier;
use std::sync::Arc;

use super::AuthenticatedUser;

pub struct MtlsValidator {
    ca_certs: Vec<CertificateDer<'static>>,
}

impl MtlsValidator {
    pub fn new(ca_cert_path: &str) -> anyhow::Result<Self> {
        let mut reader = std::io::BufReader::new(std::fs::File::open(ca_cert_path)?);
        let ca_certs = rustls_pemfile::certs(&mut reader)
            .collect::<Result<Vec<_>, _>>()?;

        Ok(Self { ca_certs })
    }

    pub fn client_verifier(&self) -> anyhow::Result<Arc<dyn ClientCertVerifier>> {
        let mut root_store = rustls::RootCertStore::empty();
        for cert in &self.ca_certs {
            root_store.add(cert.clone())?;
        }

        Ok(rustls::server::WebPkiClientVerifier::builder(Arc::new(root_store)).build()?)
    }

    pub fn extract_client_certificate(
        peer_certs: &[CertificateDer<'static>],
    ) -> anyhow::Result<AuthenticatedUser> {
        if peer_certs.is_empty() {
            return Err(anyhow::anyhow!("no client certificate provided"));
        }

        // SECURITY: parse the certificate properly via x509-parser rather
        // than treating the raw DER bytes as text and searching for a
        // literal "CN=" / "DNS:" substring. DER is a binary ASN.1 encoding
        // — a naive byte-string search is not bound to the actual Subject
        // field structure and can be misled by a crafted certificate that
        // places an unrelated matching substring elsewhere in the DER
        // (e.g. in an extension or the Issuer field), which is especially
        // risky here since client CSR subject fields are often
        // client-controlled even under a trusted CA.
        let (_, cert) = x509_parser::parse_x509_certificate(peer_certs[0].as_ref())
            .map_err(|e| anyhow::anyhow!("failed to parse client certificate: {e}"))?;

        let cn = cert
            .subject()
            .iter_common_name()
            .next()
            .and_then(|cn| cn.as_str().ok())
            .map(str::to_string)
            .unwrap_or_else(|| "unknown".to_string());

        let tenant_id = cert
            .subject_alternative_name()
            .ok()
            .flatten()
            .and_then(|san| {
                san.value.general_names.iter().find_map(|name| {
                    if let x509_parser::extensions::GeneralName::DNSName(dns) = name {
                        dns.split('.').next().map(str::to_string)
                    } else {
                        None
                    }
                })
            })
            .or_else(|| Some(cn.clone()));

        Ok(AuthenticatedUser {
            sub: cn,
            tenant_id,
            roles: vec!["client".to_string()],
            permissions: vec![],
            auth_method: super::AuthMethod::Mtls,
        })
    }
}
