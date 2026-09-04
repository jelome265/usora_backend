use std::sync::Arc;
use base64::Engine;
use tokio::signal;
use tokio::sync::Semaphore;
use tonic::transport::Server;
use tracing::{error, info};

use usora_document_processor::generated::usora::document::v1::document_analysis_service_server::DocumentAnalysisServiceServer;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let config = Arc::new(usora_document_processor::config::Config::from_env()?);
    config.validate()?;
    usora_document_processor::utils::init_tracing(&config)?;

    // See metrics.rs — registers the metrics /metrics actually exposes;
    // previously nothing called this and the /metrics endpoint didn't
    // exist at all.
    usora_document_processor::metrics::init();

    info!("Starting {} on {}", config.service_name, config.grpc_bind_address);

    let doc_service = Arc::new(usora_document_processor::grpc::DocumentAnalysisServiceImpl::new(config.clone()));

    let (mut health_reporter, health_service) = tonic_health::server::health_reporter();
    health_reporter.set_serving::<DocumentAnalysisServiceServer<_>>().await;

    let grpc_addr = config.grpc_bind_address.parse()?;

    let reflection_service = tonic_reflection::server::Builder::configure()
        .register_encoded_file_descriptor_set(
            usora_document_processor::generated::usora::document::v1::DOCUMENT_ANALYSIS_SERVICE_FILE_DESCRIPTOR_SET,
        )
        .build()?;

    let kafka_config = config.clone();
    let kafka_handle = tokio::spawn(async move {
        if let Err(e) = run_kafka_consumer(kafka_config).await {
            error!("Kafka consumer exited: {}", e);
        }
    });

    let rest_addr = config.rest_bind_address.clone().unwrap_or_else(|| "0.0.0.0:8081".to_string());
    // config.validate() already guarantees this is non-empty — see the
    // SECURITY check added there — so this expect can only fire if
    // validate() was skipped, which would itself be a bug at the call
    // site above, not a runtime condition to handle gracefully here.
    let auth_state = std::sync::Arc::new(usora_document_processor::auth::AuthState::new(
        config
            .internal_service_jwt_secret
            .as_deref()
            .expect("validate() guarantees internal_service_jwt_secret is set"),
    ));
    let rest_state = usora_document_processor::routes::AppState {
        service: doc_service.clone(),
    };
    let rest_app = usora_document_processor::routes::router(rest_state, auth_state);

    let rest_listener = tokio::net::TcpListener::bind(&rest_addr).await?;
    info!("REST server listening on {}", rest_addr);

    let rest_handle = tokio::spawn(async move {
        axum::serve(rest_listener, rest_app)
            .with_graceful_shutdown(async {
                signal::ctrl_c().await.ok();
            })
            .await
    });

    info!("gRPC server listening on {}", grpc_addr);
    let grpc_future = Server::builder()
        .add_service(health_service)
        .add_service(reflection_service)
        .add_service(DocumentAnalysisServiceServer::new(doc_service.as_ref().clone()))
        .serve_with_shutdown(grpc_addr, async {
            signal::ctrl_c().await.ok();
            info!("Shutdown signal received");
        });

    // RELIABILITY FIX (H3), found while auditing .expect() usage: the
    // REST server previously ran in a spawned task whose JoinHandle was
    // never awaited — only aborted after the gRPC server below returned.
    // If axum::serve() ever errored or panicked, that failure was
    // silently dropped: the REST API (including /metrics and every
    // document-analysis endpoint) would go dark while the gRPC server —
    // and its health-check service, which is what this chart's readiness/
    // liveness probes actually query — kept running and reporting
    // healthy. tokio::select! now awaits both concurrently and treats
    // either one failing as fatal to the whole process, so a REST-server
    // failure surfaces immediately instead of as an invisible partial
    // outage.
    tokio::select! {
        rest_result = rest_handle => {
            match rest_result {
                Ok(Ok(())) => info!("REST server exited cleanly"),
                Ok(Err(e)) => return Err(anyhow::anyhow!("REST server failed: {e}")),
                Err(e) => return Err(anyhow::anyhow!("REST server task panicked: {e}")),
            }
        }
        grpc_result = grpc_future => {
            grpc_result?;
            info!("gRPC server exited cleanly");
        }
    }

    kafka_handle.abort();
    Ok(())
}

