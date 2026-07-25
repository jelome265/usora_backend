use anyhow::Context;
use rdkafka::consumer::{Consumer, StreamConsumer};
use rdkafka::message::Message;
use rdkafka::producer::{FutureProducer, FutureRecord};
use rdkafka::ClientConfig;
use std::sync::Arc;
use tokio::signal;
use tokio::sync::Semaphore;
use tokio_stream::StreamExt;
use tonic::transport::Server;
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::util::SubscriberInitExt;
use tracing_subscriber::{EnvFilter, Layer};
use uuid::Uuid;

use usora_risk_scoring_engine::config::ServiceConfig;
use usora_risk_scoring_engine::engine::cache::MultiLevelCache;
use usora_risk_scoring_engine::engine::orchestrator::ScoringOrchestrator;
use usora_risk_scoring_engine::grpc::risk_scoring_service_server::RiskScoringServiceServer;
use usora_risk_scoring_engine::grpc::RiskScoringServiceImpl;
use usora_risk_scoring_engine::ml::feature_store::{
    CompositeFeatureStore, NormalizationParams, PostgresFeatureStore, RedisFeatureStore,
};
use usora_risk_scoring_engine::ml::inference::InferenceService;
use usora_risk_scoring_engine::ml::model::ModelRegistry;
use usora_risk_scoring_engine::models::{ApplicantScoringRequest, FeatureValue, KafkaScoreMessage};
use usora_risk_scoring_engine::rules::evaluator::RuleEvaluator;
use usora_risk_scoring_engine::rules::registry::RuleRegistry;
use usora_risk_scoring_engine::rules::RuleEngineConfig;
use usora_risk_scoring_engine::scoring::engine::PipelineScoringEngine;

#[tokio::main]
async fn main() -> Result<(), anyhow::Error> {
    let config = Arc::new(ServiceConfig::from_env().unwrap_or_default());

    init_telemetry(&config).context("Failed to initialize telemetry")?;

    tracing::info!(
        service = %config.telemetry.service_name,
        grpc_addr = %config.server.grpc_addr,
        "Starting risk scoring engine"
    );

    let model_registry = Arc::new(ModelRegistry::new());
    model_registry
        .register(config.models.applicant_risk.clone())
        .await
        .context("Failed to register applicant risk model")?;
    model_registry
        .register(config.models.transaction_risk.clone())
        .await
        .context("Failed to register transaction risk model")?;

    let normalization_params = Arc::new(NormalizationParams {
        means: std::collections::HashMap::new(),
        stds: std::collections::HashMap::new(),
        feature_order: (0..config.models.applicant_risk.input_features)
            .map(|i| format!("feature_{}", i))
            .collect(),
    });

    let ml_service = Arc::new(InferenceService::new(
        model_registry,
        normalization_params,
        config.models.clone(),
    ));

    let rule_config = RuleEngineConfig::default();
    let rule_evaluator = Arc::new(RuleEvaluator::new(rule_config.clone()));
    let rule_registry = Arc::new(RuleRegistry::new(rule_config));

    let cache = Arc::new(MultiLevelCache::new(
        &config.performance,
        config.redis.key_prefix.clone(),
    ));

    let redis_fs = Arc::new(
        RedisFeatureStore::new(&config.redis.url, config.features.clone())
            .await
            .context("Failed to connect to Redis feature store")?,
    );

    let pg_fs = Arc::new(
        PostgresFeatureStore::new(&config.postgres.url, config.features.clone())
            .await
            .context("Failed to connect to PostgreSQL feature store")?,
    );

    let composite_fs = Arc::new(CompositeFeatureStore::new(
        redis_fs,
        pg_fs,
        config.features.clone(),
    ));

    let scoring_engine = Arc::new(PipelineScoringEngine::new(
        ml_service.clone(),
        rule_evaluator.clone(),
        rule_registry.clone(),
        composite_fs.clone(),
        cache.clone(),
        config.clone(),
        normalization_params.clone(),
    ));

    let orchestrator = Arc::new(ScoringOrchestrator::new(
        scoring_engine.clone(),
        ml_service.clone(),
        rule_evaluator.clone(),
        rule_registry.clone(),
        composite_fs.clone(),
        cache.clone(),
        config.clone(),
    ));

    let kafka_consumer = init_kafka_consumer(&config.kafka)
        .context("Failed to initialize Kafka consumer")?;
    let kafka_producer = init_kafka_producer(&config.kafka)
        .context("Failed to initialize Kafka producer")?;

    let consumer_handle = tokio::spawn(run_kafka_consumer(
        kafka_consumer,
        kafka_producer,
        orchestrator.clone(),
        config.clone(),
    ));

    let grpc_service = RiskScoringServiceImpl::new(orchestrator.clone());

    let grpc_addr = config.server.grpc_addr.parse()?;
    let grpc_handle = tokio::spawn(async move {
        Server::builder()
            .add_service(RiskScoringServiceServer::new(grpc_service))
            .serve(grpc_addr)
            .await
            .context("gRPC server error")
    });

    tracing::info!("Services started. Waiting for shutdown signal...");

    signal::ctrl_c().await?;
    tracing::info!("Shutdown signal received, initiating graceful shutdown...");

    consumer_handle.abort();
    grpc_handle.abort();

    tracing::info!("Shutdown complete");
    Ok(())
}

