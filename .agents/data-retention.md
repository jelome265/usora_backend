# Agent: Data Retention & Deletion

## Metadata
- **Agent ID**: `usora-agent-data-retention`
- **Tier**: 4 — Data & Persistence
- **Owner**: Data Engineering / Compliance
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Data Retention & Deletion agent manages automated data lifecycle policies, GDPR Article 17 right-to-erasure compliance, data anonymization, and cryptographic deletion across all USORA data stores. It ensures regulatory compliance while optimizing storage costs.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Scheduler | Quartz (Java) / custom Rust | 2.5+ |
| Workflow | Temporal / custom job engine | latest |
| Anonymization | custom Rust + k-anonymity | — |
| Encryption | AWS KMS + HashiCorp Vault | latest |
| Audit | Immutable log store | — |
| Monitoring | Prometheus + Grafana | latest |

## API Surface

### gRPC Services
```protobuf
service RetentionService {
  rpc ScheduleDeletion(DeletionScheduleRequest) returns (DeletionScheduleResponse);
  rpc ExecuteDeletion(DeletionExecuteRequest) returns (DeletionExecuteResponse);
  rpc AnonymizeData(AnonymizationRequest) returns (AnonymizationResponse);
  rpc GetRetentionPolicy(RetentionPolicyRequest) returns (RetentionPolicyResponse);
  rpc UpdateRetentionPolicy(RetentionPolicyUpdateRequest) returns (RetentionPolicyUpdateResponse);
  rpc VerifyDeletion(DeletionVerificationRequest) returns (DeletionVerificationResponse);
  rpc GetDeletionReport(DeletionReportRequest) returns (DeletionReportResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/retention/schedule` | Schedule data deletion |
| POST | `/api/v1/retention/execute` | Execute scheduled deletion |
| POST | `/api/v1/retention/anonymize` | Anonymize data |
| GET | `/api/v1/retention/policy/{tenantId}` | Get retention policy |
| PUT | `/api/v1/retention/policy/{tenantId}` | Update retention policy |
| POST | `/api/v1/retention/verify` | Verify deletion completeness |
| GET | `/api/v1/retention/report/{deletionId}` | Get deletion compliance report |

## Tenant Isolation Strategy
- **Policy isolation**: Per-tenant retention policies in PostgreSQL
- **Job isolation**: Per-tenant deletion jobs with separate queues
- **Verification isolation**: Per-tenant deletion verification reports
- **Audit isolation**: Per-tenant deletion audit trails
- **Key isolation**: Per-tenant encryption key deletion for cryptographic erasure

## Security Boundaries
- Deletion jobs require dual authorization: data owner + compliance officer
- All deletions logged to immutable audit chain with cryptographic proof
- Anonymization uses k-anonymity (k=5) + l-diversity + t-closeness
- Cryptographic deletion: KMS key destruction for data encrypted with tenant key
- Verification: cryptographic hash verification of deletion completeness
- No soft deletes for GDPR requests; immediate hard deletion with verification
- Retention policy changes require compliance approval and 30-day notice

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Deletion events → structured audit log → Loki |
| Metrics | `retention_deletion_jobs_total`, `retention_deletion_duration_seconds`, `retention_anonymization_total`, `retention_storage_reclaimed_bytes` |
| Traces | OpenTelemetry spans: schedule → validate → execute → verify → report |
| Alerts | Deletion job failure, verification failure, retention policy violation |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Deletion job failure | Job error | Retry with backoff, alert, manual intervention |
| Verification failure | Hash mismatch | Re-run deletion, alert compliance, incident response |
| Anonymization failure | k-anonymity not achieved | Reject anonymization, alert, manual review |
| KMS key destruction failure | AWS API error | Retry, alert, manual key deletion |
| Policy conflict | Overlapping retention rules | Reject policy update, alert compliance admin |
| Storage cleanup failure | S3 lifecycle error | Manual cleanup, alert, review lifecycle rules |

## Configuration
```yaml
retention:
  policies:
    default:
      kyc_data: "7y"
      audit_logs: "7y"
      session_data: "30d"
      temporary_files: "7d"
      compliance_reports: "7y"
    gdpr:
      right_to_erasure: "immediate"
      verification_required: true
      cryptographic_deletion: true
  anonymization:
    k_anonymity: 5
    l_diversity: 2
    t_closeness: 0.2
    quasi_identifiers: ["age", "zip_code", "gender", "nationality"]
  deletion:
    batch_size: 1000
    max_parallel_jobs: 10
    retry_attempts: 3
    retry_backoff_seconds: 300
    verification_sample_rate: 0.01  # 1% sample verification
  scheduling:
    cron_expression: "0 2 * * *"  # Daily at 2 AM UTC
    timezone: "UTC"
  audit:
    immutable_log: true
    retention_years: 7
    blockchain_anchor: true
```

## Dependencies
- `platform-identity` — Authorization for deletion jobs
- `platform-observability` — Audit logging, metrics, alerting
- `platform-secrets` — KMS key management for cryptographic deletion
- `data-postgresql` — Retention policies, deletion tracking
- `data-s3` — Object deletion, lifecycle management
- `data-redis` — Cache eviction, session cleanup
- `data-kafka` — Event stream cleanup
