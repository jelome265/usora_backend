use std::net::SocketAddr;
use std::sync::Arc;
use tokio::net::TcpListener;
use tokio_rustls::TlsAcceptor;
use tls_listener::TlsListener;
use tracing_subscriber::EnvFilter;

use usora_api_gateway::AppState;
use usora_api_gateway::config::Config;
use usora_api_gateway::routes;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .json()
        .init();

    let config = Config::from_env()?;
    let state = Arc::new(AppState::new(config.clone()).await?);

    let app = routes::create_router(state.clone());

    let grpc_addr = config.grpc_bind_address.clone();
    tokio::spawn(async move {
        if let Err(e) = serve_grpc(&grpc_addr).await {
            tracing::error!("gRPC server error: {e}");
        }
    });

    tracing::info!(
        "usora-api-gateway starting HTTPS on {}",
        config.bind_address
    );

    let tls_config = Arc::new(config.load_tls_config()?);
    let tcp = TcpListener::bind(&config.bind_address).await?;
    let acceptor = TlsAcceptor::from(tls_config);
    let listener = TlsListener::new(acceptor, tcp);

    axum::serve(listener, app.into_make_service())
        .with_graceful_shutdown(shutdown_signal())
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

async fn serve_grpc(address: &str) -> anyhow::Result<()> {
    let addr: SocketAddr = address.parse()?;

    let (mut health_reporter, health_service) = tonic_health::server::health_reporter();
    health_reporter.set_serving("").await;

    tracing::info!("internal gRPC server starting on {address}");

    tonic::transport::Server::builder()
        .add_service(health_service)
        .serve(addr)
        .await?;

    Ok(())
}
