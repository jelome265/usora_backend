use crate::config::Config;
use crate::proto;
use tonic::transport::Channel;

#[derive(Clone)]
pub struct GrpcClients {
    pub identity: proto::identity::identity_service_client::IdentityServiceClient<Channel>,
    pub document: proto::document::document_analysis_service_client::DocumentAnalysisServiceClient<Channel>,
    pub tenant: proto::tenant::tenant_service_client::TenantServiceClient<Channel>,
    pub audit: proto::audit::audit_service_client::AuditServiceClient<Channel>,
    pub compliance: proto::compliance::compliance_service_client::ComplianceServiceClient<Channel>,
    pub notification: proto::notification::notification_service_client::NotificationServiceClient<Channel>,
    orchestrator_authority: String,
    compute_authority: String,
}

impl GrpcClients {
    pub async fn connect(config: &Config) -> anyhow::Result<Self> {
        let orch_channel = Channel::from_shared(config.upstream.orchestrator_url.clone())?
            .connect_lazy();

        let comp_channel = Channel::from_shared(config.upstream.compute_url.clone())?
            .connect_lazy();

        let orchestrator_authority = Self::authority_of(&config.upstream.orchestrator_url)?;
        let compute_authority = Self::authority_of(&config.upstream.compute_url)?;

        let identity = proto::identity::identity_service_client::IdentityServiceClient::new(orch_channel.clone());
        let document = proto::document::document_analysis_service_client::DocumentAnalysisServiceClient::new(comp_channel.clone());
        let tenant = proto::tenant::tenant_service_client::TenantServiceClient::new(orch_channel.clone());
        let audit = proto::audit::audit_service_client::AuditServiceClient::new(orch_channel.clone());
        let compliance = proto::compliance::compliance_service_client::ComplianceServiceClient::new(comp_channel.clone());
        let notification = proto::notification::notification_service_client::NotificationServiceClient::new(comp_channel.clone());

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