fn init_telemetry(config: &ServiceConfig) -> Result<(), anyhow::Error> {
    let env_filter = EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| EnvFilter::new(&config.telemetry.log_level));

    let fmt_layer = tracing_subscriber::fmt::layer()
        .json()
        .with_target(true)
        .with_level(true);

    let subscriber = tracing_subscriber::registry()
        .with(env_filter)
        .with(fmt_layer);

    if config.telemetry.enable_otlp {
        if let Some(ref endpoint) = config.telemetry.tracing_endpoint {
            let otlp_layer = opentelemetry_otlp::new_pipeline()
                .tracing()
                .with_exporter(opentelemetry_otlp::new_exporter().tonic().with_endpoint(endpoint))
                .with_trace_config(
                    opentelemetry_sdk::trace::config()
                        .with_resource(opentelemetry_sdk::Resource::new(vec![
                            opentelemetry::KeyValue::new(
                                opentelemetry_semantic_conventions::resource::SERVICE_NAME,
                                config.telemetry.service_name.clone(),
                            ),
                        ])),
                )
                .install_batch(opentelemetry_sdk::runtime::Tokio)
                .context("Failed to install OTLP tracer")?;

            let tracing_layer = tracing_opentelemetry::layer().with_tracer(otlp_layer);
            subscriber.with(tracing_layer).init();
            return Ok(());
        }
    }

    subscriber.init();
    Ok(())
}

fn init_kafka_consumer(
    kafka_config: &usora_risk_scoring_engine::config::KafkaConfig,
) -> Result<StreamConsumer, anyhow::Error> {
    let consumer: StreamConsumer = ClientConfig::new()
        .set("bootstrap.servers", &kafka_config.brokers)
        .set("group.id", &kafka_config.group_id)
        .set("auto.offset.reset", &kafka_config.auto_offset_reset)
        .set("enable.auto.commit", &kafka_config.enable_auto_commit.to_string())
        .set("session.timeout.ms", &kafka_config.session_timeout_ms.to_string())
        .set("max.poll.interval.ms", &kafka_config.max_poll_interval_ms.to_string())
        .create()
        .context("Kafka consumer creation failed")?;

    consumer
        .subscribe(&[&kafka_config.risk_tasks_topic])
        .context("Failed to subscribe to risk tasks topic")?;

    Ok(consumer)
}

fn init_kafka_producer(
    kafka_config: &usora_risk_scoring_engine::config::KafkaConfig,
) -> Result<FutureProducer, anyhow::Error> {
    let producer: FutureProducer = ClientConfig::new()
        .set("bootstrap.servers", &kafka_config.brokers)
        .set("message.timeout.ms", "5000")
        .create()
        .context("Kafka producer creation failed")?;

    Ok(producer)
}

async fn run_kafka_consumer(
    consumer: StreamConsumer,
    producer: FutureProducer,
    orchestrator: Arc<ScoringOrchestrator>,
    config: Arc<ServiceConfig>,
) {
    let semaphore = Arc::new(Semaphore::new(config.performance.max_concurrent_per_tenant));

    tracing::info!("Kafka consumer started, listening on topic: {}", config.kafka.risk_tasks_topic);

    let mut stream = consumer.stream();
    while let Some(result) = stream.next().await {
        match result {
            Ok(message) => {
                let permit = semaphore.clone().acquire_owned().await;
                if permit.is_err() {
                    tracing::warn!("Failed to acquire semaphore permit, skipping message");
                    continue;
                }
                let _permit = permit.unwrap();

                match message.payload() {
                    Some(payload) => {
                        let orchestrator = orchestrator.clone();
                        let producer = producer.clone();
                        let config = config.clone();

                        tokio::spawn(async move {
                            if let Err(e) = process_kafka_message(
                                payload,
                                orchestrator,
                                &producer,
                                &config,
                            )
                            .await
                            {
                                tracing::error!(error = %e, "Failed to process Kafka message");
                            }
                        });
                    }
                    None => {
                        tracing::warn!("Received Kafka message with empty payload");
                    }
                }
            }
            Err(e) => {
                tracing::error!(error = %e, "Kafka consumer error");
            }
        }
    }
}

async fn process_kafka_message(
    payload: &[u8],
    orchestrator: Arc<ScoringOrchestrator>,
    producer: &FutureProducer,
    config: &ServiceConfig,
) -> Result<(), anyhow::Error> {
    let request: ApplicantScoringRequest = serde_json::from_slice(payload)
        .context("Failed to deserialize Kafka message as ApplicantScoringRequest")?;

    tracing::info!(
        applicant_id = %request.applicant_id,
        tenant_id = %request.tenant_id,
        "Processing risk scoring request from Kafka"
    );

    let response = orchestrator.score_applicant(&request).await?;

    let score_message = KafkaScoreMessage {
        score_id: response.score_id,
        applicant_id: response.applicant_id.clone(),
        tenant_id: response.tenant_id.clone(),
        composite_score: response.composite_score,
        risk_level: response.risk_level,
        processing_time_ms: response.processing_time_ms,
        model_version: response.model_version,
        rule_version: response.rule_version,
        timestamp: response.computed_at,
    };

    let result_json = serde_json::to_string(&score_message)
        .context("Failed to serialize scoring result")?;

    let record = FutureRecord::to(&config.kafka.risk_results_topic)
        .key(&score_message.score_id.to_string())
        .payload(&result_json);

    producer
        .send(record, tokio::time::Duration::from_secs(5))
        .await
        .map_err(|(e, _)| anyhow::anyhow!("Kafka send error: {}", e))?;

    tracing::info!(
        score_id = %score_message.score_id,
        topic = %config.kafka.risk_results_topic,
        "Published scoring result to Kafka"
    );

    Ok(())
}