async fn run_kafka_consumer(config: Arc<usora_document_processor::config::Config>) -> anyhow::Result<()> {
    use futures::StreamExt;
    use rdkafka::config::ClientConfig;
    use rdkafka::consumer::{Consumer, StreamConsumer};
    use rdkafka::message::Message;
    use rdkafka::producer::{FutureProducer, FutureRecord};
    use rdkafka::util::Timeout;

    let consumer: StreamConsumer = ClientConfig::new()
        .set("group.id", &config.kafka_group_id)
        .set("bootstrap.servers", &config.kafka_brokers)
        .set("enable.auto.commit", "true")
        // RELIABILITY FIX (see config.rs's kafka_dead_letter_topic doc
        // comment for the full finding): with auto.offset.store left at
        // its default (true), rdkafka marks a message's offset as ready
        // to commit the moment it's handed to the consumer stream —
        // before this service has even attempted to process it, let
        // alone succeeded. That meant a crash, panic, or failed
        // processing attempt silently lost the task forever: the offset
        // was already committed, so it was never redelivered, and there
        // was no dead-letter topic to catch it either. Disabling
        // automatic offset storage and calling `store_offset` explicitly
        // — only after a message has either been processed successfully
        // or durably moved to the dead-letter topic — makes redelivery
        // (at-least-once) the failure mode instead of silent data loss.
        .set("enable.auto.offset.store", "false")
        .set("auto.offset.reset", "earliest")
        .set("session.timeout.ms", "6000")
        .set("max.poll.interval.ms", "30000")
        .create()?;

    consumer.subscribe(&[&config.kafka_tasks_topic])?;
    info!("Kafka consumer subscribed to {}", config.kafka_tasks_topic);

    let producer: FutureProducer = ClientConfig::new()
        .set("bootstrap.servers", &config.kafka_brokers)
        .set("message.timeout.ms", "5000")
        .create()?;

    let producer = Arc::new(producer);
    let consumer = Arc::new(consumer);
    let config = Arc::new(config.as_ref().clone());

    let pipeline = usora_document_processor::pipeline::PipelineBuilder::default()
        .with_ingestion()
        .with_preprocessing()
        .with_postprocessing()
        .build();

    let processor = Arc::new(usora_document_processor::DocumentProcessor::new(
        Arc::new(config.clone()),
        pipeline,
    ));

    let mut stream = consumer.stream();
    // F-024: config.max_concurrent_jobs (MAX_CONCURRENT_JOBS) was already
    // parsed and validated (see config/mod.rs) but never actually used
    // anywhere -- every Kafka message spawned an unbounded detached task
    // regardless of this setting, unlike usora-face-matching-engine and
    // usora-risk-scoring-engine, which both correctly gate concurrent
    // spawns on a semaphore built from their own equivalent config value.
    // This service is the most CPU-heavy of the three (OCR/Tesseract/
    // OpenCV), and was the one with NO bound at all: a noisy tenant
    // queuing many messages could spawn effectively unlimited concurrent
    // CPU-bound jobs, exactly the "unbounded worker pool" failure mode
    // this finding describes. Acquiring a permit BEFORE spawning (not
    // inside the spawned task) means an already-saturated worker pool
    // applies backpressure by not polling/acquiring further until a slot
    // frees up, rather than accepting unbounded queued work in memory.
    let semaphore = Arc::new(Semaphore::new(config.max_concurrent_jobs));
    while let Some(msg) = stream.next().await {
        match msg {
            Ok(m) => {
                if let Some(payload) = m.payload() {
                    let payload = payload.to_vec();
                    // Extract owned copies of everything needed to store
                    // the offset later — BorrowedMessage can't outlive
                    // this loop iteration, and processing happens in a
                    // detached task so the consumer keeps polling (a
                    // multi-second OCR/ML job must not block the poll
                    // loop, or the group coordinator sees this consumer
                    // as stalled and triggers a rebalance).
                    let msg_topic = m.topic().to_string();
                    let msg_partition = m.partition();
                    let msg_offset = m.offset();

                    let processor = processor.clone();
                    let producer = producer.clone();
                    let consumer = consumer.clone();
                    let results_topic = config.kafka_results_topic.clone();
                    let dead_letter_topic = config.kafka_dead_letter_topic.clone();
                    let retry_count = config.kafka_retry_count;
                    let retry_backoff = std::time::Duration::from_millis(config.kafka_retry_backoff_ms);

                    // F-024: acquired here, in the poll loop, before
                    // spawning -- NOT inside the spawned task. Acquiring
                    // inside the task would still let every message spawn
                    // a task immediately (just have it wait once running),
                    // which bounds concurrent CPU WORK but not the number
                    // of pending tasks/memory held for messages already
                    // pulled off Kafka; acquiring here means the consumer
                    // itself stops pulling further messages once the pool
                    // is saturated, which is the actual backpressure this
                    // finding's remediation item 4 asks for.
                    let permit = semaphore.clone().acquire_owned().await
                        .expect("semaphore should never be closed for the lifetime of this consumer");

                    tokio::spawn(async move {
                        let _permit = permit;
                        let started = std::time::Instant::now();
                        let mut last_err: Option<anyhow::Error> = None;

                        // RELIABILITY FIX: bounded retry with backoff for
                        // transient failures (a momentary DB blip, a
                        // downstream timeout) before giving up on a
                        // message — previously there was zero retry at
                        // all, so any transient error was treated
                        // identically to a permanently unprocessable
                        // document.
                        let mut attempt = 0u32;
                        let outcome = loop {
                            match processor.process_document(&payload).await {
                                Ok(result) => break Ok(result),
                                Err(e) => {
                                    last_err = Some(e);
                                    if attempt >= retry_count {
                                        break Err(());
                                    }
                                    attempt += 1;
                                    tracing::warn!(
                                        attempt,
                                        max_attempts = retry_count,
                                        "document processing failed, retrying after backoff"
                                    );
                                    tokio::time::sleep(retry_backoff * attempt).await;
                                }
                            }
                        };

                        match outcome {
                            Ok(result) => {
                                usora_document_processor::metrics::DOCUMENTS_PROCESSED_TOTAL
                                    .with_label_values(&["success"])
                                    .inc();
                                usora_document_processor::metrics::DOCUMENT_PROCESSING_DURATION_SECONDS
                                    .with_label_values(&["success"])
                                    .observe(started.elapsed().as_secs_f64());
                                usora_document_processor::metrics::KAFKA_MESSAGES_TOTAL
                                    .with_label_values(&["success"])
                                    .inc();

                                let json = serde_json::to_vec(&result).unwrap_or_default();
                                let record = FutureRecord::to(&results_topic)
                                    .payload(&json)
                                    .key(&uuid::Uuid::now_v7().to_string());
                                if let Err(e) = producer.send(record, Timeout::After(std::time::Duration::from_secs(5))).await {
                                    error!("Failed to send result: {:?}", e);
                                }
                            }
                            Err(()) => {
                                let e = last_err.expect("outcome is Err only when the loop set last_err");
                                usora_document_processor::metrics::DOCUMENTS_PROCESSED_TOTAL
                                    .with_label_values(&["failure"])
                                    .inc();
                                usora_document_processor::metrics::DOCUMENT_PROCESSING_DURATION_SECONDS
                                    .with_label_values(&["failure"])
                                    .observe(started.elapsed().as_secs_f64());
                                usora_document_processor::metrics::KAFKA_MESSAGES_TOTAL
                                    .with_label_values(&["failure"])
                                    .inc();

                                error!(
                                    "Document processing failed after {} attempt(s), routing to dead-letter topic: {}",
                                    attempt + 1,
                                    e
                                );

                                // RELIABILITY FIX: previously this branch
                                // only published a JSON error blob to the
                                // *results* topic and moved on — the
                                // original document payload was gone
                                // forever with no way to inspect or
                                // reprocess it. Publishing the original
                                // payload (base64) plus the error to a
                                // real dead-letter topic makes failed
                                // documents recoverable instead of just
                                // logged-and-lost.
                                let dlq_payload = serde_json::json!({
                                    "error": e.to_string(),
                                    "status": "failed",
                                    "attempts": attempt + 1,
                                    "original_topic": msg_topic,
                                    "original_partition": msg_partition,
                                    "original_offset": msg_offset,
                                    "document_payload_base64": base64::engine::general_purpose::STANDARD.encode(&payload),
                                });
                                let json = serde_json::to_vec(&dlq_payload).unwrap_or_default();
                                let record = FutureRecord::to(&dead_letter_topic)
                                    .payload(&json)
                                    .key(&uuid::Uuid::now_v7().to_string());
                                if let Err(send_err) = producer.send(record, Timeout::After(std::time::Duration::from_secs(5))).await {
                                    // The dead-letter publish itself failed —
                                    // do NOT store the offset in this case.
                                    // Leaving the offset uncommitted means
                                    // this message will be redelivered
                                    // (at-least-once) rather than silently
                                    // dropped, which is the correct failure
                                    // mode when we couldn't even durably
                                    // record the failure.
                                    error!("Failed to publish to dead-letter topic, offset will NOT be stored (message will be redelivered): {:?}", send_err);
                                    return;
                                }
                            }
                        }

                        // Only reached on success, or on failure that was
                        // successfully routed to the dead-letter topic —
                        // both are "durably handled" outcomes, so it's now
                        // safe to let this offset be committed.
                        if let Err(e) = consumer.store_offset(&msg_topic, msg_partition, msg_offset) {
                            error!("Failed to store offset for {}:{}:{}: {:?}", msg_topic, msg_partition, msg_offset, e);
                        }
                    });
                }
            }
            Err(e) => {
                usora_document_processor::metrics::KAFKA_MESSAGES_TOTAL
                    .with_label_values(&["consumer_error"])
                    .inc();
                error!("Kafka consumer error: {}", e);
            }
        }
    }

    Ok(())
}
