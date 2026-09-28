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
    pub jwt_validator: auth::jwt::JwtValidator,
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

        // SECURITY: this closes the gap where update_jwks() was dead code
        // and every JwtValidator was constructed with no keys and no
        // issuer/audience enforcement (JwtValidator::new(None, None)) -- see
        // auth/jwt.rs and middleware/auth.rs history. One shared validator
        // is constructed here and threaded through AuthLayer and the gRPC
        // validate_token RPC via AppState, instead of each call site
        // building its own empty one.
        //
        // F-004: audience is now always enforced. identity-service issues
        // every token with the same stable service audience regardless of
        // grant type (see DomainService::SERVICE_AUDIENCE and
        // SecurityConfig::tokenCustomizer), so there is no longer a reason
        // to treat an empty configured value as "skip the check" -- doing
        // that historically meant a valid signature and issuer alone were
        // sufficient to authenticate to this gateway, with no check at all
        // that the token was actually meant for this API. IdentityConfig's
        // default is a non-empty "usora-api", so this only becomes `None`
        // if an operator explicitly blanks out JWT_AUDIENCE, which is not a
        // supported configuration.
        let audience = if cfg.identity.audience.is_empty() {
            tracing::warn!(
                "JWT_AUDIENCE is empty -- audience validation is DISABLED. \
                 This is not a supported configuration outside of local \
                 development; set JWT_AUDIENCE to identity-service's issued \
                 audience (default \"usora-api\") in every other environment."
            );
            None
        } else {
            Some(cfg.identity.audience.clone())
        };
        let jwt_validator =
            auth::jwt::JwtValidator::new(Some(cfg.identity.issuer.clone()), audience);

        let http_client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(10))
            .build()?;

        // Attempt an initial synchronous fetch so a fresh gateway comes up
        // already able to validate tokens rather than rejecting everything
        // until the first background refresh tick. If identity-service is
        // briefly unreachable, though, we deliberately do NOT abort startup
        // here -- consistent with how Redis/Kafka are handled just above,
        // an external dependency being briefly unavailable degrades rather
        // than takes the whole gateway down. This still fails *closed* on
        // security: with an empty key set every token is rejected with
        // MissingKey (see JwtValidator::validate_token) until either the
        // background refresh task (spawned below) succeeds or the gateway
        // is restarted, so no forged/unverifiable token can ever be
        // accepted -- the failure mode is "gateway rejects everyone" rather
        // than "gateway accepts everyone", which is what actually matters.
        match auth::jwks_client::fetch_jwks(&http_client, &cfg.identity.jwks_url).await {
            Ok(keys) => {
                let count = keys.len();
                jwt_validator.update_jwks(keys).await;
                tracing::info!(
                    key_count = count,
                    "loaded JWKS from identity-service at startup"
                );
            }
            Err(e) => {
                tracing::error!(
                    error = %e,
                    "failed to load JWKS from identity-service at startup -- starting with zero \
                     verification keys, so ALL tokens will be rejected until the background refresh \
                     succeeds. This is a fail-closed degraded state, not a healthy one; check \
                     identity-service availability and IDENTITY_JWKS_URL."
                );
            }
        }

        auth::jwks_client::spawn_refresh_task(
            jwt_validator.clone(),
            http_client,
            cfg.identity.jwks_url.clone(),
            cfg.identity.jwks_refresh_secs,
        );

        Ok(Self {
            config: cfg,
            grpc_clients,
            redis,
            kafka,
            jwt_validator,
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
