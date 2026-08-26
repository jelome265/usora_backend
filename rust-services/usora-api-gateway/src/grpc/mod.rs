use crate::config::Config;
use crate::proto;
use tonic::codegen::InterceptedService;
use tonic::service::Interceptor;
use tonic::transport::{Certificate, Channel, ClientTlsConfig};

/// Attaches `authorization: Bearer <token>` to every outbound call when a
/// token is configured, so the orchestrator/compute upstreams can at least
/// authenticate that a call came from this gateway rather than any host
/// that can reach their gRPC port. A no-op (does nothing to the request)
/// when no token is configured, so this can be applied unconditionally
/// without branching the client types on whether auth is enabled.
#[derive(Clone)]
struct BearerAuth(Option<std::sync::Arc<str>>);

impl Interceptor for BearerAuth {
    fn call(&mut self, mut req: tonic::Request<()>) -> Result<tonic::Request<()>, tonic::Status> {
        if let Some(token) = &self.0 {
            let value = format!("Bearer {token}")
                .parse()
                .map_err(|_| tonic::Status::internal("invalid internal service token"))?;
            req.metadata_mut().insert("authorization", value);
        }
        Ok(req)
    }
}

type AuthedChannel = InterceptedService<Channel, BearerAuth>;

#[derive(Clone)]
pub struct GrpcClients {
    pub identity: proto::identity::identity_service_client::IdentityServiceClient<AuthedChannel>,
    pub document: proto::document::document_analysis_service_client::DocumentAnalysisServiceClient<AuthedChannel>,
    pub tenant: proto::tenant::tenant_service_client::TenantServiceClient<AuthedChannel>,
    pub audit: proto::audit::audit_service_client::AuditServiceClient<AuthedChannel>,
    pub compliance: proto::compliance::compliance_service_client::ComplianceServiceClient<AuthedChannel>,
    pub notification: proto::notification::notification_service_client::NotificationServiceClient<AuthedChannel>,
    orchestrator_authority: String,
    compute_authority: String,
}

impl GrpcClients {
    pub async fn connect(config: &Config) -> anyhow::Result<Self> {
        // SECURITY: these URLs default to "https://", but nothing here ever
        // called .tls_config() on the Endpoint -- tonic's transport doesn't
        // turn on TLS just because the URL scheme says https, you have to
        // explicitly configure it. So despite the URL, this channel was
        // never actually TLS-protected; it was either plaintext to
        // whatever was listening, or silently failed to connect at all,
        // depending on the upstream. If UPSTREAM_TLS_CA_PATH is configured,
        // build a real TLS-verified endpoint against that CA (this
        // platform's internal CA, per the same cert-manager setup already
        // used for the gateway's own inbound TLS -- see
        // infrastructure/helm/usora-gateway's tls.caCert). If it's not
        // configured, behavior is unchanged from before this fix.
        let orch_endpoint = Self::build_endpoint(&config.upstream.orchestrator_url, config.upstream.tls_ca_path.as_deref())?;
        let comp_endpoint = Self::build_endpoint(&config.upstream.compute_url, config.upstream.tls_ca_path.as_deref())?;

        let orch_channel = orch_endpoint.connect_lazy();
        let comp_channel = comp_endpoint.connect_lazy();

        let orchestrator_authority = Self::authority_of(&config.upstream.orchestrator_url)?;
        let compute_authority = Self::authority_of(&config.upstream.compute_url)?;

        let bearer = BearerAuth(config.upstream.internal_service_token.as_ref().map(|t| t.as_str().into()));

        let identity = proto::identity::identity_service_client::IdentityServiceClient::with_interceptor(orch_channel.clone(), bearer.clone());
        let document = proto::document::document_analysis_service_client::DocumentAnalysisServiceClient::with_interceptor(comp_channel.clone(), bearer.clone());
        let tenant = proto::tenant::tenant_service_client::TenantServiceClient::with_interceptor(orch_channel.clone(), bearer.clone());
        let audit = proto::audit::audit_service_client::AuditServiceClient::with_interceptor(orch_channel.clone(), bearer.clone());
        let compliance = proto::compliance::compliance_service_client::ComplianceServiceClient::with_interceptor(comp_channel.clone(), bearer.clone());
        let notification = proto::notification::notification_service_client::NotificationServiceClient::with_interceptor(comp_channel.clone(), bearer);

        Ok(Self {
            identity,
            document,
            tenant,
            audit,
            compliance,
            notification,
            orchestrator_authority,
            compute_authority,
        })
    }

    /// Builds a Channel Endpoint, wiring real TLS server-certificate
    /// verification against `ca_path` when one is configured. Falls back to
    /// tonic's default (unconfigured, no TLS backend attached) Endpoint
    /// otherwise -- this deliberately preserves whatever the previous,
    /// unconfigured behavior was for anyone not yet using
    /// UPSTREAM_TLS_CA_PATH, rather than silently changing behavior for an
    /// environment I can't confirm is ready for it.
    fn build_endpoint(url: &str, ca_path: Option<&str>) -> anyhow::Result<tonic::transport::Endpoint> {
        let endpoint = Channel::from_shared(url.to_string())?;
        match ca_path {
            Some(path) => {
                let ca_pem = std::fs::read(path)
                    .map_err(|e| anyhow::anyhow!("failed to read UPSTREAM_TLS_CA_PATH ({path}): {e}"))?;
                let tls = ClientTlsConfig::new().ca_certificate(Certificate::from_pem(ca_pem));
                Ok(endpoint.tls_config(tls)?)
            }
            None => Ok(endpoint),
        }
    }

    /// Extracts "host:port" from a gRPC target URL (e.g. "http://host:1234")
    /// for use with a raw TCP connectivity check.
    fn authority_of(url: &str) -> anyhow::Result<String> {
        let uri: axum::http::Uri = url.parse()?;
        let host = uri
            .host()
            .ok_or_else(|| anyhow::anyhow!("URL has no host: {url}"))?;
        let port = uri
            .port_u16()
            .unwrap_or(if uri.scheme_str() == Some("https") { 443 } else { 80 });
        Ok(format!("{host}:{port}"))
    }

    pub async fn check_orchestrator_health(&self) -> bool {
        Self::tcp_reachable(&self.orchestrator_authority).await
    }

    pub async fn check_compute_health(&self) -> bool {
        Self::tcp_reachable(&self.compute_authority).await
    }

    /// Checks real network reachability via a raw TCP connect attempt with a
    /// short timeout, rather than tower::Service::poll_ready. poll_ready on
    /// a lazily-connected Channel reports "ready" as soon as its background
    /// worker can accept a request into its buffer, regardless of whether
    /// the underlying TCP connection has ever actually been attempted or has
    /// failed -- so it can never actually detect a down backend once
    /// connect_lazy() is in use.
    async fn tcp_reachable(authority: &str) -> bool {
        matches!(
            tokio::time::timeout(
                std::time::Duration::from_secs(2),
                tokio::net::TcpStream::connect(authority),
            )
            .await,
            Ok(Ok(_))
        )
    }

    pub async fn check_health(&self) -> bool {
        self.check_orchestrator_health().await && self.check_compute_health().await
    }
}
