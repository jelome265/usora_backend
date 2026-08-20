//! Prometheus metrics for usora-document-processor.
//!
//! PRE-EXISTING GAP, found and fixed while writing this service's Helm
//! chart: `prometheus = "0.13"` was already declared in Cargo.toml and
//! `podAnnotations.prometheus.io/scrape: "true"` was already set in the
//! Helm chart's values.yaml, but nothing anywhere in `src/` ever
//! registered a metric or exposed an HTTP endpoint to scrape — a
//! `prometheus.io/scrape: "true"` annotation with no listener behind it
//! just means every scrape attempt against this service silently fails
//! forever, with no error visible anywhere. This module and the `/metrics`
//! route in `routes/mod.rs` close that gap for real, not just an "add the
//! dependency" placeholder.

use lazy_static::lazy_static;
use prometheus::{
    register_histogram_vec, register_int_counter_vec, HistogramVec, IntCounterVec, Registry,
    TextEncoder,
};

lazy_static! {
    pub static ref REGISTRY: Registry = Registry::new();

    /// Documents successfully or unsuccessfully processed via the Kafka
    /// consumer path, labeled by outcome ("success"/"failure").
    pub static ref DOCUMENTS_PROCESSED_TOTAL: IntCounterVec = register_int_counter_vec!(
        "usora_document_processor_documents_processed_total",
        "Total documents processed via the Kafka consumer, by outcome",
        &["outcome"]
    )
    .expect("metric registration must not fail at startup");

    /// End-to-end document processing latency, from Kafka message receipt
    /// to result publish.
    pub static ref DOCUMENT_PROCESSING_DURATION_SECONDS: HistogramVec = register_histogram_vec!(
        "usora_document_processor_processing_duration_seconds",
        "Document processing duration in seconds",
        &["outcome"],
        vec![0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0]
    )
    .expect("metric registration must not fail at startup");

    /// Kafka messages consumed, labeled by outcome, so a stalled or
    /// error-looping consumer is visible without reading logs.
    pub static ref KAFKA_MESSAGES_TOTAL: IntCounterVec = register_int_counter_vec!(
        "usora_document_processor_kafka_messages_total",
        "Total Kafka messages consumed from the tasks topic, by outcome",
        &["outcome"]
    )
    .expect("metric registration must not fail at startup");

    /// REST API requests, labeled by route and status class, so the
    /// unauthenticated-surface finding fixed alongside this module has a
    /// visible signal if auth rejections spike unexpectedly.
    pub static ref REST_REQUESTS_TOTAL: IntCounterVec = register_int_counter_vec!(
        "usora_document_processor_rest_requests_total",
        "Total REST API requests, by route and status class",
        &["route", "status_class"]
    )
    .expect("metric registration must not fail at startup");
}

/// Registers every metric above with the shared registry. Call once at
/// startup, before the REST server starts accepting scrape requests.
pub fn init() {
    let _ = REGISTRY.register(Box::new(DOCUMENTS_PROCESSED_TOTAL.clone()));
    let _ = REGISTRY.register(Box::new(DOCUMENT_PROCESSING_DURATION_SECONDS.clone()));
    let _ = REGISTRY.register(Box::new(KAFKA_MESSAGES_TOTAL.clone()));
    let _ = REGISTRY.register(Box::new(REST_REQUESTS_TOTAL.clone()));
    // Process-level metrics (CPU, RSS, fd count) via the "process" feature
    // enabled in Cargo.toml — otherwise that feature flag is also inert.
    let _ = REGISTRY.register(Box::new(
        prometheus::process_collector::ProcessCollector::for_self(),
    ));
}

/// Renders the current state of every registered metric in the Prometheus
/// text exposition format.
pub fn render() -> Result<String, prometheus::Error> {
    let metric_families = REGISTRY.gather();
    let encoder = TextEncoder::new();
    let mut buffer = Vec::new();
    encoder.encode(&metric_families, &mut buffer)?;
    String::from_utf8(buffer).map_err(|e| prometheus::Error::Msg(e.to_string()))
}
