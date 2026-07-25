use anyhow::Result;
use futures::stream::StreamExt;
use opentelemetry::KeyValue;
use opentelemetry_otlp::WithExportConfig;
use rdkafka::config::ClientConfig;
use rdkafka::consumer::{Consumer, StreamConsumer};
use rdkafka::message::Message;
use rdkafka::producer::{FutureProducer, FutureRecord};
use std::sync::Arc;
use tokio::sync::Semaphore;
use tracing::{error, info, warn};
use tracing_subscriber::EnvFilter;
use uuid::Uuid;

use usora_face_matching_engine::config::Config;
use usora_face_matching_engine::detection::face_detector::create_detector;
use usora_face_matching_engine::detection::quality_check::QualityChecker;
use usora_face_matching_engine::embedding::inference::OnnxEmbeddingModel;
use usora_face_matching_engine::grpc::{proto, IdentityVerificationServiceImpl};
use usora_face_matching_engine::liveness::active::ActiveLivenessDetector;
use usora_face_matching_engine::liveness::passive::PassiveLivenessDetector;
use usora_face_matching_engine::matching::one_to_many::FaissMatcher;
use usora_face_matching_engine::matching::one_to_one::CosineMatcher;
use usora_face_matching_engine::FaceMatchingEngine;

#[tokio::main]
async fn main() -> Result<()> {
    let config = Config::from_env();
    init_telemetry(&config)?;

    info!(service = %config.telemetry.service_name, "Starting Face Matching Engine");

    let detector = create_detector(
        &config.models.detection_model_path,
        config.models.input_width,
        config.models.input_height,
        config.models.min_face_size,
        config.models.detection_confidence_threshold,
    )?;

    let embedding_model = Arc::new(OnnxEmbeddingModel::new(
        &config.models.embedding_model_path,
        config.models.embedding_dimension,
        config.models.input_width,
        config.models.input_height,
    )?);

    let quality_checker = Arc::new(QualityChecker::new());
    let active_liveness = Arc::new(ActiveLivenessDetector::new(config.models.liveness_model_path.as_deref())?);
    let passive_liveness = Arc::new(PassiveLivenessDetector::new(config.models.liveness_model_path.as_deref())?);
    let cosine_matcher = Arc::new(CosineMatcher::new(config.matching.one_to_one_threshold));

    let faiss_matcher = Arc::new(FaissMatcher::new(
        &config.faiss,
        config.matching.one_to_many_threshold,
        config.matching.top_k_results,
    )?);

    let engine = Arc::new(FaceMatchingEngine::new(
        detector,
        embedding_model.clone(),
        quality_checker,
        active_liveness,
        passive_liveness,
        cosine_matcher,
        faiss_matcher.clone(),
        config.matching.one_to_one_threshold,
    ));

    let grpc_config = config.grpc.clone();
    let grpc_engine = engine.clone();

    let grpc_handle = tokio::spawn(async move {
        start_grpc_server(grpc_config, grpc_engine).await
    });

    let kafka_config = config.kafka.clone();
    let processing_config = config.processing.clone();
    let kafka_engine = engine.clone();

    let kafka_handle = tokio::spawn(async move {
        start_kafka_consumer(kafka_config, processing_config, kafka_engine).await
    });

    let metrics_config = config.telemetry.clone();
    let metrics_handle = tokio::spawn(async move {
        start_metrics_server(metrics_config.metrics_port).await
    });

    tokio::select! {
        r = grpc_handle => {
            if let Err(e) = r {
                error!(error = %e, "gRPC server failed");
            }
        }
        r = kafka_handle => {
            if let Err(e) = r {
                error!(error = %e, "Kafka consumer failed");
            }
        }
        r = metrics_handle => {
            if let Err(e) = r {
                error!(error = %e, "Metrics server failed");
            }
        }
    }

    Ok(())
}

