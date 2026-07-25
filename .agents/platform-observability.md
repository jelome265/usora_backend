# Agent: Platform Observability

## Metadata
- **Agent ID**: `usora-agent-platform-observability`
- **Tier**: 1 — Core Platform
- **Owner**: Platform Engineering / SRE
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Platform Observability agent provides unified telemetry collection, storage, and analysis across all USORA services. It handles structured logging, metrics aggregation, distributed tracing, and alerting with strict tenant-scoped data separation and privacy controls.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Collector | OpenTelemetry Collector | 0.110+ |
| Logs | Grafana Loki | 3.2+ |
| Metrics | Grafana Mimir | 2.14+ |
| Traces | Grafana Tempo | 2.6+ |
| Dashboards | Grafana | 11.3+ |
| Alerting | Grafana Alerting + PagerDuty | latest |
| Log Shipping | Vector / Fluent Bit | latest |

## API Surface

### gRPC Services
```protobuf
service ObservabilityService {
  rpc PushLogs(LogBatch) returns (PushResponse);
  rpc PushMetrics(MetricBatch) returns (PushResponse);
  rpc PushTraces(TraceBatch) returns (PushResponse);
  rpc QueryLogs(LogQueryRequest) returns (LogQueryResponse);
  rpc QueryMetrics(MetricQueryRequest) returns (MetricQueryResponse);
  rpc QueryTraces(TraceQueryRequest) returns (TraceQueryResponse);
  rpc CreateAlertRule(AlertRuleRequest) returns (AlertRuleResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/v1/logs` | Log ingestion (OTLP) |
| POST | `/v1/metrics` | Metric ingestion (OTLP) |
| POST | `/v1/traces` | Trace ingestion (OTLP) |
| GET | `/v1/query/logs` | LogQL queries (tenant-scoped) |
| GET | `/v1/query/metrics` | PromQL queries (tenant-scoped) |
| GET | `/v1/query/traces` | TraceQL queries (tenant-scoped) |

## Tenant Isolation Strategy
- **Log namespace**: Loki streams labeled with `tenant_id={tid}`; query enforced via OPA sidecar
- **Metric namespace**: Mimir tenant isolation via `X-Scope-OrgID` header
- **Trace namespace**: Tempo multi-tenant via `tenant_id` resource attribute
- **Dashboard isolation**: Grafana org per tenant; datasource permissions enforced
- **Alert isolation**: Alert rules namespace-scoped; routing per tenant notification channel
- **Data retention**: Per-tenant configurable retention (default 30d logs, 15d traces, 90d metrics)

## Security Boundaries
- All telemetry data encrypted at rest (AES-256-GCM) and in transit (TLS 1.3)
- PII redaction pipeline: automatic masking of SSN, passport, biometric hashes in logs
- Audit log of all telemetry queries (who queried what tenant data when)
- No cross-tenant query possible at storage layer
- Retention policies enforced by automated purge jobs with cryptographic deletion

## Observability Hooks (Meta)
| Signal | Implementation |
|--------|---------------|
| Self-monitoring | `observability_collector_queue_depth`, `observability_storage_ingestion_rate`, `observability_query_latency_seconds` |
| Health | `/health` endpoint on all components |
| Alerts | Collector backlog > 10k items, storage saturation > 85%, query latency > 5s |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Collector overload | Queue depth metric spike | Horizontal pod autoscaling, backpressure to clients |
| Storage saturation | Disk usage > 85% | Automated compaction, tiered storage to S3, alert SRE |
| Query timeout | Query latency > 30s | Query cancellation, rate limiting, cached results fallback |
| OTLP receiver down | Health check failure | Traffic reroute to replica, alert on-call |
| PII leak detection | Regex match in log stream | Immediate quarantine, security incident triggered |

## Configuration
```yaml
observability:
  collector:
    receivers:
      otlp:
        protocols:
          grpc:
            endpoint: "0.0.0.0:4317"
          http:
            endpoint: "0.0.0.0:4318"
    processors:
      batch:
        timeout: 1s
        send_batch_size: 1024
      resource:
        attributes:
          - key: platform
            value: usora
            action: upsert
    exporters:
      loki:
        endpoint: "http://loki.usora.svc.cluster.local:3100"
      mimir:
        endpoint: "http://mimir.usora.svc.cluster.local:8080"
      tempo:
        endpoint: "http://tempo.usora.svc.cluster.local:4317"
  retention:
    logs: "30d"
    traces: "15d"
    metrics: "90d"
    compliance_logs: "7y"  # Immutable audit logs
  pii_redaction:
    enabled: true
    patterns:
      - "\\b\\d{3}-\\d{2}-\\d{4}\\b"  # SSN
      - "\\b[A-Z]{1,2}\\d{6,9}\\b"  # Passport
```

## Dependencies
- `platform-secrets` — TLS certs, encryption keys for telemetry at rest
- `data-s3` — Long-term cold storage for compliance archives
- `platform-identity` — Query authorization, audit logging
