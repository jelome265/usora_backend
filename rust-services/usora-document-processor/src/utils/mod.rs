use anyhow::Result;
use base64::{engine::general_purpose::STANDARD as BASE64, Engine};
use sha2::{Digest, Sha256};
use uuid::Uuid;

pub fn base64_to_image(data: &str) -> Result<Vec<u8>> {
    let decoded = BASE64.decode(data)?;
    Ok(decoded)
}

pub fn image_to_base64(data: &[u8]) -> String {
    BASE64.encode(data)
}

pub fn generate_uuid_v7() -> Uuid {
    Uuid::now_v7()
}

pub fn sha256_hash(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    hex::encode(hasher.finalize())
}

pub fn detect_file_type(data: &[u8]) -> &'static str {
    if data.len() < 4 {
        return "unknown";
    }
    match &data[..4] {
        [0x89, 0x50, 0x4E, 0x47] => "image/png",
        [0xFF, 0xD8, 0xFF, ..] => "image/jpeg",
        [0x49, 0x49, 0x2A, 0x00] | [0x4D, 0x4D, 0x00, 0x2A] => "image/tiff",
        [0x42, 0x4D, ..] => "image/bmp",
        [0x52, 0x49, 0x46, 0x46] => "image/webp",
        [0x25, 0x50, 0x44, 0x46] => "application/pdf",
        _ => "unknown",
    }
}

pub fn format_processing_time(start: std::time::Instant) -> f64 {
    start.elapsed().as_secs_f64() * 1000.0
}

pub fn init_tracing(config: &crate::config::Config) -> Result<()> {
    use tracing_subscriber::EnvFilter;

    let filter = EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| EnvFilter::new("info"));

    tracing_subscriber::fmt()
        .with_env_filter(filter)
        .with_target(true)
        .json()
        .init();

    if let Some(ref endpoint) = config.otlp_endpoint {
        let _ = opentelemetry_otlp::new_pipeline()
            .tracing()
            .with_exporter(opentelemetry_otlp::new_exporter().tonic().with_endpoint(endpoint))
            .with_trace_config(opentelemetry::sdk::trace::config().with_resource(
                opentelemetry::sdk::Resource::new(vec![
                    opentelemetry::KeyValue::new("service.name", config.service_name.clone()),
                ]),
            ))
            .install_batch(opentelemetry::runtime::Tokio);
    }

    Ok(())
}
