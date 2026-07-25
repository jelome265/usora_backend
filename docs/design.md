# USORA — Design Document

## 1. Design Philosophy

### 1.1 Core Principles

USORA's design is governed by five immutable principles that shape every architectural decision:

**Performance as a Feature**
Every millisecond matters in identity verification. User abandonment spikes exponentially with latency. The architecture is optimized for sub-100ms end-to-end response times at the 99.9th percentile, not just median cases.

**Security by Construction**
Security is not bolted on; it is the foundation. Tenant isolation, encryption, and audit trails are architectural invariants enforced by the type system, runtime checks, and infrastructure.

**Failure is Normal**
Services fail, networks partition, disks corrupt. The system is designed to degrade gracefully, recover automatically, and never lose data.

**Observability is Mandatory**
If you cannot measure it, you cannot operate it. Every request, every decision, every failure is traced, logged, and metered.

**Change is Constant**
Regulations evolve, fraud techniques adapt, scale demands shift. The architecture supports independent deployment, feature flags, and A/B testing.

### 1.2 Technology Selection Rationale

| Layer | Technology | Why |
|---|---|---|
| **API Gateway** | Rust + Tokio | Zero-GC latency, memory safety, millions of concurrent connections, predictable p99 performance |
| **Orchestration** | Java 21 + Spring Boot | Mature BPMN ecosystem, Virtual Threads for massive concurrency, enterprise integration patterns |
| **Compute** | Rust + Tokio | Sustained CPU-intensive workloads without GC pauses, safe parallelism, small binaries for fast scaling |
| **Frontend** | TypeScript 5 + React 19 | Type safety across stack, Server Components for performance, modern developer experience |
| **Data** | PostgreSQL + Redis + Kafka | ACID transactions, sub-millisecond cache, event-driven async backbone |

---

## 2. System Architecture

### 2.1 Layered Architecture

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                              CLIENT LAYER                                     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐ │
│  │  Web Portal  │  │  Mobile SDK  │  │  API Clients │  │  3rd Party       │ │
│  │  (React 19)  │  │  (iOS/Andr)  │  │  (REST/gRPC) │  │  Integrations    │ │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘ │
└─────────┼─────────────────┼─────────────────┼───────────────────┼───────────┘
          │                 │                 │                   │
          ▼                 ▼                 ▼                   ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                              EDGE LAYER                                       │
│  CloudFlare / AWS CloudFront — DDoS protection, CDN, geo-routing              │
└──────────────────────────────────────────────────────────────────────────────┘
          │
          ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│  LAYER 1: API GATEWAY  (Rust + Tokio — Custom)                               │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │  axum HTTP/2 server  │  tonic gRPC server  │  WebSocket handler       │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
│                              │                                                │
│                              ▼                                                │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │  TOWER MIDDLEWARE PIPELINE (per-request, ordered)                       │  │
│  │  1. TLS Termination (rustls, TLS 1.3, mTLS)                             │  │
│  │  2. Request ID injection (UUID v7, sortable)                            │  │
│  │  3. Correlation ID propagation (OpenTelemetry trace context)              │  │
│  │  4. DDoS / WAF filtering (rate limit per IP, geo-block)                 │  │
│  │  5. Tenant resolution (subdomain → tenant_id, header fallback)          │  │
│  │  6. Authentication (JWT validation, mTLS cert extraction)               │  │
│  │  7. Authorization (RBAC/ABAC policy engine, OPA/Rego)                   │  │
│  │  8. Rate limiting (token bucket per tenant, Redis-backed)               │  │
│  │  9. Request validation (OpenAPI schema, max body size)                  │  │
│  │  10. Circuit breaker (per-tenant, per-upstream, Resilience4j pattern)   │  │
│  │  11. Request transformation (REST → gRPC, header injection)             │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
│                              │                                                │
│                              ▼                                                │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │  ROUTING ENGINE                                                         │  │
│  │  • Service discovery (Kubernetes DNS / Consul)                          │  │
│  │  • Load balancing (least-connections, weighted)                         │  │
│  │  • Canary / blue-green (header-based traffic split)                     │  │
│  │  • Sticky sessions (WebSocket affinity)                                 │  │
│  │  • Direct compute bypass (simple ops skip orchestration)                │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
│                              │                                                │
│                              ▼                                                │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │  RESPONSE PIPELINE                                                      │  │
│  │  • gRPC → REST translation                                              │  │
│  │  • Response caching (Redis, TTL per endpoint)                           │  │
│  │  • Audit log emission (async Kafka)                                     │  │
│  │  • Metrics (Prometheus counters/histograms)                             │  │
│  │  • Distributed tracing (OpenTelemetry span closure)                     │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────┘
          │                              │
          │ gRPC + mTLS                  │ gRPC + mTLS (bypass)
          ▼                              ▼
┌────────────────────────┐    ┌────────────────────────┐
│  LAYER 2: ORCHESTRATION │    │  LAYER 2b: COMPUTE     │
│  (Java 21 + Spring Boot)│    │  DIRECT (Rust + Tokio) │
│                         │    │  (health, metrics,     │
│                         │    │   simple inference)    │
└────────────────────────┘    └────────────────────────┘
          │
          │ gRPC + mTLS / Kafka
          ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│  LAYER 3: COMPUTE  (Rust + Tokio — Worker Pools)                             │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │  TASK CONSUMER (rdkafka, async consumer group)                          │  │
│  │  • Manual offset commit (at-least-once)                                 │  │
│  │  • Dead-letter topic (max 3 retries)                                    │  │
│  │  • Priority partitioning (P0 express, P1 standard, P2 batch)            │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
│                              │                                                │
│                              ▼                                                │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │  WORKER POOLS (Tokio runtime, per-tenant resource quotas)               │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌───────────────┐  │  │
│  │  │ Document    │  │ Biometric   │  │ Risk        │  │ Fraud         │  │  │
│  │  │ Analysis    │  │ Matching    │  │ Scoring     │  │ Detection     │  │  │
│  │  │ Worker Pool │  │ Worker Pool │  │ Worker Pool │  │ Worker Pool   │  │  │
│  │  │ (CPU-bound) │  │ (CPU-bound) │  │ (Mixed)     │  │ (Mixed)       │  │  │
│  │  │ spawn_block │  │ spawn_block │  │ async + CPU │  │ async + CPU   │  │  │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └───────────────┘  │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
│                              │                                                │
│                              ▼                                                │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │  RESULT PUBLISHER                                                       │  │
│  │  • Kafka result topic (for orchestration consumption)                   │  │
│  │  • Webhook delivery (with retry + circuit breaker)                      │  │
│  │  • Metrics + tracing emission                                           │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────┘
          │
          │ JDBC / SQLx / Redis / S3
          ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│  LAYER 4: DATA                                                                │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │ PostgreSQL  │  │ Redis       │  │ Kafka       │  │ S3 / MinIO          │  │