fn init_telemetry(config: &Config) -> Result<()> {
    if config.telemetry.tracing_enabled {
        if let Some(ref endpoint) = config.telemetry.otlp_endpoint {
            let provider = opentelemetry_otlp::new_pipeline()
                .tracing()
                .with_exporter(
                    opentelemetry_otlp::new_exporter()
                        .tonic()
                        .with_endpoint(endpoint),
                )
                .with_trace_config(
                    opentelemetry::sdk::trace::config().with_resource(
                        opentelemetry::sdk::Resource::new(vec![
                            KeyValue::new("service.name", config.telemetry.service_name.clone()),
                            KeyValue::new("service.version", env!("CARGO_PKG_VERSION")),
                        ]),
                    ),
                )
                .install_batch(opentelemetry::runtime::Tokio)?;

            let tracer = provider.get_tracer(config.telemetry.service_name.clone());
            opentelemetry::global::set_tracer_provider(provider);

            tracing_subscriber::registry()
                .with(tracing_opentelemetry::layer().with_tracer(tracer))
                .with(EnvFilter::new(&config.telemetry.log_level))
                .try_init()
                .ok();
        } else {
            tracing_subscriber::registry()
                .with(EnvFilter::new(&config.telemetry.log_level))
                .try_init()
                .ok();
        }
    } else {
        tracing_subscriber::registry()
            .with(EnvFilter::new(&config.telemetry.log_level))
            .try_init()
            .ok();
    }

    Ok(())
}

async fn start_grpc_server(
    config: usora_face_matching_engine::config::GrpcConfig,
    engine: Arc<FaceMatchingEngine>,
) -> Result<()> {
    let addr = config.bind_address.parse()?;
    let service = IdentityVerificationServiceImpl::new(engine);

    info!(address = %config.bind_address, "Starting gRPC server");

    tonic::transport::Server::builder()
        .max_concurrent_streams(config.max_concurrent_streams)
        .initial_stream_window_size(Some(config.max_message_size as u32))
        .initial_connection_window_size(Some(config.max_message_size as u32))
        .add_service(
            proto::identity_verification_service_server::IdentityVerificationServiceServer::new(service)
        )
        .serve_with_shutdown(addr, async {
            tokio::signal::ctrl_c().await.ok();
        })
        .await?;

    Ok(())
}

async fn start_kafka_consumer(
    config: usora_face_matching_engine::config::KafkaConfig,
    processing: usora_face_matching_engine::config::ProcessingConfig,
    engine: Arc<FaceMatchingEngine>,
) -> Result<()> {
    let consumer: StreamConsumer = ClientConfig::new()
        .set("bootstrap.servers", &config.brokers)
        .set("group.id", &config.consumer_group)
        .set("enable.auto.commit", "true")
        .set("auto.offset.reset", "earliest")
        .set("max.poll.interval.ms", &config.max_poll_interval_ms.to_string())
        .set("session.timeout.ms", &config.session_timeout_ms.to_string())
        .create()?;

    let producer: FutureProducer = ClientConfig::new()
        .set("bootstrap.servers", &config.brokers)
        .set("message.timeout.ms", "5000")
        .create()?;

    consumer.subscribe(&[&config.tasks_topic])?;

    info!(topic = %config.tasks_topic, group = %config.consumer_group, "Kafka consumer started");

    let semaphore = Arc::new(Semaphore::new(processing.max_concurrent_jobs));

    let mut stream = consumer.stream();
    while let Some(msg) = stream.next().await {
        match msg {
            Ok(msg) => {
                let permit = semaphore.clone().acquire_owned().await;
                let engine = engine.clone();
                let producer = producer.clone();
                let results_topic = config.results_topic.clone();
                let audit_topic = config.audit_topic.clone();

                tokio::spawn(async move {
                    let _permit = permit;
                    if let Some(payload) = msg.payload() {
                        match process_kafka_message(payload, &engine).await {
                            Ok(result) => {
                                let record = FutureRecord::to(&results_topic)
                                    .payload(&result)
                                    .key(msg.key());
                                if let Err(e) = producer.send(record, tokio::time::Duration::from_secs(5)).await {
                                    error!(error = %e, "Failed to publish result");
                                }
                            }
                            Err(e) => {
                                let error_payload = serde_json::json!({
                                    "error": e.to_string(),
                                    "original_key": msg.key().map(|k| String::from_utf8_lossy(k).to_string()),
                                });
                                let record = FutureRecord::to(&audit_topic)
                                    .payload(&error_payload.to_string())
                                    .key(msg.key());
                                if let Err(e) = producer.send(record, tokio::time::Duration::from_secs(5)).await {
                                    error!(error = %e, "Failed to publish error to audit");
                                }
                                error!(error = %e, "Failed to process Kafka message");
                            }
                        }
                    }
                });
            }
            Err(e) => {
                warn!(error = %e, "Kafka consumer error");
            }
        }
    }

    Ok(())
}

