# Agent: Security Audit

## Metadata
- **Agent ID**: `usora-agent-security-audit`
- **Tier**: 6 — Security & Trust
- **Owner**: Security Engineering / Compliance
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Security Audit agent provides immutable audit logging, tamper detection, forensic analysis, and compliance evidence collection across all USORA services. It ensures every security-relevant event is captured, cryptographically signed, and stored with integrity guarantees for regulatory inspection and incident response.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Audit Store | PostgreSQL (append-only) + S3 (cold) | 16+ |
| Integrity | SHA-256 Merkle tree + blockchain anchoring | — |
| Log Shipping | Vector / Fluent Bit | latest |
| SIEM | Splunk / Elastic Security / custom | latest |
| Forensics | custom Rust + TheHive | — |
| Blockchain | Hyperledger Fabric (optional) | 2.5+ |
| Monitoring | Prometheus + Grafana | latest |

## API Surface

### gRPC Services
```protobuf
service AuditService {
  rpc LogEvent(AuditEventRequest) returns (AuditEventResponse);
  rpc GetAuditTrail(AuditTrailRequest) returns (AuditTrailResponse);
  rpc VerifyIntegrity(IntegrityRequest) returns (IntegrityResponse);
  rpc SearchEvents(EventSearchRequest) returns (EventSearchResponse);
  rpc GenerateComplianceReport(ComplianceReportRequest) returns (ComplianceReportResponse);
  rpc GetTamperAlerts(TamperAlertRequest) returns (TamperAlertResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/audit/events` | Log audit event |
| GET | `/api/v1/audit/trail/{entityType}/{entityId}` | Get audit trail |
| POST | `/api/v1/audit/verify` | Verify integrity of audit log |
| POST | `/api/v1/audit/search` | Search audit events |
| POST | `/api/v1/audit/reports/compliance` | Generate compliance report |
| GET | `/api/v1/audit/tamper-alerts` | Get tamper detection alerts |

## Tenant Isolation Strategy
- **Schema isolation**: Per-tenant audit tables: `tenant_{tid}.audit_log`
- **Index isolation**: Per-tenant Elasticsearch indices for audit search
- **Key isolation**: Per-tenant signing keys for audit entry signatures
- **Access isolation**: Audit read access restricted to tenant admin + compliance officer roles
- **Retention isolation**: Per-tenant retention policies (default: 7 years)
- **Export isolation**: Per-tenant compliance reports with tenant-specific evidence

## Security Boundaries
- All audit entries cryptographically signed with tenant-specific HMAC key
- Merkle tree root hash anchored to blockchain every hour for tamper detection
- Audit log append-only: no UPDATE or DELETE operations allowed
- Audit entries include: timestamp, actor, action, resource, before/after state, IP, user agent
- Tamper detection: periodic verification of Merkle tree integrity
- Forensic preservation: incident-related audit entries flagged and preserved beyond retention
- SIEM integration: real-time streaming of security events to SIEM

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Audit ingestion events → Loki (meta-audit) |
| Metrics | `audit_events_logged_total`, `audit_integrity_verification_total`, `audit_tamper_alerts_total`, `audit_search_latency_seconds` |
| Traces | OpenTelemetry spans on audit log operations |
| Alerts | Tamper detection alert, audit log write failure, integrity verification failure |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Audit log write failure | DB error | Queue to local buffer, retry, alert if persistent |
| Merkle tree corruption | Hash mismatch | Freeze audit log, alert security, manual investigation |
| Blockchain anchoring failure | Transaction error | Retry, use local hash chain, alert |
| SIEM connection failure | Health check | Buffer events locally, retry, alert |
| Tamper detected | Integrity check failure | Immediate security incident, freeze affected records, alert CISO |
| Search index lag | ES lag metric | Route queries to DB, re-index, alert |

## Configuration
```yaml
audit:
  storage:
    hot_store: "postgresql"
    cold_store: "s3"
    hot_retention_days: 90
    cold_retention_years: 7
  integrity:
    hash_algorithm: "SHA-256"
    merkle_tree_interval: "1h"
    blockchain_anchor: true
    blockchain_network: "hyperledger_fabric"
  signing:
    algorithm: "HMAC-SHA256"
    key_provider: "vault"
    key_rotation_days: 90
  siem:
    enabled: true
    provider: "splunk"
    url: "${VAULT:siem_url}"
    token: "${VAULT:siem_token}"
    batch_size: 100
    flush_interval: "5s"
  events:
    categories:
      - "authentication"
      - "authorization"
      - "data_access"
      - "data_modification"
      - "configuration_change"
      - "security_event"
      - "compliance_check"
    required_fields:
      - "timestamp"
      - "actor_id"
      - "action"
      - "resource_type"
      - "resource_id"
      - "tenant_id"
      - "outcome"
  forensics:
    incident_flag_ttl: "2555d"  # 7 years
    evidence_preservation: true
    chain_of_custody: true
```

## Dependencies
- `platform-observability` — Metrics, alerting, log shipping
- `platform-secrets` — Signing keys, SIEM credentials
- `data-postgresql` — Hot audit storage
- `data-s3` — Cold audit archive
- `security-zero-trust` — Security event sources