│  │ (Per-tenant │  │ (Cache /    │  │ (Event      │  │ (Documents /        │  │
│  │  schema iso)│  │  Session)   │  │  Bus)       │  │  Artifacts)         │  │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────────────┘  │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │ ClickHouse  │  │ Elastic-    │  │ Blockchain  │  │ HashiCorp Vault     │  │
│  │ (Analytics) │  │ search      │  │ (Audit      │  │ (Secrets / Keys)    │  │
│  │             │  │ (Search)    │  │  Anchor)    │  │                     │  │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Service Boundaries

#### 2.2.1 API Gateway (Rust)

**Responsibilities:**
- Accept all inbound traffic (REST, gRPC, WebSocket)
- Terminate TLS, enforce mTLS for internal routing
- Authenticate and authorize every request
- Resolve tenant context and enforce per-tenant quotas
- Route requests to appropriate downstream services
- Translate protocols (REST ↔ gRPC) when needed
- Cache responses and emit audit logs
- Never contain business logic

**Explicitly NOT Responsible For:**
- Business workflow decisions
- Document analysis or ML inference
- Database transactions
- Long-running state management

**Deployment Model:**
- Stateless, horizontally scaled
- 3+ replicas minimum per region

#### 2.2.2 Orchestration (Java)

**Responsibilities:**
- Manage verification lifecycle (create → process → complete)
- Execute BPMN workflows with Camunda
- Coordinate multi-step verification flows
- Make business decisions (approve, reject, escalate)
- Maintain saga state for distributed transactions
- Enforce compliance rules and regulatory logic
- Publish domain events to Kafka
- Handle human-in-the-loop review queues

**Explicitly NOT Responsible For:**
- Raw HTTP client handling (gateway does this)
- CPU-intensive ML inference (compute does this)
- Direct document image processing

**Deployment Model:**
- Stateful (workflow state in PostgreSQL)
- Horizontally scaled with partition-aware routing
- Leader election for scheduled tasks

#### 2.2.3 Compute (Rust)

**Responsibilities:**
- Execute CPU-intensive tasks: OCR, document forensics, biometric matching, ML inference
- Process tasks asynchronously from Kafka queues
- Return structured results to orchestration via Kafka
- Maintain model weights and feature caches in memory
- Enforce per-tenant resource quotas
- Provide health and metrics endpoints

**Explicitly NOT Responsible For:**
- HTTP request handling (gateway does this)
- Business workflow decisions (orchestration does this)
- Direct client communication

**Deployment Model:**
- Stateless workers, horizontally scaled
- GPU nodes for deep learning inference (optional)
- Separate node pools per workload type

---

## 3. Data Architecture

### 3.1 Data Flow

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Client    │────▶│   Gateway   │────▶│Orchestration│────▶│   Kafka     │
│             │     │   (Rust)    │     │   (Java)    │     │  (Events)   │
└─────────────┘     └─────────────┘     └─────────────┘     └──────┬──────┘
                                                                   │
                    ┌──────────────────────────────────────────────┘
                    │
                    ▼
┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Client    │◀────│   Gateway   │◀────│Orchestration│◀────│   Kafka     │
│  (Webhook)  │     │   (Rust)    │     │   (Java)    │     │  (Results)  │
└─────────────┘     └─────────────┘     └─────────────┘     └──────┬──────┘
                                                                   │
                    ┌──────────────────────────────────────────────┘
                    │
                    ▼
            ┌─────────────┐     ┌─────────────┐
            │   Compute   │────▶│  PostgreSQL │
            │   (Rust)    │     │  (Results)  │
            └─────────────┘     └─────────────┘
```

### 3.2 Tenant Isolation Strategy

**PostgreSQL: Schema-per-Tenant with Row-Level Security**

```sql
-- Each tenant gets a dedicated schema
CREATE SCHEMA tenant_acme;
CREATE SCHEMA tenant_globalbank;

-- Shared tables with RLS
CREATE TABLE tenant_acme.verifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL DEFAULT 'acme-uuid',
    user_reference VARCHAR(255),
    status verification_status NOT NULL,
    risk_score DECIMAL(5,2),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Row-Level Security Policy
CREATE POLICY tenant_isolation ON tenant_acme.verifications
    USING (tenant_id = current_setting('app.current_tenant')::UUID);

ALTER TABLE tenant_acme.verifications ENABLE ROW LEVEL SECURITY;

-- Application sets tenant context per connection
SET app.current_tenant = 'acme-uuid';
```

**Connection Pooling:**
- PgBouncer with transaction-level pooling
- 10,000+ concurrent connections across all tenants
- Connection multiplexing reduces PostgreSQL process overhead

**Redis: Key Namespacing**

```
tenant:{tenant_id}:session:{session_id}  →  Session data
tenant:{tenant_id}:rate_limit:{client_id}  →  Rate limit counters
tenant:{tenant_id}:cache:{cache_key}  →  Cached responses
tenant:{tenant_id}:feature_flags  →  Tenant configuration
```

### 3.3 Event Schema (Kafka Topics)

| Topic | Producer | Consumer | Schema | Retention |
|---|---|---|---|---|
| `verification.commands` | Gateway, Admin | Orchestration | Protobuf | 7 days |
| `verification.events` | Orchestration | Gateway, Compute, Analytics | Protobuf | 30 days |
| `verification.results` | Compute | Orchestration | Protobuf | 30 days |
| `document.tasks` | Orchestration | Compute (Document) | Protobuf | 1 day |
| `biometric.tasks` | Orchestration | Compute (Biometric) | Protobuf | 1 day |
| `risk.tasks` | Orchestration | Compute (Risk) | Protobuf | 1 day |
| `fraud.tasks` | Orchestration | Compute (Fraud) | Protobuf | 1 day |
| `audit.logs` | All services | ClickHouse, Blockchain | Avro | 7 years |
| `webhook.delivery` | Orchestration | Gateway | Protobuf | 1 day |
| `compliance.alerts` | Orchestration | Admin, SIEM | Protobuf | 1 year |

### 3.4 Audit Trail

Every action produces an immutable audit record:

```protobuf
message AuditRecord {
  string record_id = 1;           // UUID v7
  string tenant_id = 2;
  string actor_id = 3;            // User or service identity
  string actor_type = 4;          // USER | SERVICE | SYSTEM
  string action = 5;              // CREATE | READ | UPDATE | DELETE | EXECUTE
  string resource_type = 6;       // VERIFICATION | DOCUMENT | BIOMETRIC
  string resource_id = 7;
  string payload_hash = 8;        // SHA-256 of request payload
  string previous_state_hash = 9; // Hash chain for tamper detection
  int64 timestamp_ms = 10;        // Unix epoch millis
  string source_ip = 11;
  string user_agent = 12;
  map<string, string> metadata = 13;
}
```

**Hash Chain:**
```
Record_N.hash = SHA256(Record_N.payload + Record_{N-1}.hash)
```

**Blockchain Anchoring:**
- Merkle root of hourly audit batches published to Ethereum L2 (Polygon)
- Smart contract stores root hash with timestamp
- Any tampering detectable by hash mismatch

---

## 4. API Design

### 4.1 Gateway API Contract

**Base URL:** `https://api.usora.io/v1/{tenant-id}/`

