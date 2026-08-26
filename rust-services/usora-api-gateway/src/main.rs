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
    health_reporter
        .set_serving::<usora_api_gateway::proto::gateway::gateway_service_server::GatewayServiceServer<usora_api_gateway::gateway_service::GatewayServiceImpl>>()
        .await;

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
