# Agent: Orchestrator Core

## Metadata
- **Agent ID**: `usora-agent-orchestrator-core`
- **Tier**: 2 — Business Orchestration
- **Owner**: Backend Engineering
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Orchestrator Core is the central business logic engine of USORA, built on Spring Boot 4.1 with Java 21 Virtual Threads. It coordinates all KYC operations, manages service-to-service communication, handles sagas and compensating transactions, and provides the primary REST/gRPC API surface for external consumers.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Runtime | Spring Boot | 4.1 |
| JVM | Java 21 LTS | 21 |
| Concurrency | Virtual Threads (Project Loom) | — |
| Web | Spring WebFlux (reactive) + Virtual Threads bridge | 6.2+ |
| gRPC | grpc-spring-boot-starter | 3.1+ |
| Messaging | Spring for Apache Kafka | 3.2+ |
| Caching | Spring Data Redis | 3.4+ |
| Validation | Jakarta Bean Validation | 3.1+ |
| Mapping | MapStruct | 1.6+ |

## API Surface

### gRPC Services
```protobuf
service OrchestratorService {
  rpc SubmitKYC(KYCSubmissionRequest) returns (KYCSubmissionResponse);
  rpc GetKYCStatus(KYCStatusRequest) returns (KYCStatusResponse);
  rpc CancelKYC(KYCCancelRequest) returns (KYCCancelResponse);
  rpc GetCaseDetails(CaseDetailsRequest) returns (CaseDetailsResponse);
  rpc ListCases(CaseListRequest) returns (CaseListResponse);
  rpc UpdateCaseStatus(CaseStatusUpdateRequest) returns (CaseStatusUpdateResponse);
  rpc GetTenantConfig(TenantConfigRequest) returns (TenantConfigResponse);
  rpc UpdateTenantConfig(TenantConfigUpdateRequest) returns (TenantConfigUpdateResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/kyc/submit` | Submit new KYC case |
| GET | `/api/v1/kyc/{caseId}/status` | Get KYC case status |
| POST | `/api/v1/kyc/{caseId}/cancel` | Cancel pending KYC case |
| GET | `/api/v1/cases` | List cases (paginated, filterable) |
| GET | `/api/v1/cases/{caseId}` | Get case details |
| PUT | `/api/v1/cases/{caseId}/status` | Update case status (manual review) |
| GET | `/api/v1/tenants/{tenantId}/config` | Get tenant configuration |
| PUT | `/api/v1/tenants/{tenantId}/config` | Update tenant configuration |

## Tenant Isolation Strategy
- **Schema isolation**: Each tenant has dedicated PostgreSQL schema: `tenant_{tid}`
- **Connection pooling**: HikariCP pools per tenant with max 20 connections each
- **Request context**: `TenantContext` thread-local propagated via Virtual Threads
- **Data filtering**: All repository queries auto-prefixed with tenant schema
- **Configuration isolation**: Tenant configs stored in Redis with `config:tenant:{tid}:` prefix
- **Event isolation**: Kafka messages include `tenant_id` header; consumers filter by tenant ACL
- **Cache isolation**: Redis keys prefixed with `cache:tenant:{tid}:`

## Security Boundaries
- All endpoints protected by OAuth2 resource server (JWT validation)
- Role-based access: `kyc:submit`, `kyc:read`, `case:manage`, `tenant:admin`
- Input validation: Jakarta Bean Validation + custom sanitizers
- Rate limiting: Per-tenant, per-endpoint via Redis-backed buckets
- Audit logging: Every state change logged to immutable audit table
- Idempotency: `Idempotency-Key` header enforced for mutation endpoints
- CORS: Whitelist per tenant configuration

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Structured JSON via Logback → Loki; Virtual Thread IDs in MDC |
| Metrics | Micrometer → Mimir: `orchestrator_kyc_submitted_total`, `orchestrator_case_resolution_duration_seconds`, `orchestrator_tenant_config_cache_hit_ratio` |
| Traces | OpenTelemetry → Tempo; spans across gRPC + Kafka + DB |
| Alerts | KYC submission error rate > 1%, case resolution time > 5min (p99), DB connection pool exhaustion |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| DB connection pool exhaustion | HikariCP metrics | Queue requests, scale read replicas, alert |
| Virtual Thread pinning | JFR event | Monitor, refactor synchronized blocks, alert |
| Kafka producer failure | Delivery callback error | Retry with exponential backoff, DLQ after 3 attempts |
| gRPC upstream timeout | Deadline exceeded | Circuit breaker, fallback to cached response, alert |
| Tenant config cache miss | Redis key miss | Load from DB, warm cache, metric increment |
| Saga compensation failure | Compensation event error | Manual intervention queue, alert, audit log |

## Configuration
```yaml
orchestrator:
  server:
    port: 8080
    virtual_threads:
      enabled: true
      max_pool_size: 1000
  datasource:
    hikari:
      maximum_pool_size: 20
      minimum_idle: 5
      connection_timeout: 30000
      idle_timeout: 600000
      max_lifetime: 1800000
  kafka:
    producer:
      acks: all
      retries: 3
      batch_size: 16384
    consumer:
      group_id: "orchestrator-group"
      auto_offset_reset: earliest
      max_poll_records: 500
  grpc:
    server:
      port: 9090
      max_inbound_message_size: 4194304  # 4MB
  redis:
    host: "redis.usora.svc.cluster.local"
    port: 6379
    database: 0
    timeout: 2000
```

## Dependencies
- `platform-gateway` — Ingress routing, WAF, rate limiting
- `platform-identity` — JWT validation, RBAC enforcement
- `platform-observability` — Metrics, traces, logs, alerting
- `data-postgresql` — Tenant-scoped schema storage
- `data-redis` — Caching, session store, idempotency keys
- `data-kafka` — Event publishing/consuming
- `orchestrator-workflow` — Camunda workflow engine integration
- `compute-*` — Verification service clients
