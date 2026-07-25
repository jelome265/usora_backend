use std::sync::Arc;
use tokio::signal;
use tonic::transport::Server;
use tracing::{error, info};

use usora_document_processor::generated::usora::document::v1::document_service_server::DocumentServiceServer;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let config = Arc::new(usora_document_processor::config::Config::from_env()?);
    config.validate()?;
    usora_document_processor::utils::init_tracing(&config)?;

    info!("Starting {} on {}", config.service_name, config.grpc_bind_address);

    let doc_service = usora_document_processor::grpc::DocumentServiceImpl::new(config.clone());

    let (mut health_reporter, health_service) = tonic_health::server::health_reporter();
    health_reporter.set_serving::<DocumentServiceServer<usora_document_processor::grpc::DocumentServiceImpl>>().await;

    let grpc_addr = config.grpc_bind_address.parse()?;

    let reflection_service = tonic_reflection::server::Builder::configure()
        .register_encoded_file_descriptor_set(
            usora_document_processor::generated::usora::document::v1::DOCUMENT_SERVICE_FILE_DESCRIPTOR_SET,
        )
        .build()?;

    let kafka_config = config.clone();
    let kafka_handle = tokio::spawn(async move {
        if let Err(e) = run_kafka_consumer(kafka_config).await {
            error!("Kafka consumer exited: {}", e);
        }
    });

    info!("gRPC server listening on {}", grpc_addr);
    Server::builder()
        .add_service(health_service)
        .add_service(reflection_service)
        .add_service(DocumentServiceServer::new(doc_service))
        .serve_with_shutdown(grpc_addr, async {
            signal::ctrl_c().await.ok();
            info!("Shutdown signal received");
        })
        .await?;

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
    while let Some(msg) = stream.next().await {
        match msg {
            Ok(m) => {
                if let Some(payload) = m.payload() {
                    let payload = payload.to_vec();
                    let processor = processor.clone();
                    let producer = producer.clone();
                    let results_topic = config.kafka_results_topic.clone();

                    tokio::spawn(async move {
                        match processor.process_document(&payload).await {
                            Ok(result) => {
                                let json = serde_json::to_vec(&result).unwrap_or_default();
                                let record = FutureRecord::to(&results_topic)
                                    .payload(&json)
                                    .key(&uuid::Uuid::now_v7().to_string());
                                if let Err(e) = producer.send(record, Timeout::After(std::time::Duration::from_secs(5))).await {
                                    error!("Failed to send result: {:?}", e);
                                }
                            }
                            Err(e) => {
                                error!("Document processing failed: {}", e);
                                let err_payload = serde_json::json!({
                                    "error": e.to_string(),
                                    "status": "failed"
                                });
                                let json = serde_json::to_vec(&err_payload).unwrap_or_default();
                                let record = FutureRecord::to(&results_topic)
                                    .payload(&json)
                                    .key(&uuid::Uuid::now_v7().to_string());
                                if let Err(e) = producer.send(record, Timeout::After(std::time::Duration::from_secs(5))).await {
                                    error!("Failed to send error result: {:?}", e);
                                }
                            }
                        }
                    });
                }
            }
            Err(e) => {
                error!("Kafka consumer error: {}", e);
            }
        }
    }

    Ok(())
}
