use std::sync::Arc;
use crate::config::Config;
use crate::proto;
use tokio::sync::RwLock;
use tonic::transport::Channel;

#[derive(Clone)]
pub struct GrpcClients {
    pub identity: proto::identity::IdentityServiceClient<Channel>,
    pub document: proto::document::DocumentServiceClient<Channel>,
    pub tenant: proto::tenant::TenantServiceClient<Channel>,
    pub audit: proto::audit::AuditServiceClient<Channel>,
    pub compliance: proto::compliance::ComplianceServiceClient<Channel>,
    pub notification: proto::notification::NotificationServiceClient<Channel>,
    orchestrator_channel: Arc<RwLock<Channel>>,
    compute_channel: Arc<RwLock<Channel>>,
}

impl GrpcClients {
    pub async fn connect(config: &Config) -> anyhow::Result<Self> {
        let orch_channel = Channel::from_shared(config.upstream.orchestrator_url.clone())?
            .connect()
            .await
            .map_err(|e| anyhow::anyhow!("failed to connect to orchestrator: {e}"))?;

        let comp_channel = Channel::from_shared(config.upstream.compute_url.clone())?
            .connect()
            .await
            .map_err(|e| anyhow::anyhow!("failed to connect to compute: {e}"))?;

        let identity = proto::identity::IdentityServiceClient::new(orch_channel.clone());
        let document = proto::document::DocumentServiceClient::new(comp_channel.clone());
        let tenant = proto::tenant::TenantServiceClient::new(orch_channel.clone());
        let audit = proto::audit::AuditServiceClient::new(orch_channel.clone());
        let compliance = proto::compliance::ComplianceServiceClient::new(comp_channel.clone());
        let notification = proto::notification::NotificationServiceClient::new(comp_channel.clone());

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
        self.orchestrator_channel
            .read()
            .await
            .connect()
            .await
            .is_ok()
    }

    pub async fn check_compute_health(&self) -> bool {
        self.compute_channel
            .read()
            .await
            .connect()
            .await
            .is_ok()
    }

    pub async fn check_health(&self) -> bool {
        self.check_orchestrator_health().await && self.check_compute_health().await
    }
}
