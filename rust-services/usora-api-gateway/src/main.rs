use axum_server::tls_rustls::RustlsConfig;
use std::net::SocketAddr;
use std::sync::Arc;
use tracing_subscriber::EnvFilter;

use usora_api_gateway::config::Config;
use usora_api_gateway::routes;
use usora_api_gateway::AppState;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .json()
        .init();

    let config = Config::from_env()?;
    let state = Arc::new(AppState::new(config.clone()).await?);
    config.log_effective_security_summary(state.redis.is_some());

    let app = routes::create_router(state.clone());

    let grpc_addr = config.grpc_bind_address.clone();
    let grpc_state = state.clone();
    tokio::spawn(async move {
        if let Err(e) = serve_grpc(grpc_state, &grpc_addr).await {
            tracing::error!("gRPC server error: {e}");
        }
    });

    tracing::info!(
        "usora-api-gateway starting HTTPS on {}",
        config.bind_address
    );

    let tls_config = config.load_tls_config()?;
    let rustls_config = RustlsConfig::from_config(Arc::new(tls_config));
    let addr: SocketAddr = config.bind_address.parse()?;

    let handle = axum_server::Handle::new();
    let shutdown_handle = handle.clone();
    tokio::spawn(async move {
        shutdown_signal().await;
        shutdown_handle.graceful_shutdown(Some(std::time::Duration::from_secs(30)));
    });

    // Serve with real peer-address info attached to each request
    // (ConnectInfo<SocketAddr>) so downstream middleware — notably the rate
    // limiter — can key on the actual TCP peer instead of a client-supplied,
    // trivially spoofable header. See middleware/rate_limit.rs.
    axum_server::bind_rustls(addr, rustls_config)
        .handle(handle)
        .serve(app.into_make_service_with_connect_info::<SocketAddr>())
        .await?;

    Ok(())
}

async fn shutdown_signal() {
    let ctrl_c = async {
        tokio::signal::ctrl_c()
            .await
            .expect("failed to install SIGINT handler");
    };

    #[cfg(unix)]
    let term = async {
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("failed to install SIGTERM handler")
            .recv()
            .await;
    };

    #[cfg(not(unix))]
    let term = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = term => {},
    }
}

async fn serve_grpc(state: Arc<AppState>, address: &str) -> anyhow::Result<()> {
    let addr: SocketAddr = address.parse()?;

    let (mut health_reporter, health_service) = tonic_health::server::health_reporter();

    // AVAILABILITY (F-005): previously this unconditionally called
    // set_serving() here regardless of whether the gateway had ever
    // successfully loaded a JWKS key set -- meaning a pod that came up
    // with zero verification keys (e.g. identity-service unreachable at
    // startup) still reported itself SERVING to Kubernetes' readiness
    // probe (see infrastructure/helm/usora-gateway/values.yaml, which
    // points both readiness and liveness at this same gRPC health
    // service). Kubernetes would then keep routing real traffic to a
    // replica that rejects every single authenticated request --
    // "healthy but useless" is worse than "not ready yet", since the
    // latter at least gives Kubernetes a reason to stop sending traffic
    // and, during a rolling restart, to hold the previous good replica in
    // service instead.
    //
    // Report NOT_SERVING until jwt_validator confirms at least one JWKS
    // load has actually succeeded (AppState::new attempts this
    // synchronously before we ever get here, but a slow/unreachable
    // identity-service at startup means that fetch can still be
    // in-flight or have already failed by the time this task runs).
    // Once ready, this never reports NOT_SERVING again for JWKS
    // staleness alone -- a transient refresh failure correctly keeps
    // using the last-known-good key set (see jwks_client.rs) rather than
    // being treated as an outage; flapping readiness for that would be
    // wrong, not more correct.
    health_reporter
        .set_not_serving::<usora_api_gateway::proto::gateway::gateway_service_server::GatewayServiceServer<usora_api_gateway::gateway_service::GatewayServiceImpl>>()
        .await;

    let ready_validator = state.jwt_validator.clone();
    let mut ready_reporter = health_reporter.clone();
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_millis(250));
        loop {
            interval.tick().await;
            if ready_validator.is_ready() {
                ready_reporter
                    .set_serving::<usora_api_gateway::proto::gateway::gateway_service_server::GatewayServiceServer<usora_api_gateway::gateway_service::GatewayServiceImpl>>()
                    .await;
                tracing::info!(
                    "JWKS loaded -- reporting SERVING on the gRPC health service (readiness/liveness probes)"
                );
                break;
            }
        }
    });

    let gateway_service =
        usora_api_gateway::proto::gateway::gateway_service_server::GatewayServiceServer::new(
            usora_api_gateway::gateway_service::GatewayServiceImpl::new(state),
        );

    tracing::info!("internal gRPC server starting on {address}");

    tonic::transport::Server::builder()
        .add_service(health_service)
        .add_service(gateway_service)
        .serve(addr)
        .await?;

    Ok(())
}
