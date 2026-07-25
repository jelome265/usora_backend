# Agent: Data Kafka

## Metadata
- **Agent ID**: `usora-agent-data-kafka`
- **Tier**: 4 — Data & Persistence
- **Owner**: Data Engineering / SRE
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Data Kafka agent manages the event streaming backbone of USORA using Amazon MSK (Managed Kafka). It handles tenant-aware topic design, schema registry, stream processing, dead letter queues, and exactly-once semantics with strict tenant isolation and compliance-grade audit trails.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Streaming | Apache Kafka | 3.8+ |
| Managed Service | Amazon MSK | latest |
| Schema Registry | Confluent Schema Registry | 7.7+ |
| Stream Processing | Kafka Streams / Flink | 3.8+ / 1.20+ |
| Client | kafka-clients (Java) / rdkafka (Rust) | 3.8+ / 0.36+ |
| Monitoring | Kafka Exporter + Grafana | latest |

## API Surface

### gRPC Services
```protobuf
service KafkaService {
  rpc CreateTopic(TopicCreateRequest) returns (TopicCreateResponse);
  rpc DeleteTopic(TopicDeleteRequest) returns (TopicDeleteResponse);
  rpc PublishMessage(MessagePublishRequest) returns (MessagePublishResponse);
  rpc ConsumeMessages(ConsumeRequest) returns (stream ConsumeResponse);
  rpc GetTopicInfo(TopicInfoRequest) returns (TopicInfoResponse);
  rpc GetConsumerGroupLag(ConsumerLagRequest) returns (ConsumerLagResponse);
  rpc RegisterSchema(SchemaRegisterRequest) returns (SchemaRegisterResponse);
  rpc GetSchema(SchemaGetRequest) returns (SchemaGetResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/kafka/topics` | Create tenant topic |
| DELETE | `/api/v1/kafka/topics/{topic}` | Delete tenant topic |
| POST | `/api/v1/kafka/publish` | Publish message |
| GET | `/api/v1/kafka/consume` | Consume messages (SSE) |
| GET | `/api/v1/kafka/topics/{topic}/info` | Get topic metadata |
| GET | `/api/v1/kafka/consumer-groups/{group}/lag` | Get consumer lag |
| POST | `/api/v1/kafka/schemas` | Register Avro/Protobuf schema |
| GET | `/api/v1/kafka/schemas/{subject}` | Get schema by subject |

## Tenant Isolation Strategy
- **Topic naming**: `tenant-{tid}-{event-type}` — enforced by topic creation API
- **ACL isolation**: Per-tenant Kafka ACLs (produce/consume/create on tenant prefix only)
- **Consumer group isolation**: Consumer groups prefixed with `tenant-{tid}-`
- **Schema isolation**: Schema Registry subjects prefixed with `tenant-{tid}.`
- **Quota isolation**: Per-tenant producer/consumer rate limits and byte quotas
- **Partition isolation**: Per-tenant topic partitions; no cross-tenant partition sharing
- **DLQ isolation**: Per-tenant dead letter topics: `tenant-{tid}-dlq-{original-topic}`

## Security Boundaries
- All Kafka connections over TLS 1.3 with mutual authentication (mTLS)
- SASL/SCRAM authentication per tenant with password rotation via Vault
- Schema evolution: backward compatibility enforced by Schema Registry
- Message encryption: sensitive fields encrypted with tenant key before serialization
- Audit logging: all produce/consume operations logged to immutable audit topic
- No cross-tenant topic access possible via ACL enforcement
- Exactly-once semantics: idempotent producers + transactional consumers

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Broker logs → Loki; client logs → Loki |
| Metrics | `kafka_messages_produced_total`, `kafka_messages_consumed_total`, `kafka_consumer_lag`, `kafka_topic_bytes_in_rate`, `kafka_replication_lag` |
| Traces | OpenTelemetry spans: produce → broker → consume → processing; propagated via Kafka headers |
| Alerts | Consumer lag > 10000, broker offline, replication under-replicated partitions > 0, topic bytes in > threshold |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Broker failure | MSK health check | Automatic re-election, alert, verify replication |
| Consumer lag spike | Lag metric | Scale consumer pods, alert, investigate processing bottleneck |
| Schema incompatibility | Schema Registry validation | Reject message, alert producer, manual schema evolution |
| Message serialization failure | Deserializer error | Route to DLQ, alert, manual inspection |
| Producer timeout | Delivery timeout | Retry with exponential backoff, alert if persistent |
| Exactly-once violation | Transaction coordinator error | Abort transaction, alert, manual investigation |
| Topic ACL violation | Authorization error | Reject operation, log security event, alert |

## Configuration
```yaml
kafka:
  msk:
    cluster_arn: "${MSK_CLUSTER_ARN}"
    broker_count: 6
    instance_type: "kafka.m5.large"
    storage_per_broker_gb: 1000
    encryption:
      in_transit: "TLS"
      at_rest: true
  schema_registry:
    url: "http://schema-registry.usora.svc.cluster.local:8081"
    compatibility: "BACKWARD"
    auth: "BASIC"
  topics:
    default_partitions: 6
    default_replication_factor: 3
    retention_ms: 604800000  # 7 days
    cleanup_policy: "delete"
    min_insync_replicas: 2
  producer:
    acks: "all"
    retries: 3
    batch_size: 16384
    linger_ms: 5
    compression: "snappy"
    enable_idempotence: true
  consumer:
    auto_offset_reset: "earliest"
    enable_auto_commit: false
    max_poll_records: 500
    isolation_level: "read_committed"
  quotas:
    default_producer_rate: 1048576  # 1 MB/s per tenant
    default_consumer_rate: 1048576
    default_request_percentage: 20
```

## Dependencies
- `platform-infra` — MSK provisioning, VPC, security groups
- `platform-secrets` — SASL passwords, TLS certificates
- `platform-observability` — Metrics, logs, alerting
- `orchestrator-tenant` — Topic provisioning on tenant onboarding
- `data-retention` — Topic retention management, data purging
