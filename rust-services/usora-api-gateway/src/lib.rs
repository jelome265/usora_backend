pub mod auth;
pub mod config;
pub mod grpc;
pub mod handlers;
pub mod middleware;
pub mod models;
pub mod rate_limit;
pub mod routes;
pub mod utils;

pub mod proto {
    pub mod gateway {
        tonic::include_proto!("usora.gateway.v1");
    }
    pub mod identity {
        tonic::include_proto!("usora.identity.v1");
    }
    pub mod document {
        tonic::include_proto!("usora.document.v1");
    }
    pub mod tenant {
        tonic::include_proto!("usora.tenant.v1");
    }
    pub mod audit {
        tonic::include_proto!("usora.audit.v1");
    }
    pub mod compliance {
        tonic::include_proto!("usora.compliance.v1");
    }
    pub mod notification {
        tonic::include_proto!("usora.notification.v1");
    }
}

pub mod gateway_service;

use axum::extract::FromRef;
pub use config::Config;
use grpc::GrpcClients;
use rdkafka::producer::FutureProducer;
use redis::aio::ConnectionManager;
use std::sync::Arc;

#[derive(Clone)]
pub struct AppState {
    pub config: Arc<Config>,
    pub grpc_clients: Arc<GrpcClients>,
    pub redis: Option<ConnectionManager>,
    pub kafka: Option<FutureProducer>,
}

impl AppState {
    pub async fn new(config: Config) -> anyhow::Result<Self> {
        let cfg = Arc::new(config);
        let grpc_clients = Arc::new(GrpcClients::connect(&cfg).await?);

        let redis = if cfg.redis.url.is_empty() {
            None
        } else {
            match redis::Client::open(cfg.redis.url.as_str()) {
                Ok(client) => match ConnectionManager::new(client).await {
                    Ok(conn) => Some(conn),
                    Err(e) => {
                        tracing::warn!(
                            error = %e,
                            "failed to connect to Redis at startup -- falling back to \
                             in-memory rate limiting rather than refusing to start"
                        );
                        None
                    }
                },
                Err(e) => {
                    tracing::warn!(error = %e, "invalid Redis URL -- falling back to in-memory rate limiting");
                    None
                }
            }
        };

        let kafka = if cfg.kafka.brokers.is_empty() {
            None
        } else {
            let producer: FutureProducer = rdkafka::config::ClientConfig::new()
                .set("bootstrap.servers", &cfg.kafka.brokers)
                .set("message.timeout.ms", "5000")
                .create()?;
            Some(producer)
        };

        Ok(Self {
            config: cfg,
            grpc_clients,
            redis,
            kafka,
        })
    }
}

impl FromRef<AppState> for Arc<Config> {
    fn from_ref(state: &AppState) -> Self {
        state.config.clone()
    }
}

impl FromRef<AppState> for Arc<GrpcClients> {
    fn from_ref(state: &AppState) -> Self {
        state.grpc_clients.clone()
    }
}
