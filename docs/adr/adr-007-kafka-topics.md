# ADR-007: Kafka Topic Design for Multi-Tenant Event Bus

## Status

Accepted

## Context

USORA's event-driven architecture relies on Kafka as the backbone for asynchronous communication between services. The Gateway, Orchestration, Compute, and Analytics layers all produce and consume events via Kafka. In a multi-tenant environment, topic design must balance:

- **Tenant isolation**: Prevent cross-tenant event leakage
- **Performance**: Minimize topic/partition overhead
- **Scalability**: Support thousands of tenants without Kafka cluster explosion
- **Operational simplicity**: Manageable number of topics to monitor, backup, and tune
- **Compliance**: Audit trail for all events

Options evaluated:

1. **Topic-per-Tenant** — Each tenant gets dedicated topics. Maximum isolation but unmanageable at scale.
2. **Shared Topics with Tenant ID in Key/Value** — All tenants share topics; tenant ID in message key or value. Requires consumer-side filtering.
3. **Topic-per-Event-Type with Tenant Partitioning** — Topics by event type (e.g., `verification.events`); partitions assigned by tenant hash. Balanced approach.
4. **Topic-per-Workload-per-Tenant** — Each tenant gets topics per workload type. Too many topics.
5. **Topic-per-Event-Type with Tenant Header** — Topics by event type; tenant ID in Kafka headers. Consumer filters by header.

## Decision

Use **Topic-per-Event-Type with Tenant ID in Message Key** as the primary pattern, with a dedicated `audit.logs` topic per tenant for compliance.

## Consequences

### Positive

- **Operational simplicity**: Manageable number of topics (~20 event types vs. thousands of tenant topics). Kafka cluster remains manageable.
- **Performance**: Topic-per-event-type allows optimized partitioning per event type. High-throughput topics (e.g., `verification.events`) can have more partitions than low-throughput topics.
- **Tenant isolation**: Tenant ID in message key ensures that all events for a tenant route to the same partition (if using default partitioner). Consumer groups can filter by tenant ID.
- **Scalability**: Adding tenants does not require new topics. Partition count can be increased independently of tenant count.
- **Compliance**: `audit.logs` topic has 7-year retention and is replicated to ClickHouse and blockchain anchor. Dedicated topic per tenant for audit ensures no cross-tenant audit leakage.
- **Consumer efficiency**: Consumers can subscribe to specific topics based on their needs. No need to consume all events and filter.

### Negative

- **Consumer-side filtering required**: Consumers must filter messages by tenant ID. A misconfigured consumer could process another tenant's events.
- **No database-level isolation**: Unlike PostgreSQL RLS, Kafka does not enforce tenant isolation at the broker level.
- **Partition imbalance**: If tenant event volume is uneven, some partitions may be hot while others are idle. Requires careful partition key design.
- **Cross-tenant analytics**: Aggregating events across tenants requires consuming from shared topics and filtering.

### Mitigations

- **Consumer validation**: All Kafka consumers validate the tenant ID in the message key against their authorized tenant list. Unauthorized tenant events are rejected and logged.
- **Partition key design**: Tenant ID + event sub-type (e.g., `tenant_id + ":" + verification_id`) ensures even distribution while maintaining ordering per verification.
- **Monitoring**: Per-tenant consumer lag metrics. Alert if a tenant's events are not being consumed.
- **ACLs**: Kafka ACLs restrict topic access per consumer group. Consumers can only access topics they are authorized for.
- **Audit topic isolation**: `audit.logs` is a single topic with 7-year retention, but consumer access is strictly controlled. Audit consumers are the only services that read from this topic.

## Topic Inventory

| Topic | Producer | Consumer | Schema | Retention | Partitions |
|---|---|---|---|---|---|
| `verification.commands` | Gateway, Admin | Orchestration | Protobuf | 7 days | 12 |
| `verification.events` | Orchestration | Gateway, Compute, Analytics | Protobuf | 30 days | 24 |
| `verification.results` | Compute | Orchestration | Protobuf | 30 days | 24 |
| `document.tasks` | Orchestration | Compute (Document) | Protobuf | 1 day | 48 |
| `biometric.tasks` | Orchestration | Compute (Biometric) | Protobuf | 1 day | 48 |
| `risk.tasks` | Orchestration | Compute (Risk) | Protobuf | 1 day | 24 |
| `fraud.tasks` | Orchestration | Compute (Fraud) | Protobuf | 1 day | 24 |
| `audit.logs` | All services | ClickHouse, Blockchain | Avro | 7 years | 12 |
| `webhook.delivery` | Orchestration | Gateway | Protobuf | 1 day | 12 |
| `compliance.alerts` | Orchestration | Admin, SIEM | Protobuf | 1 year | 6 |

## Message Key Format

```
{tenant_id}:{entity_type}:{entity_id}

Example: "acme-uuid:verification:ver_7f8a9b2c"
```

This ensures:
- All events for a specific verification are ordered within a partition
- Even distribution across partitions (verification UUID provides good hash distribution)
- Consumer can filter by tenant_id prefix

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| Topic-per-Tenant | Thousands of topics unmanageable. Kafka has practical limits (~10,000 topics per cluster). Operational nightmare. |
| Shared Topics with Tenant Header | Headers are not part of the partition key, so tenant ordering is not guaranteed. Less efficient consumer filtering. |
| Topic-per-Workload-per-Tenant | Similar to topic-per-tenant but slightly fewer topics. Still unmanageable at scale. |
| Topic-per-Tenant-per-Event-Type | Combines the worst of both approaches: too many topics and too much management overhead. |

## Related Decisions

- ADR-004: Schema-per-Tenant with Row-Level Security for PostgreSQL
- ADR-005: Redis Key Namespacing for Tenant Isolation
- ADR-008: gRPC for Inter-Service Communication

## Date

2026-03-20

## Author

Bob Martinez, Backend Lead

## Reviewed By

Alice Chen (Platform Lead), Diana Ross (Data Lead)