**Authentication:**
- OAuth 2.0 + PKCE for interactive flows
- API Key + HMAC-SHA256 request signing for server-to-server
- mTLS for high-security integrations

**Headers (Mandatory):**
```
Authorization: Bearer {jwt_token}
X-Request-ID: {uuid_v7}
X-Tenant-ID: {tenant_uuid}
X-Client-Version: {semver}
Content-Type: application/json
```

**Error Response Format:**
```json
{
  "error": {
    "code": "VERIFICATION_EXPIRED",
    "message": "The verification session has expired",
    "target": "verification_id",
    "details": [
      {
        "code": "SESSION_TIMEOUT",
        "message": "Session exceeded 60 minute limit",
        "target": "expires_at"
      }
    ],
    "request_id": "550e8400-e29b-41d4-a716-446655440000",
    "timestamp": "2026-07-21T23:07:00Z"
  }
}
```

**Error Codes:**

| Code | HTTP | Description |
|---|---|---|
| `UNAUTHORIZED` | 401 | Invalid or missing credentials |
| `FORBIDDEN` | 403 | Insufficient permissions |
| `TENANT_NOT_FOUND` | 404 | Tenant does not exist |
| `RATE_LIMITED` | 429 | Quota exceeded, retry-after header provided |
| `VERIFICATION_NOT_FOUND` | 404 | Verification ID invalid |
| `VERIFICATION_EXPIRED` | 410 | Session timeout |
| `INVALID_DOCUMENT` | 422 | Document failed validation |
| `BIOMETRIC_FAILED` | 422 | Liveness or match failed |
| `INTERNAL_ERROR` | 500 | Unexpected server error |
| `GATEWAY_TIMEOUT` | 504 | Upstream service timeout |

### 4.2 gRPC Service Definitions

```protobuf
// usora/gateway/v1/gateway.proto
syntax = "proto3";
package usora.gateway.v1;

service GatewayService {
  rpc CreateVerification(CreateVerificationRequest) returns (Verification);
  rpc GetVerification(GetVerificationRequest) returns (Verification);
  rpc CancelVerification(CancelVerificationRequest) returns (Verification);
  rpc StreamVerificationEvents(StreamVerificationEventsRequest) 
    returns (stream VerificationEvent);
}

message CreateVerificationRequest {
  string tenant_id = 1;
  string workflow_id = 2;
  string user_reference = 3;
  string callback_url = 4;
  map<string, string> metadata = 5;
}

message Verification {
  string id = 1;
  string tenant_id = 2;
  string status = 3;
  string workflow_id = 4;
  repeated VerificationStep steps = 5;
  string session_url = 6;
  int64 created_at_ms = 7;
  int64 expires_at_ms = 8;
}

message VerificationStep {
  string id = 1;
  string name = 2;
  string status = 3;
  bool required = 4;
  int64 completed_at_ms = 5;
}

message VerificationEvent {
  string verification_id = 1;
  string event_type = 2;
  bytes payload = 3;
  int64 timestamp_ms = 4;
}
```

```protobuf
// usora/compute/v1/document.proto
syntax = "proto3";
package usora.compute.v1;

service DocumentAnalysisService {
  rpc AnalyzeDocument(AnalyzeDocumentRequest) returns (DocumentAnalysisResult);
  rpc AnalyzeDocumentStream(stream AnalyzeDocumentRequest) 
    returns (stream DocumentAnalysisResult);
}

message AnalyzeDocumentRequest {
  string task_id = 1;
  string tenant_id = 2;
  string verification_id = 3;
  bytes document_image = 4;
  string document_type = 5;
  string country_code = 6;
  AnalysisDepth depth = 7;
}

message DocumentAnalysisResult {
  string task_id = 1;
  string status = 2;
  ExtractedData extracted_data = 3;
  AuthenticityCheck authenticity = 4;
  TamperingCheck tampering = 5;
  float risk_score = 6;
  repeated string flags = 7;
  int64 processing_time_ms = 8;
}
```

### 4.3 Webhook Contract

**Delivery:**
```http
POST /webhooks/usora HTTP/1.1
Host: tenant-api.example.com
X-Usora-Signature: sha256=abc123...
X-Usora-Event-ID: evt_7f8a9b2c
X-Usora-Timestamp: 1721600820
Content-Type: application/json

{
  "event": "verification.completed",
  "data": { ... },
  "tenant_id": "tenant_acme",
  "timestamp": "2026-07-21T23:07:00Z"
}
```

**Signature Verification:**
```
signature = HMAC-SHA256(webhook_secret, timestamp + "." + payload)
```

**Retry Policy:**
- Immediate, 1s, 2s, 4s, 8s, 16s, 32s, 64s, 128s, 256s, 512s
- Max 24 hours total
- Exponential backoff with jitter

---

## 5. Security Architecture

### 5.1 Threat Model

| Threat | Vector | Mitigation |
|---|---|---|
| Tenant data leakage | Application bug | Schema-per-tenant + RLS + encryption |
| Man-in-the-middle | Network interception | TLS 1.3 + mTLS everywhere |
| Credential theft | Compromised client | Short-lived JWTs, refresh token rotation |
| DDoS | Volumetric attack | Edge DDoS + per-tenant rate limits |
| Insider threat | Malicious employee | Just-in-time access, session recording |
| Supply chain | Compromised dependency | SLSA Level 3, signed artifacts, SBOMs |
| Model poisoning | Adversarial training data | Model versioning, A/B testing, drift detection |
| Deepfake injection | Fraudulent biometric | Multi-modal liveness, temporal analysis |

### 5.2 Authentication Flow

```
Client                    Gateway                   Identity Provider
  │                         │                              │
  │  POST /auth/token       │                              │
  │────────────────────────▶│                              │
  │                         │  OAuth2 / OIDC flow          │
  │                         │─────────────────────────────▶│
  │                         │                              │
  │                         │◀─────────────────────────────│
  │                         │  ID Token + Access Token     │
  │                         │                              │
  │  JWT (signed by USORA)  │                              │
  │◀────────────────────────│                              │
  │                         │                              │
  │  API Request + JWT      │                              │
  │────────────────────────▶│                              │
  │                         │  Validate JWT (local)        │
  │                         │  Check revocation (Redis)    │
  │                         │                              │
  │  Response               │                              │
  │◀────────────────────────│                              │
```

**JWT Claims:**
```json
{
  "sub": "user_12345",
  "tid": "tenant_acme",
  "roles": ["verifier", "admin"],
  "scope": "verifications:read verifications:write",
  "iat": 1721600820,
  "exp": 1721604420,
  "jti": "jwt_abc123",
  "iss": "usora.io",
  "aud": "usora-api"
}
```

