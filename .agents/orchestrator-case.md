# Agent: Orchestrator Case

## Metadata
- **Agent ID**: `usora-agent-orchestrator-case`
- **Tier**: 2 — Business Orchestration
- **Owner**: Backend Engineering
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Orchestrator Case agent manages the complete lifecycle of KYC cases — from initial submission through automated verification, manual review queues, escalation rules, and final resolution. It provides case routing, assignment, SLA tracking, and audit trails with strict tenant isolation and compliance-grade immutability.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Runtime | Spring Boot | 4.1 |
| JVM | Java 21 LTS | 21 |
| Concurrency | Virtual Threads | — |
| Database | PostgreSQL (schema-per-tenant) | 16+ |
| Cache | Redis | 7.2+ |
| Events | Kafka | 3.8+ |
| Search | Elasticsearch / OpenSearch | 2.16+ |
| Queue | Redis Streams / Kafka | — |

## API Surface

### gRPC Services
```protobuf
service CaseService {
  rpc CreateCase(CaseCreateRequest) returns (CaseCreateResponse);
  rpc GetCase(CaseGetRequest) returns (CaseGetResponse);
  rpc UpdateCase(CaseUpdateRequest) returns (CaseUpdateResponse);
  rpc AssignCase(CaseAssignRequest) returns (CaseAssignResponse);
  rpc EscalateCase(CaseEscalateRequest) returns (CaseEscalateResponse);
  rpc ResolveCase(CaseResolveRequest) returns (CaseResolveResponse);
  rpc ListCases(CaseListRequest) returns (CaseListResponse);
  rpc GetCaseTimeline(CaseTimelineRequest) returns (CaseTimelineResponse);
  rpc AddCaseNote(CaseNoteRequest) returns (CaseNoteResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/cases` | Create new KYC case |
| GET | `/api/v1/cases/{caseId}` | Get case details |
| PUT | `/api/v1/cases/{caseId}` | Update case (status, priority, data) |
| POST | `/api/v1/cases/{caseId}/assign` | Assign case to reviewer |
| POST | `/api/v1/cases/{caseId}/escalate` | Escalate case (SLA breach, complexity) |
| POST | `/api/v1/cases/{caseId}/resolve` | Resolve case (approve/reject/needs_info) |
| GET | `/api/v1/cases` | List cases (paginated, filtered, sorted) |
| GET | `/api/v1/cases/{caseId}/timeline` | Get full audit timeline |
| POST | `/api/v1/cases/{caseId}/notes` | Add review note |

## Tenant Isolation Strategy
- **Schema isolation**: Cases stored in `tenant_{tid}.cases` table
- **Search isolation**: Elasticsearch indices per tenant: `cases-tenant-{tid}`
- **Queue isolation**: Redis Streams per tenant: `case-queue:{tid}`
- **Assignment isolation**: Reviewers scoped to tenant; no cross-tenant assignment
- **SLA isolation**: Per-tenant SLA configuration (default: 24h for standard, 4h for priority)
- **Audit isolation**: Case timeline events tagged with `tenant_id`; immutable append-only

## Security Boundaries
- Case data encrypted at rest (AES-256-GCM via PostgreSQL TDE)
- PII in case notes redacted in logs; full content only in encrypted DB
- Case assignment requires `case:manage` role within tenant
- Escalation requires `case:escalate` role or automatic SLA breach trigger
- Resolution requires dual authorization for high-risk cases (risk score > 0.8)
- All case state changes append-only audit log with cryptographic integrity (Merkle tree)
- Case export requires `case:export` role + compliance approval

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Case lifecycle events → structured audit log → Loki |
| Metrics | `case_created_total`, `case_resolved_total`, `case_resolution_duration_seconds`, `case_sla_breach_total`, `case_queue_depth` |
| Traces | OpenTelemetry spans per case operation; propagated to review UI |
| Alerts | SLA breach rate > 2%, case queue depth > 100 per tenant, resolution time > SLA (p95) |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Case creation failure | DB constraint / validation error | Retry, DLQ, alert if persistent |
| Assignment failure | No available reviewers | Queue for auto-retry, alert tenant admin |
| SLA breach | Scheduled job check | Auto-escalate, notify tenant admin, audit log |
| Search index out of sync | DB vs ES mismatch | Re-index job, alert, manual verification |
| Timeline corruption | Merkle tree verification failure | Freeze case, alert security, manual investigation |
| Export failure | Large dataset timeout | Streaming export, chunked download, progress tracking |

## Configuration
```yaml
case:
  sla:
    standard: "24h"
    priority: "4h"
    urgent: "1h"
    auto_escalate_before_breach: "30m"
  assignment:
    strategy: "round_robin"  # round_robin | least_loaded | skill_based
    max_cases_per_reviewer: 20
    auto_assign: true
  escalation:
    levels:
      - name: "supervisor"
        condition: "sla_breach_warning"
      - name: "manager"
        condition: "sla_breached"
      - name: "compliance"
        condition: "fraud_suspected"
  resolution:
    dual_authorization_threshold: 0.8  # risk score
    required_fields: ["reviewer_id", "resolution_reason", "supporting_evidence"]
  audit:
    merkle_tree_enabled: true
    immutable_storage: true
    retention_years: 7
```

## Dependencies
- `orchestrator-core` — Business logic integration, tenant context
- `orchestrator-workflow` — BPMN workflow integration for case routing
- `platform-identity` — RBAC, reviewer authentication
- `data-postgresql` — Case storage, audit timeline
- `data-redis` — Assignment queue, SLA tracking
- `data-kafka` — Case event streaming
