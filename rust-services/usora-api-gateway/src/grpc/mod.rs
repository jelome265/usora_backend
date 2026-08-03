use std::sync::Arc;
use crate::config::Config;
use crate::proto;
use tokio::sync::RwLock;
use tonic::transport::Channel;

#[derive(Clone)]
pub struct GrpcClients {
    pub identity: proto::identity::identity_service_client::IdentityServiceClient<Channel>,
    pub document: proto::document::document_analysis_service_client::DocumentAnalysisServiceClient<Channel>,
    pub tenant: proto::tenant::tenant_service_client::TenantServiceClient<Channel>,
    pub audit: proto::audit::audit_service_client::AuditServiceClient<Channel>,
    pub compliance: proto::compliance::compliance_service_client::ComplianceServiceClient<Channel>,
    pub notification: proto::notification::notification_service_client::NotificationServiceClient<Channel>,
    orchestrator_channel: Arc<RwLock<Channel>>,
    compute_channel: Arc<RwLock<Channel>>,
}

impl GrpcClients {
    pub async fn connect(config: &Config) -> anyhow::Result<Self> {
        let orch_channel = Channel::from_shared(config.upstream.orchestrator_url.clone())?
            .connect_lazy();

        let comp_channel = Channel::from_shared(config.upstream.compute_url.clone())?
            .connect_lazy();

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
            orchestrator_channel: Arc::new(RwLock::new(orch_channel)),
            compute_channel: Arc::new(RwLock::new(comp_channel)),
        })
    }

    pub async fn check_orchestrator_health(&self) -> bool {
        Self::channel_is_ready(&self.orchestrator_channel).await
    }

    pub async fn check_compute_health(&self) -> bool {
        Self::channel_is_ready(&self.compute_channel).await
    }

    /// Checks whether a channel is ready to accept requests via
    /// tower::Service::poll_ready, rather than trying to re-establish a
    /// connection (tonic's Channel manages reconnection internally and
    /// has no public "reconnect" method -- the previous code called
    /// .connect() on the Channel itself, which doesn't exist as a method
    /// on that type and never compiled).
    async fn channel_is_ready(channel: &Arc<RwLock<Channel>>) -> bool {
        use tower_service::Service;
        let mut svc = channel.read().await.clone();
        std::future::poll_fn(|cx| svc.poll_ready(cx)).await.is_ok()
    }

    pub async fn check_health(&self) -> bool {
        self.check_orchestrator_health().await && self.check_compute_health().await
    }
}