### 5.3 Secret Management

**HashiCorp Vault Integration:**

```
┌─────────────────────────────────────────┐
│         HashiCorp Vault                  │
│  ┌─────────────────────────────────┐   │
│  │  PKI Engine                      │   │
│  │  • Service certificates (mTLS)   │   │
│  │  • Auto-rotation (24h TTL)       │   │
│  └─────────────────────────────────┘   │
│  ┌─────────────────────────────────┐   │
│  │  KV Engine v2                    │   │
│  │  • API keys (per-tenant)         │   │
│  │  • Database credentials (dynamic)│   │
│  │  • Encryption keys (per-tenant)  │   │
│  └─────────────────────────────────┘   │
│  ┌─────────────────────────────────┐   │
│  │  Transit Engine                  │   │
│  │  • Data encryption/decryption    │   │
│  │  • Key rotation automation       │   │
│  └─────────────────────────────────┘   │
└─────────────────────────────────────────┘
```

**Dynamic Database Credentials:**
- Vault generates short-lived PostgreSQL credentials (1-hour TTL)
- Each service instance gets unique credentials
- Automatic revocation on pod termination

### 5.4 Network Security

**Zero-Trust Network Model:**

```
┌─────────────────────────────────────────────────────────────┐
│                        INTERNET                              │
└───────────────────────────┬─────────────────────────────────┘
                            │  TLS 1.3
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  EDGE (CloudFlare) — WAF, DDoS, Bot detection               │
└───────────────────────────┬─────────────────────────────────┘
                            │  TLS 1.3
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  KUBERNETES CLUSTER                                          │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  INGRESS (Rust Gateway)                              │   │
│  │  • mTLS termination                                  │   │
│  │  • Namespace isolation                               │   │
│  └────────────────┬────────────────────────────────────┘   │
│                   │  mTLS (SPIFFE/SPIRE)                    │
│                   ▼                                         │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  SERVICES (Orchestration, Compute)                   │   │
│  │  • Network policies (deny-all default)               │   │
│  │  • Calico/Cilium eBPF enforcement                    │   │
│  └─────────────────────────────────────────────────────┘   │
│                   │  mTLS                                   │
│                   ▼                                         │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  DATA (PostgreSQL, Redis, Kafka)                     │   │
│  │  • Private subnets only                              │   │
│  │  • No direct internet access                         │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## 6. Compute Layer Design

### 6.1 Document Analysis Worker

```rust
// Pseudocode — Document Analysis Worker Architecture

use tokio::sync::mpsc;
use tokio::task::spawn_blocking;

struct DocumentWorker {
    model: OnnxModel,
    ocr_engine: TesseractOcr,
    feature_extractor: DocumentFeatureExtractor,
    tenant_quota: Arc<DashMap<Uuid, ResourceQuota>>,
}

impl DocumentWorker {
    async fn process_task(&self, task: DocumentTask) -> Result<DocumentResult> {
        // 1. Validate tenant quota
        self.enforce_quota(task.tenant_id).await?;
        
        // 2. Preprocess image (async I/O)
        let image = self.download_image(&task.s3_url).await?;
        let preprocessed = preprocess_image(image).await?;
        
        // 3. Run ML inference (CPU-bound, spawn_blocking)
        let model = self.model.clone();
        let inference_result = spawn_blocking(move || {
            model.infer(preprocessed)
        }).await?;
        
        // 4. Extract structured data
        let extracted = self.ocr_engine.extract(&inference_result)?;
        
        // 5. Forensic analysis (if requested)
        let forensic = if task.depth == AnalysisDepth::Forensic {
            spawn_blocking(move || {
                run_forensic_checks(preprocessed)
            }).await?
        } else { None };
        
        // 6. Compute risk score
        let risk_score = self.feature_extractor.score(&extracted, &forensic)?;
        
        Ok(DocumentResult {
            task_id: task.id,
            extracted_data: extracted,
            authenticity: inference_result.authenticity,
            tampering: forensic,
            risk_score,
            processing_time_ms: task.elapsed_ms(),
        })
    }
}
```

### 6.2 Biometric Matching Worker

**Template Storage:**
- Templates stored as cancelable biometric hashes (irreversible)
- FAISS index for approximate nearest neighbor search (1M+ templates)
- Per-tenant index partitioning prevents cross-tenant leakage

**Matching Pipeline:**
```
Biometric Capture
      │
      ▼
┌─────────────────┐
│ Quality Check   │ ──► Face detection, blur/sharpness, lighting
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Liveness Det.   │ ──► Passive (texture) + Active (challenge-response)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Feature Extract │ ──► Deep learning embedding (512-dim vector)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Template Hash   │ ──► Cancelable transform (BioHashing)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ FAISS Search    │ ──► 1:N search in tenant's index
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Threshold Check │ ──► Score > tenant-configured threshold?
└─────────────────┘
```

### 6.3 Risk Scoring Worker

**Feature Engineering:**
```rust
struct RiskFeatures {
    // Document signals
    document_authenticity: f32,
    template_match_score: f32,
    known_fraud_pattern: bool,
    
    // Biometric signals
    face_match_score: f32,
    liveness_confidence: f32,
    deepfake_probability: f32,
    
    // Behavioral signals
    typing_speed_variance: f32,
    mouse_jitter: f32,
    device_reputation: f32,
    
    // Identity signals
    name_dob_consistency: f32,
    address_verification: f32,
    synthetic_identity_indicators: Vec<f32>,
    
    // Device/Network signals
    ip_reputation: f32,
    geolocation_anomaly: f32,
    vpn_proxy_detected: bool,
    emulator_detected: bool,
    