async fn process_kafka_message(payload: &[u8], engine: &FaceMatchingEngine) -> Result<String> {
    let task: serde_json::Value = serde_json::from_slice(payload)?;
    let task_type = task["task_type"].as_str().unwrap_or("unknown");
    let task_id = task["task_id"].as_str().unwrap_or("");

    let result = match task_type {
        "verify_face" => {
            let source_b64 = task["payload"]["source_image"].as_str().ok_or_else(|| anyhow::anyhow!("Missing source_image"))?;
            let target_b64 = task["payload"]["target_image"].as_str().ok_or_else(|| anyhow::anyhow!("Missing target_image"))?;
            let threshold = task["payload"]["threshold"].as_f64().unwrap_or(engine.default_threshold());

            let source_bytes = usora_face_matching_engine::utils::decode_image_base64(source_b64)?;
            let target_bytes = usora_face_matching_engine::utils::decode_image_base64(target_b64)?;
            let source_img = usora_face_matching_engine::utils::load_image_from_bytes(&source_bytes)?;
            let target_img = usora_face_matching_engine::utils::load_image_from_bytes(&target_bytes)?;

            let verify_result = engine.verify_faces(&source_img, &target_img, threshold).await?;
            serde_json::to_value(&verify_result)?
        }
        "identify_face" => {
            let probe_b64 = task["payload"]["probe_image"].as_str().ok_or_else(|| anyhow::anyhow!("Missing probe_image"))?;
            let top_k = task["payload"]["top_k"].as_u64().unwrap_or(10) as usize;

            let probe_bytes = usora_face_matching_engine::utils::decode_image_base64(probe_b64)?;
            let probe_img = usora_face_matching_engine::utils::load_image_from_bytes(&probe_bytes)?;

            let identify_result = engine.identify_face(&probe_img, top_k).await?;
            serde_json::to_value(&identify_result)?
        }
        "liveness_check" => {
            let image_b64 = task["payload"]["image"].as_str().ok_or_else(|| anyhow::anyhow!("Missing image"))?;
            let challenge_type = task["payload"]["challenge_type"].as_str().unwrap_or("passive");
            let challenge_data = task["payload"]["challenge_data"].as_str();

            let image_bytes = usora_face_matching_engine::utils::decode_image_base64(image_b64)?;
            let image = usora_face_matching_engine::utils::load_image_from_bytes(&image_bytes)?;

            let liveness_result = engine.check_liveness(&image, challenge_type, challenge_data).await?;
            serde_json::to_value(&liveness_result)?
        }
        "register_face" => {
            let image_b64 = task["payload"]["image"].as_str().ok_or_else(|| anyhow::anyhow!("Missing image"))?;
            let user_id = task["payload"]["user_id"].as_str().ok_or_else(|| anyhow::anyhow!("Missing user_id"))?;

            let image_bytes = usora_face_matching_engine::utils::decode_image_base64(image_b64)?;
            let image = usora_face_matching_engine::utils::load_image_from_bytes(&image_bytes)?;

            engine.register_face(&image, user_id).await?;
            serde_json::json!({"status": "registered", "user_id": user_id})
        }
        _ => {
            anyhow::bail!("Unknown task type: {}", task_type);
        }
    };

    let response = serde_json::json!({
        "task_id": task_id,
        "task_type": task_type,
        "status": "completed",
        "result": result,
        "processing_time_ms": 0,
    });

    Ok(response.to_string())
}

async fn start_metrics_server(port: u16) -> Result<()> {
    use hyper::body::Incoming;
    use hyper::service::service_fn;
    use hyper::{Request, Response};
    use http_body_util::Full;
    use hyper::body::Bytes;

    let addr = std::net::SocketAddr::from(([0, 0, 0, 0], port));

    let listener = tokio::net::TcpListener::bind(addr).await?;

    info!(port = %port, "Starting metrics endpoint");

    loop {
        let (stream, _) = listener.accept().await?;
        let io = hyper::server::conn::http1::Builder::new();
        tokio::spawn(async move {
            let svc = service_fn(|_req: Request<Incoming>| async move {
                let encoder = prometheus::TextEncoder::new();
                let mut buffer = Vec::new();
                let metric_families = prometheus::gather();
                encoder.encode(&metric_families, &mut buffer).unwrap();
                let body = String::from_utf8(buffer).unwrap_or_default();
                Ok::<_, hyper::Error>(Response::new(Full::new(Bytes::from(body))))
            });
            if let Err(e) = io.serve_connection(stream, svc).await {
                warn!(error = %e, "Metrics connection error");
            }
        });
    }
}