    // Historical signals
    previous_verification_outcome: Option<bool>,
    account_age_days: u32,
    velocity_checks: VelocityMetrics,
}
```

**Model Ensemble:**
- XGBoost for tabular feature interpretation
- Neural network for non-linear feature interactions
- Weighted average with tenant-specific calibration

---

## 7. Orchestration Layer Design

### 7.1 BPMN Workflow Engine

**Camunda Integration:**
- BPMN 2.0 process definitions deployed per tenant
- Process variables stored in PostgreSQL (tenant-isolated)
- External task workers for long-running operations
- History cleanup with tenant-specific retention policies

**Example Process Definition:**
```bpmn
<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             id="standard-kyc-v3"
             targetNamespace="http://usora.io/bpmn">
  
  <process id="standard_kyc_v3" isExecutable="true">
    <startEvent id="start" name="Start Verification"/>
    
    <sequenceFlow sourceRef="start" targetRef="document_upload"/>
    <userTask id="document_upload" name="Document Upload"/>
    
    <sequenceFlow sourceRef="document_upload" targetRef="document_analysis"/>
    <serviceTask id="document_analysis" name="Analyze Document"
                 camunda:type="external"
                 camunda:topic="document-analysis"/>
    
    <exclusiveGateway id="document_valid" name="Document Valid?"/>
    <sequenceFlow sourceRef="document_analysis" targetRef="document_valid"/>
    
    <sequenceFlow sourceRef="document_valid" targetRef="biometric_capture"
                  name="Yes" conditionExpression="${documentRisk &lt; 70}"/>
    <sequenceFlow sourceRef="document_valid" targetRef="manual_review"
                  name="No" conditionExpression="${documentRisk &gt;= 70}"/>
    
    <userTask id="biometric_capture" name="Biometric Capture"/>
    <serviceTask id="biometric_analysis" name="Analyze Biometric"
                 camunda:type="external"
                 camunda:topic="biometric-analysis"/>
    
    <exclusiveGateway id="biometric_pass" name="Biometric Pass?"/>
    <sequenceFlow sourceRef="biometric_analysis" targetRef="biometric_pass"/>
    
    <sequenceFlow sourceRef="biometric_pass" targetRef="risk_scoring"
                  name="Yes"/>
    <sequenceFlow sourceRef="biometric_pass" targetRef="manual_review"
                  name="No"/>
    
    <serviceTask id="risk_scoring" name="Compute Risk Score"
                 camunda:type="external"
                 camunda:topic="risk-scoring"/>
    
    <exclusiveGateway id="risk_check" name="Risk Acceptable?"/>
    <sequenceFlow sourceRef="risk_scoring" targetRef="risk_check"/>
    
    <sequenceFlow sourceRef="risk_check" targetRef="approve"
                  name="Yes" conditionExpression="${riskScore &lt; 30}"/>
    <sequenceFlow sourceRef="risk_check" targetRef="enhanced_due_diligence"
                  name="Maybe" conditionExpression="${riskScore &gt;= 30 &amp;&amp; riskScore &lt; 70}"/>
    <sequenceFlow sourceRef="risk_check" targetRef="manual_review"
                  name="No" conditionExpression="${riskScore &gt;= 70}"/>
    
    <serviceTask id="enhanced_due_diligence" name="EDD Check"
                 camunda:type="external"
                 camunda:topic="enhanced-due-diligence"/>
    
    <userTask id="manual_review" name="Manual Review"/>
    
    <endEvent id="approve" name="Approved"/>
    <endEvent id="reject" name="Rejected"/>
  </process>
</definitions>
```

### 7.2 Saga Pattern for Distributed Transactions

**Verification Saga:**
```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Create    │────▶│   Process   │────▶│   Analyze   │────▶│   Complete  │
│ Verification│     │  Document   │     │  Biometric  │     │  Verification│
└─────────────┘     └─────────────┘     └─────────────┘     └─────────────┘
      │                   │                   │                   │
      │                   │                   │                   │
      ▼                   ▼                   ▼                   ▼
┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Compensate │     │  Compensate │     │  Compensate │     │   (final)   │
│   (delete)  │     │   (release) │     │   (release) │     │             │
└─────────────┘     └─────────────┘     └─────────────┘     └─────────────┘
```

**Compensating Actions:**
- Document analysis fails → Delete uploaded document from S3, release quota
- Biometric analysis fails → Delete biometric template, release quota
- Risk scoring fails → Mark verification as incomplete, notify user

### 7.3 State Machine

```java
public enum VerificationState {
    CREATED,
    DOCUMENT_PENDING,
    DOCUMENT_UPLOADED,
    DOCUMENT_ANALYZING,
    DOCUMENT_ANALYZED,
    BIOMETRIC_PENDING,
    BIOMETRIC_CAPTURED,
    BIOMETRIC_ANALYZING,
    BIOMETRIC_ANALYZED,
    RISK_SCORING,
    RISK_SCORED,
    ENHANCED_DUE_DILIGENCE,
    MANUAL_REVIEW_PENDING,
    MANUAL_REVIEWING,
    APPROVED,
    REJECTED,
    EXPIRED,
    CANCELLED
}

public enum VerificationEvent {
    DOCUMENT_UPLOADED,
    DOCUMENT_ANALYSIS_COMPLETED,
    DOCUMENT_ANALYSIS_FAILED,
    BIOMETRIC_CAPTURED,
    BIOMETRIC_ANALYSIS_COMPLETED,
    BIOMETRIC_ANALYSIS_FAILED,
    RISK_SCORE_COMPUTED,
    EDD_COMPLETED,
    MANUAL_REVIEW_SUBMITTED,
    APPROVE,
    REJECT,
    EXPIRE,
    CANCEL
}
```

---

## 8. Gateway Layer Design

### 8.1 Request Lifecycle

```
┌─────────────────────────────────────────────────────────────────┐
│  1. CONNECTION ACCEPT                                           │
│     • TCP handshake                                             │
│     • TLS 1.3 handshake (rustls, certificate validation)        │
│     • ALPN negotiation (h2 preferred, http/1.1 fallback)        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  2. REQUEST PARSING                                             │
│     • HTTP/2 frame decoding (h2 crate)                          │
│     • Header validation (required headers present)              │
│     • Body size enforcement (max 50MB for uploads)              │
│     • Content-Type validation                                   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  3. TENANT RESOLUTION                                           │
│     • Extract tenant from subdomain (acme.api.usora.io)         │
│     • Fallback to X-Tenant-ID header                            │
│     • JWT claim validation (tid matches resolved tenant)        │
│     • Load tenant configuration from Redis (cached)             │
│     • Check tenant status (active, suspended, deleted)          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  4. AUTHENTICATION                                              │
│     • JWT signature validation (Ed25519, RS256)                 │
│     • Expiration and not-before checks                          │
│     • Revocation check (Redis blacklist, O(1))                  │
│     • Scope validation (does token have required scope?)        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  5. AUTHORIZATION                                               │
│     • RBAC: Does user role permit this action?                  │
│     • ABAC: Does resource context permit this action?           │
│     • Policy evaluation (OPA/Rego, cached decisions)            │
│     • Rate limit check (token bucket, Redis-backed)             │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  6. REQUEST TRANSFORMATION                                      │
│     • REST → gRPC translation (if target is gRPC service)       │
│     • Header injection (tenant-id, request-id, trace-context)   │
│     • Body transformation (JSON → Protobuf)                     │
│     • Path parameter extraction and validation                  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  7. ROUTING                                                     │
│     • Service discovery (Kubernetes DNS lookup)                 │
│     • Load balancer selection (least-connections)               │
│     • Canary check (is this request in canary cohort?)          │
│     • Circuit breaker check (is upstream healthy?)              │
│     • Connection pool management (hyper client)                 │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  8. UPSTREAM COMMUNICATION                                      │
│     • gRPC call to orchestration (tonic client)                 │
│     • mTLS certificate presented                                │
│     • Request timeout (configurable per endpoint)               │
│     • Retry logic (idempotent requests only)                    │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  9. RESPONSE PROCESSING                                         │
│     • gRPC → REST translation (if needed)                       │
│     • Response caching (GET requests, cache-control headers)    │
│     • Audit log emission (async, non-blocking)                  │
│     • Metrics recording (latency histogram, counter)            │
│     • Distributed tracing span closure                          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  10. RESPONSE DELIVERY                                          │
│     • HTTP/2 frame encoding                                     │
│     • TLS encryption                                            │
│     • TCP delivery                                              │
└─────────────────────────────────────────────────────────────────┘
```

### 8.2 Middleware Stack (Tower)

```rust
// Tower middleware composition
let app = Router::new()
    .route("/v1/:tenant_id/verifications", post(create_verification))
    .route("/v1/:tenant_id/verifications/:id", get(get_verification))
    .layer(
        ServiceBuilder::new()
            // Outer layers execute first on request, last on response
            .layer(TraceLayer::new_for_http())           // OpenTelemetry tracing
            .layer(MetricsLayer::new(registry.clone()))  // Prometheus metrics
            .layer(AuditLayer::new(kafka_producer))      // Audit logging
            .layer(CacheLayer::new(redis.clone()))       // Response caching
            .layer(CorsLayer::permissive())              // CORS headers
            .layer(
                RateLimitLayer::new(
                    redis.clone(),
                    RateLimitConfig {
                        requests_per_second: 1000,
                        burst_size: 2000,
                        key_extractor: |req| req.tenant_id(),
                    }
                )
            )
            .layer(
                CircuitBreakerLayer::new(
                    CircuitBreakerConfig {
                        failure_threshold: 5,
                        success_threshold: 3,
                        timeout: Duration::from_secs(30),
                        half_open_max_calls: 10,
                    }
                )
            )
            .layer(
                AuthLayer::new(
                    AuthConfig {
                        jwt_validator: JwtValidator::new(public_key),
                        mtls_validator: Some(MtlsValidator::new(ca_cert)),
                        scope_validator: ScopeValidator::new(),
                    }
                )
            )
            .layer(TenantResolutionLayer::new(redis.clone()))
            .layer(RequestValidationLayer::new(openapi_spec))
            .layer(RequestIdLayer::new())
            .layer(TlsTerminationLayer::new(rustls_config))
    );
```

### 8.3 Rate Limiting Algorithm

**Token Bucket per Tenant:**
```rust
struct TenantRateLimiter {
    redis: RedisConnection,
}

impl TenantRateLimiter {
    async fn check(&self, tenant_id: Uuid, cost: u32) -> Result<()> {
        let key = format!("tenant:{}:rate_limit", tenant_id);
        
        // Redis Lua script for atomic token bucket
        let script = r#"
            local key = KEYS[1]
            local cost = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local refill_rate = tonumber(ARGV[3])
            local now = tonumber(ARGV[4])
            
            local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
            local tokens = tonumber(bucket[1]) or capacity
            local last_refill = tonumber(bucket[2]) or now
            
            local elapsed = now - last_refill
            tokens = math.min(capacity, tokens + elapsed * refill_rate)
            
            if tokens >= cost then
                tokens = tokens - cost
                redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)
                redis.call('EXPIRE', key, 3600)
                return 1
            else
                redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)
                redis.call('EXPIRE', key, 3600)
                return 0
            end
        "#;
        
        let allowed: bool = self.redis.eval(
            script,
            &[key],
            &[cost, capacity, refill_rate, now]
        ).await?;
        
        if !allowed {
            return Err(RateLimitExceeded);
        }
        Ok(())
    }
}
```

---

## 9. Deployment Architecture

### 9.1 Kubernetes Topology

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  KUBERNETES CLUSTER (EKS / GKE / AKS)                                       │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  NAMESPACE: usora-edge                                               │    │
│  │  • Ingress Controller (NGINX / Traefik)                              │    │
│  │  • Cert Manager (Let's Encrypt / private CA)                         │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  NAMESPACE: usora-gateway                                            │    │
│  │  • Deployment: gateway (Rust) — 5 replicas, HPA 3-20                 │    │
│  │    - CPU: 2 cores, Memory: 1Gi per pod                               │    │
│  │    - Requests: 1000m CPU, 512Mi memory                               │    │
│  │  • Service: gateway (ClusterIP)                                      │    │
│  │  • PodDisruptionBudget: minAvailable: 3                              │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  NAMESPACE: usora-orchestration                                      │    │
│  │  • Deployment: orchestration (Java) — 3 replicas, HPA 3-10           │    │
│  │    - CPU: 4 cores, Memory: 8Gi per pod (Virtual Threads)             │    │
│  │    - JVM: -XX:+UseContainerSupport, -XX:MaxRAMPercentage=75.0        │    │
│  │  • StatefulSet: camunda-db (PostgreSQL) — 1 replica                  │    │
│  │  • Service: orchestration (ClusterIP)                                │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  NAMESPACE: usora-compute                                            │    │
│  │  • Deployment: compute-document (Rust) — 5 replicas, HPA 5-50        │    │
│  │    - CPU: 8 cores, Memory: 16Gi per pod                              │    │
│  │  • Deployment: compute-biometric (Rust) — 3 replicas, HPA 3-30       │    │
│  │    - CPU: 8 cores, Memory: 16Gi per pod                              │    │
│  │  • Deployment: compute-risk (Rust) — 3 replicas, HPA 3-20            │    │
│  │    - CPU: 4 cores, Memory: 8Gi per pod                               │    │
│  │  • Deployment: compute-fraud (Rust) — 2 replicas, HPA 2-10           │    │
│  │    - CPU: 8 cores, Memory: 32Gi per pod (graph analysis)             │    │
│  │  • Service: compute-* (ClusterIP)                                    │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  NAMESPACE: usora-data                                               │    │
│  │  • StatefulSet: postgres-primary — 1 replica, 500Gi SSD              │    │
│  │  • StatefulSet: postgres-replica — 2 replicas, 500Gi SSD             │    │
│  │  • StatefulSet: redis-cluster — 6 replicas (3 master, 3 replica)     │    │
│  │  • StatefulSet: kafka — 3 replicas, 1Ti SSD each                     │    │
│  │  • StatefulSet: clickhouse — 2 replicas, 2Ti SSD each                │    │
│  │  • Deployment: elasticsearch — 3 replicas                            │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  NAMESPACE: usora-observability                                      │    │
│  │  • Deployment: prometheus — 1 replica                                │    │
│  │  • Deployment: grafana — 2 replicas                                  │    │
│  │  • Deployment: jaeger — 1 replica                                    │    │
│  │  • Deployment: loki — 1 replica                                      │    │
│  │  • Deployment: alertmanager — 2 replicas                             │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  NAMESPACE: usora-security                                           │    │
│  │  • StatefulSet: vault — 3 replicas (HA mode)                         │    │
│  │  • DaemonSet: falco (runtime security)                               │    │
│  │  • Deployment: cert-manager                                          │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 9.2 Multi-Region Deployment

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           GLOBAL LOAD BALANCER                                │
│                    (AWS Route 53 / CloudFlare Load Balancing)                 │
│                         Latency-based + Geolocation                           │
└─────────────────────────────────────────────────────────────────────────────┘
          │                      │                      │
          ▼                      ▼                      ▼
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│  REGION: us-east │    │  REGION: eu-west │    │  REGION: ap-south│
│  (Primary)       │    │  (Active)        │    │  (Active)        │
│                  │    │                  │    │                  │
│  • Full stack    │◀──▶│  • Full stack    │◀──▶│  • Full stack    │
│  • Write leader  │    │  • Write leader  │    │  • Write leader  │
│  • Read replicas │    │  • Read replicas │    │  • Read replicas │
│                  │    │                  │    │                  │
│  Kafka: Leader   │◀──▶│  Kafka: Follower │◀──▶│  Kafka: Follower │
│  Postgres: Primary│◀──▶│  Postgres: Replica│◀──▶│  Postgres: Replica│
│  Redis: Master   │◀──▶│  Redis: Replica  │◀──▶│  Redis: Replica  │
│                  │    │                  │    │                  │
│  Cross-region    │    │  Cross-region    │    │  Cross-region    │
│  replication:    │    │  replication:    │    │  replication:    │
│  <50ms latency   │    │  <50ms latency   │    │  <50ms latency   │
└─────────────────┘    └─────────────────┘    └─────────────────┘
```

**Failover Strategy:**
- Automatic failover to nearest healthy region
- DNS TTL: 30 seconds for rapid propagation
- Data replication lag monitoring: alert if >5 seconds
- Read-only mode during partial outages (writes queued)

---

## 10. Observability Design

### 10.1 Metrics

**Gateway Metrics (Prometheus):**
```
# Request rate by endpoint, tenant, status
usora_gateway_requests_total{tenant_id, method, path, status}

# Request latency histogram
usora_gateway_request_duration_seconds_bucket{tenant_id, method, path, le}

# Active connections
gateway_active_connections{tenant_id, protocol}

# Rate limit hits
usora_gateway_rate_limit_hits_total{tenant_id, limit_type}

# Circuit breaker state
usora_gateway_circuit_breaker_state{upstream, state}

# Cache hit/miss ratio
usora_gateway_cache_hits_total{tenant_id, cache_name}
usora_gateway_cache_misses_total{tenant_id, cache_name}
```

**Orchestration Metrics:**
```
# Workflow execution
usora_orchestration_workflow_duration_seconds{workflow_id, tenant_id, status}
usora_orchestration_workflow_active{workflow_id, tenant_id}

# Task queue depth
usora_orchestration_task_queue_depth{task_type, tenant_id}

# Manual review SLA
usora_orchestration_review_wait_seconds{tenant_id, priority}
```

**Compute Metrics:**
```
# Task processing
usora_compute_tasks_processed_total{worker_type, tenant_id, status}
usora_compute_task_duration_seconds{worker_type, tenant_id, model_version}

# Model inference
usora_compute_inference_duration_seconds{model_name, model_version, tenant_id}
usora_compute_inference_errors_total{model_name, error_type}

# Resource utilization
usora_compute_cpu_usage_percent{worker_type, pod_name}
usora_compute_memory_usage_bytes{worker_type, pod_name}
```

### 10.2 Distributed Tracing

**Trace Structure:**
```
Trace: verification_abc123
├── Span: gateway_receive (Rust)
│   ├── Span: tenant_resolution (Rust)
│   ├── Span: auth_validation (Rust)
│   ├── Span: rate_limit_check (Rust)
│   └── Span: route_to_orchestration (Rust)
│       └── Span: orchestration_process (Java)
│           ├── Span: workflow_start (Java/Camunda)
│           ├── Span: document_task_dispatch (Java)
│           │   └── Span: compute_document_analysis (Rust)
│           │       ├── Span: image_download (Rust)
│           │       ├── Span: preprocessing (Rust)
│           │       ├── Span: model_inference (Rust)
│           │       ├── Span: ocr_extraction (Rust)
│           │       └── Span: result_publish (Rust)
│           ├── Span: biometric_task_dispatch (Java)
│           │   └── Span: compute_biometric_analysis (Rust)
│           ├── Span: risk_scoring (Java)
│           └── Span: workflow_complete (Java)
└── Span: gateway_respond (Rust)
```

**Context Propagation:**
- W3C Trace Context headers (`traceparent`, `tracestate`)
- Baggage for tenant_id, user_id, request_id
- OpenTelemetry SDK in all services

### 10.3 Alerting Rules

```yaml
# Critical alerts (P1)
- alert: GatewayHighErrorRate
  expr: rate(usora_gateway_requests_total{status=~"5.."}[5m]) > 0.01
  for: 2m
  severity: P1
  
- alert: OrchestrationWorkflowStuck
  expr: usora_orchestration_workflow_active > 10000
  for: 5m
  severity: P1

- alert: ComputeTaskQueueBacklog
  expr: usora_orchestration_task_queue_depth > 100000
  for: 3m
  severity: P1

# Warning alerts (P2)
- alert: GatewayHighLatency
  expr: histogram_quantile(0.99, usora_gateway_request_duration_seconds) > 0.1
  for: 5m
  severity: P2

- alert: DatabaseReplicationLag
  expr: pg_replication_lag_seconds > 5
  for: 2m
  severity: P2

# Info alerts (P3)
- alert: HighRateLimitHits
  expr: rate(usora_gateway_rate_limit_hits_total[5m]) > 100
  for: 10m
  severity: P3
```

---

## 11. Scalability Design

### 11.1 Horizontal Scaling Strategy

| Layer | Scaling Trigger | Max Scale | Bottleneck |
|---|---|---|---|
| Gateway | CPU >70% or connections >80% | 100 pods | Network bandwidth |
| Orchestration | Task queue depth >1000 | 50 pods | Database connections |
| Compute (Document) | Queue lag >30s | 200 pods | GPU/CPU availability |
| Compute (Biometric) | Queue lag >30s | 150 pods | Memory for FAISS index |
| PostgreSQL | CPU >80% | Read replicas | Write throughput |
| Redis | Memory >80% | Cluster shards | Network I/O |
| Kafka | Consumer lag >1000 | Add partitions | Disk throughput |

### 11.2 Backpressure Handling

```
Client ──▶ Gateway ──▶ Orchestration ──▶ Kafka ──▶ Compute
              │              │              │           │
              │              │              │           │
              ▼              ▼              ▼           ▼
         Rate Limit    Queue Depth    Partition   Worker Pool
         (Token Bucket)  Monitor      Lag Alert   Backlog
                                                    │
                                                    ▼
                                              Shed Load
                                              (return 503
                                               with Retry-After)
```

**Backpressure Signals:**
- Gateway: 429 Too Many Requests (rate limit)
- Gateway: 503 Service Unavailable (upstream overload)
- Kafka: Consumer group lag metrics
- Compute: Task queue depth, worker pool saturation

---

## 12. Disaster Recovery

### 12.1 RPO / RTO Targets

| Data Class | RPO | RTO | Strategy |
|---|---|---|---|
| Verification transactions | 0 (synchronous replication) | 15 min | Multi-region active-active |
| Audit logs | 0 (append-only, replicated) | 15 min | Kafka MirrorMaker 2 |
| Document images | 1 hour | 30 min | S3 cross-region replication |
| Analytics data | 4 hours | 2 hours | ClickHouse replication |
| Configuration | 0 (GitOps) | 5 min | Git + ArgoCD |

### 12.2 Backup Strategy

```
PostgreSQL:
  • Continuous WAL archiving to S3
  • Hourly incremental backups
  • Daily full backups (retained 30 days)
  • Weekly backups (retained 1 year)
  • Monthly backups (retained 7 years)

Kafka:
  • Topic replication factor: 3
  • Min ISR: 2
  • Retention: 30 days (events), 7 years (audit)

S3 Documents:
  • Versioning enabled
  • Cross-region replication
  • Object lock (WORM) for audit documents
  • Lifecycle: Glacier after 90 days
```

---

## 13. Development Guidelines

### 13.1 Rust Guidelines

```rust
// Error handling: use thiserror for domain errors, anyhow for binaries
#[derive(thiserror::Error, Debug)]
pub enum GatewayError {
    #[error("tenant not found: {0}")]
    TenantNotFound(Uuid),
    #[error("rate limit exceeded for tenant: {0}")]
    RateLimitExceeded(Uuid),
    #[error("authentication failed: {0}")]
    AuthenticationFailed(String),
    #[error("upstream unavailable: {service}")]
    UpstreamUnavailable { service: String },
    #[error("internal error")]
    Internal(#[from] anyhow::Error),
}

// Async patterns: prefer structured concurrency
use tokio::task::JoinSet;

async fn process_batch(tasks: Vec<Task>) -> Vec<Result<TaskResult>> {
    let mut set = JoinSet::new();
    for task in tasks {
        set.spawn(process_single(task));
    }
    
    let mut results = Vec::new();
    while let Some(res) = set.join_next().await {
        results.push(res?);
    }
    results
}

// Metrics: use prometheus crate with labels
use prometheus::{CounterVec, HistogramVec, Registry};

lazy_static! {
    static ref REQUEST_DURATION: HistogramVec = register_histogram_vec!(
        "usora_gateway_request_duration_seconds",
        "Request duration",
        &["tenant_id", "method", "path", "status"],
        vec![0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0]
    ).unwrap();
}

// Tracing: use tracing crate with structured fields
use tracing::{info, instrument};

#[instrument(skip(req), fields(tenant_id = %req.tenant_id, request_id = %req.request_id))]
async fn handle_request(req: Request) -> Result<Response> {
    info!(method = %req.method, path = %req.path, "handling request");
    // ...
}
```

### 13.2 Java Guidelines

```java
// Use Virtual Threads for I/O-bound operations
@Service
public class VerificationService {
    
    @Async("virtualThreadExecutor")
    public CompletableFuture<Verification> processVerification(UUID id) {
        // Runs on a Virtual Thread — millions possible
        return CompletableFuture.supplyAsync(() -> {
            var verification = repository.findById(id);
            workflowEngine.start(verification);
            return verification;
        });
    }
}

// Configuration
@Configuration
public class ThreadConfig {
    @Bean
    public Executor virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

// Domain modeling with records and sealed classes
public sealed interface VerificationResult 
    permits Approved, Rejected, Escalated, Pending {}

public record Approved(UUID verificationId, RiskScore score, Instant timestamp) 
    implements VerificationResult {}

public record Rejected(UUID verificationId, List<RejectionReason> reasons, Instant timestamp) 
    implements VerificationResult {}

// Structured logging with Micrometer + SLF4J
private static final Logger log = LoggerFactory.getLogger(VerificationService.class);

public void completeVerification(Verification v) {
    log.atInfo()
        .setMessage("Verification completed")
        .addKeyValue("verificationId", v.getId())
        .addKeyValue("tenantId", v.getTenantId())
        .addKeyValue("status", v.getStatus())
        .addKeyValue("durationMs", v.getDuration().toMillis())
        .log();
}
```

---

## 14. Decision Log

| Date | Decision | Context | Alternatives | Rationale |
|---|---|---|---|---|
| 2026-01 | Rust for API Gateway | High-concurrency ingress | Kong, NGINX, Envoy | Zero-GC latency, custom tenant logic, full control |
| 2026-01 | Java 21 for Orchestration | Business workflow engine | Go, Rust, Node.js | Camunda ecosystem, Virtual Threads, enterprise patterns |
| 2026-01 | Rust for Compute | CPU-intensive ML inference | Python, C++ | Memory safety, no GC pauses, safe parallelism |
| 2026-02 | Custom Gateway vs Kong | API Gateway selection | Kong, AWS API GW, Envoy | Tenant-aware rate limiting, protocol translation, embedded logic |
| 2026-02 | Schema-per-Tenant vs Row-Level | Database isolation | Shared table, separate DB | Balance of isolation and operational simplicity |
| 2026-03 | Kafka over RabbitMQ | Event bus | RabbitMQ, NATS, Pulsar | Ecosystem maturity, replay capability, partition scaling |
| 2026-03 | gRPC over REST internal | Inter-service protocol | REST, GraphQL | Binary efficiency, streaming, strong typing |
| 2026-04 | Camunda over Temporal | Workflow engine | Temporal, Cadence, custom | BPMN standard, enterprise adoption, Java native |
| 2026-04 | FAISS over custom ANN | Biometric search | Custom LSH, Annoy | Proven accuracy, GPU acceleration, active development |
| 2026-05 | HashiCorp Vault over AWS SM | Secret management | AWS Secrets Manager, Azure Key Vault | Multi-cloud portability, dynamic credentials, PKI |
| 2026-05 | ClickHouse over BigQuery | Analytics | BigQuery, Snowflake, Druid | Real-time analytics, self-hosted, cost efficiency |
| 2026-06 | Rustls over OpenSSL | TLS implementation | OpenSSL, BoringSSL | Memory safety, simpler API, no external C dependency |

---

## 15. Document Information

| Field | Value |
|---|---|
| **Document Version** | 1.0.0 |
| **Last Updated** | 2026-07-21 |
| **Author** | USORA Architecture Team |
| **Review Cycle** | Monthly |
| **Classification** | Internal — Confidential |
| **Next Review** | 2026-08-21 |

---

*USORA — Trust at Scale*
