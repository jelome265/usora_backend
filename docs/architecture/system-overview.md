# USORA System Architecture Overview

> **Version:** 1.0.0  
> **Last Updated:** 2026-07-25  
> **Classification:** Internal — Engineering Reference  
> **Owner:** USORA Architecture Team  
> **Status:** Approved

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Architecture Principles](#2-architecture-principles)
3. [4-Layer Architecture](#3-4-layer-architecture)
4. [Technology Stack](#4-technology-stack)
5. [Inter-Service Communication](#5-inter-service-communication)
6. [Multi-Tenancy Strategy](#6-multi-tenancy-strategy)
7. [Security Architecture](#7-security-architecture)
8. [Performance Targets](#8-performance-targets)
9. [Deployment Architecture](#9-deployment-architecture)
10. [Observability Stack](#10-observability-stack)

---

## 1. Executive Summary

The USORA KYC Platform is a polyglot, multi-tenant identity verification system architected to process millions of verifications daily across 200+ countries and 7,000+ document types. The platform combines Rust and Java microservices with ML-powered compute workers to deliver sub-100ms p99 verification latency while maintaining hardware-enforced tenant isolation and blockchain-anchored audit trails.

The architecture follows a strict 4-layer separation of concerns: a Rust-based API Gateway handles all client-facing traffic with zero-GC latency; a Java 21 + Spring Boot Orchestration layer manages complex BPMN workflows and business logic; Rust-based Compute workers execute ML inference for document/biometric analysis and risk scoring; and a multi-model Data layer provides persistence, caching, streaming, and analytics. All inter-service communication uses gRPC with mutual TLS, with Kafka providing asynchronous event distribution.

The platform is designed for 99.99% uptime through multi-region active-active deployment with automatic failover, and meets regulatory requirements including GDPR, AML5/6, eIDAS, SOC 2, ISO 27001, and KSA/KFS.

---

## 2. Architecture Principles

The following five immutable principles govern every architectural decision:

### 2.1 Performance as a Feature

Every millisecond matters in identity verification. User abandonment spikes exponentially with latency. The architecture is optimized for sub-100ms end-to-end response times at the 99.9th percentile, not just median cases. This drives:
- Zero-GC gateway in Rust (no stop-the-world pauses)
- Connection pooling and multiplexing at every layer
- In-memory caches with predictable hit rates
- Direct compute bypass path for simple operations
- Priority-based task scheduling (express, standard, batch)

### 2.2 Security by Construction

Security is not bolted on; it is the foundation. Tenant isolation, encryption, and audit trails are architectural invariants enforced by the type system, runtime checks, and infrastructure. This drives:
- mTLS for all inter-service communication (SPIFFE/SPIRE)
- Schema-per-tenant in PostgreSQL with Row-Level Security
- Per-tenant encryption keys in HashiCorp Vault
- Immutable audit logs with hash-chain integrity
- Zero-trust network model with deny-all default policies

### 2.3 Failure is Normal

Services fail, networks partition, disks corrupt. The system is designed to degrade gracefully, recover automatically, and never lose data. This drives:
- Circuit breakers on every inter-service call (per-tenant, per-upstream)
- Saga pattern for distributed transaction compensation
- Multi-region active-active deployment
- Automatic failover with 30s DNS TTL
- Dead-letter queues with exponential backoff retry

### 2.4 Observability is Mandatory

If you cannot measure it, you cannot operate it. Every request, every decision, every failure is traced, logged, and metered. This drives:
- OpenTelemetry tracing with W3C Trace Context propagation
- Structured JSON logging with trace correlation
- Prometheus metrics on every request/response cycle
- Real-time alerting with defined SLOs and burn-rate thresholds
- Distributed tracing across all 4 layers

### 2.5 Change is Constant

Regulations evolve, fraud techniques adapt, scale demands shift. The architecture supports independent deployment, feature flags, and A/B testing. This drives:
- Independent service deployment (each service owns its release cadence)
- Feature flags via LaunchDarkly-style configuration
- Canary deployments and traffic splitting at the gateway
- API versioning with 12-month deprecation windows
- Tenant-specific BPMN workflows and compliance rules

---

## 3. 4-Layer Architecture

```
┌──────────────────────────────────────────────────────────────────────────────────────┐
│                                CLIENT LAYER                                            │
│                                                                                        │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐  ┌────────────────┐ │
│  │   Web Portal     │  │   Mobile SDK     │  │   API Clients    │  │  3rd Party     │ │
│  │   (React 19)     │  │  (iOS/Android)   │  │  (REST/gRPC)     │  │  Integrations  │ │
│  │   Admin +        │  │   Native KYC     │  │   Server-to-     │  │  Webhook       │ │
│  │   Applicant UI   │  │   Flow UI        │  │   Server         │  │  Consumers     │ │
│  └────────┬─────────┘  └────────┬─────────┘  └────────┬─────────┘  └───────┬────────┘ │
└───────────┼─────────────────────┼─────────────────────┼─────────────────────┼──────────┘
            │                     │                     │                     │
            │   TLS 1.3           │   TLS 1.3           │   mTLS              │   HTTPS
            ▼                     ▼                     ▼                     ▼
┌──────────────────────────────────────────────────────────────────────────────────────┐
│                                    EDGE LAYER                                          │
│                                                                                        │
│  ┌────────────────────────────────────────────────────────────────────────────────┐   │
│  │              CloudFlare / AWS CloudFront + AWS Shield + Route 53               │   │
│  │                                                                                │   │
│  │  • DDoS Protection (L3/L4/L7)      • CDN (static assets, edge caching)        │   │
│  │  • Web Application Firewall (WAF)  • Geo-routing (latency-based DNS)           │   │
│  │  • Bot Management                  • SSL/TLS termination at edge               │   │
│  └────────────────────────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────────────────┘
            │
            │   TLS 1.3 (origin pull)
            ▼
┌──────────────────────────────────────────────────────────────────────────────────────┐
│  LAYER 1: API GATEWAY  (Rust + Tokio — Custom Implementation)                         │
│                                                                                        │
│  ┌────────────────────────────────────────────────────────────────────────────────┐   │
│  │  PROTOCOL SERVERS                                                                │   │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌────────────────────────────┐   │   │
│  │  │  Axum HTTP/2    │  │  Tonic gRPC      │  │  Tokio-Tungstenite WS     │   │   │
│  │  │  (REST API)     │  │  (Internal)      │  │  (Real-time streaming)    │   │   │
│  │  └──────────────────┘  └──────────────────┘  └────────────────────────────┘   │   │
│  └────────────────────────────────────────────────────────────────────────────────┘   │
│                                    │                                                   │
│                                    ▼                                                   │
│  ┌────────────────────────────────────────────────────────────────────────────────┐   │
│  │  TOWER MIDDLEWARE PIPELINE  (per-request, ordered execution)                     │   │
│  │                                                                                  │   │
│  │   1. TLS Termination          (rustls, TLS 1.3, mTLS client cert validation)     │   │
│  │   2. Request ID Injection     (UUID v7, sortable, X-Request-ID header)           │   │
│  │   3. Trace Context Propagation (OpenTelemetry W3C Trace Context)                 │   │
│  │   4. DDoS / WAF Filtering     (per-IP rate limit, geo-block, bot detection)      │   │
│  │   5. Tenant Resolution        (subdomain -> tenant_id, X-Tenant-ID fallback)     │   │
│  │   6. Authentication           (JWT validation, mTLS cert extraction, API Key)    │   │
│  │   7. Authorization            (RBAC/ABAC via OPA/Rego, cached policy decisions)  │   │
│  │   8. Rate Limiting            (token bucket per tenant, Redis-backed sliding win)│   │
│  │   9. Request Validation       (OpenAPI schema validation, max body size 50MB)    │   │
│  │  10. Circuit Breaker          (per-tenant, per-upstream, 50% error threshold)    │   │
│  │  11. Request Transformation   (REST -> gRPC, header injection, body transform)   │   │
│  └────────────────────────────────────────────────────────────────────────────────┘   │
│                                    │                                                   │
│                                    ▼                                                   │
│  ┌────────────────────────────────────────────────────────────────────────────────┐   │
│  │  ROUTING ENGINE                                                                  │   │
│  │  • Service Discovery        (Kubernetes DNS / Consul)                            │   │
│  │  • Load Balancing           (least-connections, weighted, locality-prioritized)  │   │
│  │  • Canary / Blue-Green      (header/cookie-based traffic split)                  │   │
│  │  • Sticky Sessions          (WebSocket affinity via cookie)                      │   │
│  │  • Direct Compute Bypass    (health, metrics skip orchestration)                 │   │
│  └────────────────────────────────────────────────────────────────────────────────┘   │
│                                    │                                                   │
│                                    ▼                                                   │
│  ┌────────────────────────────────────────────────────────────────────────────────┐   │
│  │  RESPONSE PIPELINE                                                               │   │
│  │  • gRPC -> REST Translation         (Protobuf -> JSON, field mapping)            │   │
│  │  • Response Caching                 (Redis, TTL per endpoint, Cache-Control)     │   │
│  │  • Audit Log Emission              (async Kafka, non-blocking, WAL-buffered)     │   │
│  │  • Metrics Recording               (Prometheus counters, histograms, summaries)  │   │
│  │  • Distributed Tracing Span Closure (OpenTelemetry, parent span finalization)    │   │
│  └────────────────────────────────────────────────────────────────────────────────┘   │
└──────────────────────────┬───────────────────────────────────────────────────────────┘
                           │
              ┌────────────┴────────────┐
              │                         │
              │ gRPC + mTLS             │ gRPC + mTLS (bypass)
              │ (SPIFFE/SPIRE)          │ (health, metrics)
              ▼                         ▼
┌─────────────────────────────┐  ┌─────────────────────────────┐
│  LAYER 2: ORCHESTRATION     │  │  LAYER 2b: COMPUTE DIRECT   │
│  (Java 21 + Spring Boot)    │  │  (Rust + Tokio)             │
│                             │  │                             │
│  • Camunda BPMN Engine      │  │  • Health probes            │
│  • Business Services        │  │  • Metrics endpoints        │
│  • Saga State Management    │  │  • Simple inference         │
│  • State Machines           │  │    (bypass orchestration)   │
│  • Compliance Rules Engine  │  │                             │
│  • Case Management          │  │                             │
│  • Webhook Delivery         │  │                             │
└─────────────┬───────────────┘  └─────────────────────────────┘
              │
              │ gRPC + mTLS / Kafka Events
              ▼
┌──────────────────────────────────────────────────────────────────────────────────────┐
│  LAYER 3: COMPUTE  (Rust + Tokio — High-Throughput Worker Pools)                     │
│                                                                                        │
│  ┌────────────────────────────────────────────────────────────────────────────────┐   │
│  │  TASK CONSUMER  (rdkafka, async consumer group per worker type)                  │   │
│  │  • Manual offset commit       (at-least-once delivery semantics)                 │   │
│  │  • Dead-letter topic          (max 3 retries, exponential backoff + jitter)      │   │
│  │  • Priority partitioning      (P0 express, P1 standard, P2 batch)                │   │
│  │  • Batch consumption          (max 100 messages per poll, tuned throughput)       │   │
│  └────────────────────────────────────────────────────────────────────────────────┘   │
│                                    │                                                   │
│                                    ▼                                                   │
│  ┌────────────────────────────────────────────────────────────────────────────────┐   │
│  │  WORKER POOLS  (Tokio runtime, per-tenant resource quotas enforced)             │   │
│  │                                                                                  │   │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐  ┌──────────┐ │   │
│  │  │   Document       │  │   Biometric      │  │   Risk Scoring   │  │  Fraud   │ │   │
│  │  │   Analysis       │  │   Matching       │  │   Engine         │  │  Detect  │ │   │
│  │  │                  │  │                  │  │                  │  │          │ │   │
│  │  │  • OCR (Tesseract)│  │  • Face Detect   │  │  • XGBoost       │  │  • GNN   │ │   │
│  │  │  • ML Inference  │  │  • Liveness      │  │  • Neural Net    │  │  • Link  │ │   │
│  │  │  • Forensic      │  │  • Template      │  │  • Feature Store │  │  • Anom  │ │   │
│  │  │  • Tamper Detect │  │  • FAISS Search  │  │  • Ensemble      │  │  • Graph │ │   │
│  │  │  CPU-bound       │  │  CPU-bound       │  │  Mixed async+CPU │  │  Mixed   │ │   │
│  │  │  spawn_blocking  │  │  spawn_blocking  │  │  tokio tasks     │  │  tokio+  │ │   │
│  │  └──────────────────┘  └──────────────────┘  └──────────────────┘  └──────────┘ │   │
│  └────────────────────────────────────────────────────────────────────────────────┘   │
│                                    │                                                   │
│                                    ▼                                                   │
│  ┌────────────────────────────────────────────────────────────────────────────────┐   │
│  │  RESULT PUBLISHER                                                               │   │
│  │  • Kafka result topic          (Verification results for orchestration)          │   │
│  │  • Webhook delivery            (HTTP POST with HMAC-SHA256 signature)            │   │
│  │  • Metrics + tracing emission  (Prometheus + OpenTelemetry)                      │   │
│  │  • Audit log emission          (async, immutable, hash-chained)                  │   │
│  └────────────────────────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────────────────┘
            │
            │  JDBC / SQLx / RESP / S3 API / ES API / ClickHouse Native Protocol
            ▼
┌──────────────────────────────────────────────────────────────────────────────────────┐
│  LAYER 4: DATA LAYER                                                                   │
│                                                                                        │
│  ┌────────────────────┐  ┌────────────────────┐  ┌────────────────────┐  ┌───────────┐│
│  │    PostgreSQL 16    │  │     Redis 7         │  │     Kafka 3.x      │  │  S3/MinIO ││
│  │                     │  │                     │  │                     │  │           ││
│  │  • Schema-per-     │  │  • Session store    │  │  • Event bus        │  │  • Docs   ││
│  │    tenant          │  │  • Cache layer      │  │  • Async workflows  │  │  • Images  ││
│  │  • Row-Level Sec   │  │  • Rate limit       │  │  • Audit log stream │  │  • Artifac ││
│  │  • PgBouncer pool  │  │  • Distributed lock │  │  • MirrorMaker2     │  │  • Reports ││
│  │  • WAL archiving   │  │  • Cluster mode     │  │  • Tiered storage   │  │  • Lifecycle│
│  └────────────────────┘  └────────────────────┘  └────────────────────┘  └───────────┘│
│                                                                                        │
│  ┌────────────────────┐  ┌────────────────────┐  ┌────────────────────┐  ┌───────────┐│
│  │    ClickHouse       │  │   Elasticsearch     │  │   Blockchain       │  │ Vault     ││
│  │                     │  │                     │  │   (Polygon L2)     │  │           ││
│  │  • Analytics WH    │  │  • Full-text search │  │  • Audit anchor    │  │  • Secrets ││
│  │  • Time-series     │  │  • Audit log index  │  │  • Merkle root     │  │  • Keys    ││
│  │  • Aggregations    │  │  • Compliance search │  │  • Tamper evidence │  │  • PKI     ││
│  │  • Tenant-partition│  │  • Tenant-isolated   │  │  • Hourly batches  │  │  • Dynamic ││
│  └────────────────────┘  └────────────────────┘  └────────────────────┘  └───────────┘│
└──────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Technology Stack

| Layer | Component | Technology | Version | Purpose |
|-------|-----------|-----------|---------|---------|
| **Edge** | CDN / DDoS | CloudFlare | Enterprise | DDoS protection, CDN, WAF, geo-routing |
| **Edge** | DNS | AWS Route 53 | — | Latency-based routing, health checks |
| **Gateway** | HTTP Server | Axum | 0.8+ | Async HTTP/2 server, Tower middleware |
| **Gateway** | gRPC Server | Tonic | 0.12+ | gRPC server for internal APIs |
| **Gateway** | TLS | rustls | 0.23+ | TLS 1.3 termination, mTLS validation |
| **Gateway** | Runtime | Tokio | 1.x | Async runtime, multi-threaded scheduler |
| **Gateway** | Tracing | OpenTelemetry | 0.27+ | Distributed tracing, span management |
| **Gateway** | Metrics | Prometheus (rust) | 0.23+ | Metrics exposition, histograms |
| **Gateway** | JWT | jsonwebtoken (rust) | 9.x | JWT validation, Ed25519/RS256 |
| **Gateway** | Policy Engine | OPA/Rego (wasm) | 0.68+ | Authorization policy evaluation |
| **Orchestration** | Runtime | Java | 21 LTS | Virtual Threads, pattern matching |
| **Orchestration** | Framework | Spring Boot | 4.1+ | IoC, AOP, configuration, Actuator |
| **Orchestration** | Workflow Engine | Camunda | 8.6+ | BPMN 2.0 process engine |
| **Orchestration** | Database | PostgreSQL (via JDBC) | 16 | Workflow state, business data |
| **Orchestration** | gRPC Client | grpc-java | 1.66+ | Inter-service communication |
| **Orchestration** | Kafka Client | spring-kafka | 3.2+ | Event publishing and consumption |
| **Orchestration** | Circuit Breaker | Resilience4j | 2.2+ | Fault tolerance, retry, bulkhead |
| **Compute** | Runtime | Tokio | 1.x | Async worker pool runtime |
| **Compute** | ML Inference | ONNX Runtime (rust) | 1.19+ | Cross-platform ML model inference |
| **Compute** | OCR | Tesseract (rust bindings) | 5.5+ | Optical character recognition |
| **Compute** | Face Detection | RetinaFace / MTCNN | — | Face detection, landmark extraction |
| **Compute** | Face Matching | ArcFace / AdaFace | — | Deep learning embedding (512-dim) |
| **Compute** | Vector Search | FAISS (rust bindings) | 1.9+ | Approximate nearest neighbor search |
| **Compute** | Liveness Detection | Custom CNN | — | Passive + active liveness analysis |
| **Compute** | Risk Model | XGBoost (rust) | 2.1+ | Gradient-boosted decision trees |
| **Compute** | Fraud Detection | Graph Neural Net | — | Link analysis, anomaly detection |
| **Compute** | Image Processing | image-rs | 0.25+ | Image loading, resize, color conversion |
| **Data** | Primary DB | PostgreSQL | 16.4 | ACID transactions, JSONB, partitioning |
| **Data** | Connection Pool | PgBouncer | 1.23+ | Transaction-level pooling |
| **Data** | Cache | Redis | 7.2+ | Session, rate-limit, response cache |
| **Data** | Event Bus | Kafka (MSK) | 3.7+ | Event streaming, log compaction |
| **Data** | Object Storage | S3 / MinIO | — | Document images, artifacts |
| **Data** | Analytics | ClickHouse | 24.x | Real-time analytics, aggregations |
| **Data** | Search | Elasticsearch | 8.15+ | Full-text search, audit indexing |
| **Data** | Secrets | HashiCorp Vault | 1.18+ | Secret storage, PKI, dynamic creds |
| **Frontend** | Framework | React | 19+ | UI component model |
| **Frontend** | Meta-framework | Next.js | 15+ | SSR, RSC, App Router |
| **Frontend** | Styling | Tailwind CSS | 4+ | Utility-first CSS |
| **Frontend** | Mobile | React Native | 0.76+ | iOS/Android SDK |
| **Platform** | Container | Docker | 27+ | Container runtime |
| **Platform** | Orchestration | Kubernetes (EKS) | 1.31+ | Container orchestration |
| **Platform** | GitOps | ArgoCD | 2.12+ | Declarative deployments |
| **Platform** | IaC | Terraform | 1.9+ | Infrastructure provisioning |
| **Platform** | Service Mesh | SPIFFE/SPIRE + Cilium | 1.16+ | Identity-based mTLS, eBPF networking |
| **Platform** | Runtime Security | Falco | 0.39+ | Container security monitoring |
| **Observability** | Tracing | OpenTelemetry | 1.30+ | Distributed traces (W3C Trace Context) |
| **Observability** | Metrics | Prometheus | 2.54+ | Time-series metrics |
| **Observability** | Dashboards | Grafana | 11.3+ | Visualization, alerting |
| **Observability** | Logs | Loki | 3.2+ | Log aggregation |
| **Observability** | Tracing Backend | Tempo | 2.6+ | Trace storage and querying |
| **Observability** | Alerting | Alertmanager | 0.28+ | Alert routing, deduplication |

---

## 5. Inter-Service Communication

### 5.1 Synchronous: gRPC with mTLS

All inter-service synchronous communication uses gRPC with protocol buffers v3 for schema definition and mTLS for authentication and encryption.

```
┌──────────────┐          gRPC + mTLS           ┌──────────────────┐
│              │  ──────────────────────────▶    │                  │
│    Gateway   │  (SPIFFE SVID presented)        │  Orchestration   │
│    (Rust)    │  ◀──────────────────────────    │  (Java)          │
│              │                                 │                  │
└──────────────┘                                 └──────────────────┘
       │                                                   │
       │   gRPC + mTLS                                     │  gRPC + mTLS
       │   (bypass path)                                   │
       ▼                                                   ▼
┌──────────────┐                                 ┌──────────────────┐
│   Compute    │                                 │    Audit         │
│   (Direct)   │                                 │    Service       │
└──────────────┘                                 └──────────────────┘
```

**gRPC Service Definitions:**

| Service | Producer | Consumer | Proto Package |
|---------|----------|----------|--------------|
| `GatewayService` | Gateway | Clients | `usora.gateway.v1` |
| `OrchestrationService` | Orchestration | Gateway | `usora.orchestration.v1` |
| `DocumentAnalysisService` | Compute | Orchestration | `usora.compute.v1` |
| `BiometricAnalysisService` | Compute | Orchestration | `usora.compute.v1` |
| `RiskScoringService` | Compute | Orchestration | `usora.compute.v1` |
| `AuditService` | Audit | All | `usora.audit.v1` |
| `ComplianceService` | Compliance | Orchestration | `usora.compliance.v1` |

**mTLS Configuration:**
- Certificate issuance: SPIFFE/SPIRE, auto-rotated every 24 hours
- Certificate validation: SPIFFE ID verification (spiffe://usora.io/{service}/{pod})
- Cipher suites: TLS_AES_256_GCM_SHA384 (TLS 1.3 only)
- Mutual verification: Both sides present and validate certificates
- Connection pooling: gRPC keepalive (10s ping, 30s timeout)

### 5.2 Asynchronous: Kafka Events

Long-running operations and event notifications use Kafka for asynchronous communication.

```
                    ┌──────────────────────┐
                    │      Kafka Cluster    │
                    │   (3 brokers, RF=3)   │
                    └──────────────────────┘
                             │
         ┌───────────────────┼───────────────────┐
         │                   │                   │
         ▼                   ▼                   ▼
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│   Gateway    │    │Orchestration │    │   Compute    │
│  (Produce:   │    │ (Produce:    │    │ (Produce:    │
│   commands)  │    │  tasks,      │    │  results)    │
│  (Consume:   │    │  events)     │    │ (Consume:    │
│   responses) │    │ (Consume:    │    │  tasks)      │
└──────────────┘    │  results)    │    └──────────────┘
                    └──────────────┘
```

**Topic Design:**

| Topic | Partitions | Retention | Schema | Key |
|-------|-----------|-----------|--------|-----|
| `verification.commands` | 24 | 7 days | Protobuf | `tenant_id` |
| `verification.events` | 24 | 30 days | Protobuf | `verification_id` |
| `verification.results` | 24 | 30 days | Protobuf | `task_id` |
| `document.tasks` | 48 | 1 day | Protobuf | `tenant_id` |
| `biometric.tasks` | 48 | 1 day | Protobuf | `tenant_id` |
| `risk.tasks` | 48 | 1 day | Protobuf | `tenant_id` |
| `fraud.tasks` | 48 | 1 day | Protobuf | `tenant_id` |
| `audit.logs` | 12 | 7 years | Avro | `tenant_id` |
| `webhook.delivery` | 24 | 1 day | Protobuf | `webhook_id` |
| `compliance.alerts` | 12 | 1 year | Protobuf | `tenant_id` |

**Delivery Guarantees:**
- At-least-once delivery via manual offset commits
- Exactly-once semantics for audit logs (idempotent producer + transactional API)
- Max 3 retries before dead-letter queue

---

## 6. Multi-Tenancy Strategy

USORA enforces multi-tenant isolation at every layer of the stack:

### 6.1 PostgreSQL: Schema-per-Tenant + Row-Level Security

```
┌───────────────────────────────────────────────────────┐
│                  PostgreSQL Instance                    │
│                                                        │
│  ┌─────────────────────────────────────────────────┐   │
│  │  public schema  (tenant registry, shared config) │   │
│  └─────────────────────────────────────────────────┘   │
│                                                        │
│  ┌─────────────────────────────────────────────────┐   │
│  │  tenant_acme                                     │   │
│  │  │  verifications                                │   │
│  │  │  documents                                    │   │
│  │  │  cases                                        │   │
│  │  │  webhooks                                     │   │
│  │  │  audit_log                                    │   │
│  └─────────────────────────────────────────────────┘   │
│                                                        │
│  ┌─────────────────────────────────────────────────┐   │
│  │  tenant_globalbank                               │   │
│  │  │  verifications                                │   │
│  │  │  documents                                    │   │
│  │  │  ...                                          │   │
│  └─────────────────────────────────────────────────┘   │
│                                                        │
│  ┌─────────────────────────────────────────────────┐   │
│  │  tenant_fintech_01                               │   │
│  │  │  verifications                                │   │
│  │  │  ...                                          │   │
│  └─────────────────────────────────────────────────┘   │
└───────────────────────────────────────────────────────┘
```

- Each tenant receives a dedicated PostgreSQL schema (`tenant_{tenant_id}`)
- Cross-schema queries are forbidden at the database role level
- Row-Level Security (RLS) provides an additional defense layer
- Connection pooling via PgBouncer with schema-aware routing

### 6.2 Redis: Key Namespacing

```
tenant:{tenant_id}:session:{session_id}            Session data
tenant:{tenant_id}:rate_limit:{client_id}           Rate limit counters
tenant:{tenant_id}:cache:{endpoint}:{params_hash}   Cached responses
tenant:{tenant_id}:feature_flags                    Tenant configuration
tenant:{tenant_id}:jwt_blacklist:{jti}              Token revocation
tenant:{tenant_id}:locks:{resource_id}              Distributed locks
```

- All Redis keys are prefixed with `tenant:{tenant_id}:`
- Keyspace scanning restricted to tenant prefix
- Redis ACL users limited to their key pattern namespace
- Database-level isolation option available for high-security tenants

### 6.3 Kafka: Topic-per-Event-Type with Tenant Key

- Each event type has its own topic (not per-tenant topics)
- Message key is always `tenant_id` for partition affinity
- Consumer groups filter by tenant ID at application level
- ACLs restrict topic access to authorized services
- Consumer lag monitoring per tenant partition

### 6.4 S3: Per-Tenant Prefix with IAM Policies

- Document storage path: `s3://usora-data/tenant/{tenant_id}/{verification_id}/{document_id}`
- IAM policy enforces prefix-level access:
  ```json
  {
    "Effect": "Allow",
    "Action": ["s3:GetObject", "s3:PutObject"],
    "Resource": "arn:aws:s3:::usora-data/tenant/{tenant_id}/*"
  }
  ```
- S3 bucket policies deny cross-prefix access
- Object lock (WORM) enabled for audit documents
- Server-side encryption with per-tenant KMS keys

### 6.5 Elasticsearch: Index-per-Tenant

- Index naming: `cases-tenant-{tenant_id}`, `audit-tenant-{tenant_id}`, `documents-tenant-{tenant_id}`
- Index-level security via Elasticsearch RBAC
- Tenant-scoped search queries with index patterns
- ILM policies per tenant (hot/warm/delete phases)

### 6.6 ClickHouse: Tenant-Partitioned Tables

- Tables partitioned by `toYYYYMM(created_at)` with `tenant_id` as primary sort key
- Materialized views per tenant for common aggregations
- Query filtering enforced at application level
- Storage quota enforcement per tenant

### 6.7 HashiCorp Vault: Per-Tenant Secrets

- KV engine path: `secret/tenant/{tenant_id}/*`
- Transit key: `transit/keys/tenant-{tenant_id}-*`
- PKI role: `pki/roles/tenant-{tenant_id}`
- Dynamic database credentials scoped to tenant schema
- ACL policies enforce path-level isolation

---

## 7. Security Architecture

### 7.1 Zero-Trust Network Model

```
┌────────────────────────────────────────────────────────────────────────────┐
│                                INTERNET                                      │
└──────────────────────────────────┬─────────────────────────────────────────┘
                                   │  TLS 1.3
                                   ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  EDGE (CloudFlare)                                                          │
│  • WAF (OWASP Top 10 rules, rate limiting)                                  │
│  • DDoS protection (L3/L4/L7)                                              │
│  • Bot management (JS challenge, CAPTCHA)                                   │
│  • IP reputation filtering                                                  │
└──────────────────────────────────┬─────────────────────────────────────────┘
                                   │  Origin pull (TLS 1.3, mTLS optional)
                                   ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  KUBERNETES CLUSTER (EKS, private subnets only)                             │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  INGRESS NAMESPACE (usora-edge)                                       │   │
│  │  • Ingress Controller (NGINX + cert-manager)                          │   │
│  │  • NetworkPolicy: allow from internet on :443 only                    │   │
│  └──────────────────────────────┬───────────────────────────────────────┘   │
│                                 │  TLS 1.3 + mTLS                           │
│                                 ▼                                           │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  GATEWAY NAMESPACE (usora-gateway)                                    │   │
│  │  • NetworkPolicy: allow from ingress on :8443                         │   │
│  │  • mTLS termination (SPIFFE/SPIRE SVID validation)                    │   │
│  │  • API authentication (JWT, mTLS, API Key)                            │   │
│  │  • Authorization (RBAC/ABAC via OPA/Rego)                             │   │
│  │  • Rate limiting (per-tenant token bucket)                            │   │
│  └──────────────────────────────┬───────────────────────────────────────┘   │
│                                 │  mTLS (SPIFFE/SPIRE)                      │
│                                 ▼                                           │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  ORCHESTRATION NAMESPACE (usora-orchestration)                        │   │
│  │  • NetworkPolicy: allow from gateway only                            │   │
│  │  • No direct ingress access                                          │   │
│  │  • Service mesh with Cilium NetworkPolicy (eBPF enforcement)          │   │
│  └──────────────────────────────┬───────────────────────────────────────┘   │
│                                 │                                           │
│                                 ▼                                           │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  COMPUTE NAMESPACE (usora-compute)                                   │   │
│  │  • NetworkPolicy: allow from orchestration + gateway (bypass)        │   │
│  │  • No egress to internet (private subnets only)                      │   │
│  └──────────────────────────────┬───────────────────────────────────────┘   │
│                                 │                                           │
│                                 ▼                                           │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  DATA NAMESPACE (usora-data)                                          │   │
│  │  • PostgreSQL: private statefulset, allow only from orchestration    │   │
│  │  • Redis: private cluster, allow from gateway + orchestration        │   │
│  │  • Kafka: private cluster, allow from all application namespaces     │   │
│  │  • No direct internet access (egress to S3 via VPC endpoint)         │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────────┘
```

### 7.2 Authentication Methods

| Method | Use Case | Protocol | Token Format | Lifetime |
|--------|----------|----------|-------------|----------|
| OAuth 2.0 + PKCE | Interactive flows (web/mobile) | Authorization code flow | JWT (RS256) | 1 hour |
| API Key + HMAC | Server-to-server | Header-based signing | Opaque key | Until revoked |
| mTLS | High-security / inter-service | X.509 certificate | SPIFFE SVID | 24 hours (auto-rotated) |

### 7.3 Authorization Model

- **RBAC**: 50+ predefined roles, hierarchical (super_admin > tenant_admin > reviewer > viewer)
- **ABAC**: OPA/Rego policies for contextual decisions (time, IP range, risk score)
- **Scope-based**: JWT scopes for API-level access control (`verifications:read`, `webhooks:manage`)

### 7.4 Encryption at Rest

| Data Store | Encryption Method | Key Management |
|-----------|------------------|----------------|
| PostgreSQL | AES-256 (TDE) + column-level encryption | Vault (per-tenant transit key) |
| Redis | AES-256 (RDB/AOF encryption) | Vault (cluster-wide) |
| S3 | AES-256 (SSE-KMS) | KMS (per-tenant key) |
| Kafka | TLS + AES-256 (at rest) | KMS (cluster-wide) |
| Elasticsearch | AES-256 (disk encryption) | KMS (cluster-wide) |
| ClickHouse | AES-256 (disk encryption) | KMS (cluster-wide) |

### 7.5 Immutable Audit Trail

Every action produces an immutable audit record with hash-chain integrity:

```
Record_N.hash = SHA256(Record_N.payload + Record_{N-1}.hash)
```

- Audit records are write-once, never deleted or modified
- Hash chain prevents backdating or tampering
- Merkle root of hourly batches anchored to Polygon L2
- Quarterly cryptographic audit verification

---

## 8. Performance Targets

### 8.1 Latency SLOs

| Component | p50 | p95 | p99 | p99.9 |
|-----------|-----|-----|-----|-------|
| API Gateway (end-to-end) | 2ms | 5ms | 10ms | 20ms |
| Orchestration (business logic) | 5ms | 15ms | 50ms | 100ms |
| Compute — Document Analysis | 500ms | 2s | 5s | 10s |
| Compute — Biometric Matching | 200ms | 800ms | 2s | 5s |
| Compute — Risk Scoring | 50ms | 150ms | 300ms | 500ms |
| Database Query (cached) | 1ms | 2ms | 5ms | 10ms |
| Database Query (uncached) | 5ms | 20ms | 50ms | 100ms |
| **End-to-End (simple)** | **20ms** | **50ms** | **100ms** | **200ms** |
| **End-to-End (complex)** | **1s** | **3s** | **5s** | **10s** |

### 8.2 Throughput Targets

| Metric | Current | Target | Peak |
|--------|---------|--------|------|
| Verifications per day | 1M | 10M | 20M |
| Requests per second | 500 | 5,000 | 10,000 |
| Active tenants | 50 | 500 | 1,000 |
| Documents processed per day | 2M | 20M | 40M |
| Biometric matches per day | 1.6M | 16M | 32M |
| Audit events per day | 20M | 200M | 400M |

### 8.3 Availability SLOs

| Tier | Uptime | Monthly Downtime | SLA Credit |
|------|--------|-----------------|------------|
| Enterprise | 99.99% | 4.38 minutes | 100% |
| Professional | 99.95% | 21.9 minutes | 50% |
| Starter | 99.9% | 43.8 minutes | 25% |

---

## 9. Deployment Architecture

### 9.1 Multi-Region Active-Active

```
                    ┌──────────────────────────────────────────────────────┐
                    │          GLOBAL LOAD BALANCER (Route 53)             │
                    │     Latency-based + Geolocation + Health-check       │
                    └──────────────────────┬───────────────────────────────┘
              ┌───────────────────────────┼──────────────────────────────┐
              │                           │                              │
              ▼                           ▼                              ▼
┌─────────────────────────┐  ┌─────────────────────────┐  ┌─────────────────────────┐
│  REGION: us-east-1       │  │  REGION: eu-west-1       │  │  REGION: ap-southeast-1  │
│  (Primary)               │  │  (Active)                │  │  (Active)               │
│                          │  │                          │  │                          │
│  ┌───────────────────┐   │  │  ┌───────────────────┐   │  │  ┌───────────────────┐   │
│  │  AZ: us-east-1a    │   │  │  │  AZ: eu-west-1a    │   │  │  │  AZ: ap-southeast-1│   │
│  │  AZ: us-east-1b    │   │  │  │  AZ: eu-west-1b    │   │  │  │  AZ: ap-southeast-2│   │
│  │  AZ: us-east-1c    │   │  │  │  AZ: eu-west-1c    │   │  │  │  AZ: ap-southeast-3│   │
│  └───────────────────┘   │  │  └───────────────────┘   │  │  └───────────────────┘   │
│                          │  │                          │  │                          │
│  Full Stack:             │  │  Full Stack:             │  │  Full Stack:             │
│  • API Gateway (5 pods)  │  │  • API Gateway (5 pods)  │  │  • API Gateway (3 pods)  │
│  • Orchestration (3 pod) │  │  • Orchestration (3 pod) │  │  • Orchestration (2 pod) │
│  • Compute Workers       │  │  • Compute Workers       │  │  • Compute Workers       │
│                          │  │                          │  │                          │
│  Data Layer:             │  │  Data Layer:             │  │  Data Layer:             │
│  • PostgreSQL Primary    │◀─▶│  • PostgreSQL Replica   │◀─▶│  • PostgreSQL Replica   │
│  • Redis Cluster (3M+3R) │◀─▶│  • Redis Cluster (3M+3R)│◀─▶│  • Redis Cluster (3M+3R)│
│  • Kafka (3 brokers)    │◀─▶│  • Kafka (3 brokers)   │◀─▶│  • Kafka (3 brokers)   │
│  • ClickHouse (2 nodes) │◀─▶│  • ClickHouse (2 nodes) │◀─▶│  • ClickHouse (1 node) │
│  • S3 (local)           │◀─▶│  • S3 (local)           │◀─▶│  • S3 (local)           │
└─────────────────────────┘  └─────────────────────────┘  └─────────────────────────┘
                                    │
                                    ▼
                    ┌──────────────────────────────────────────────────────┐
                    │          CROSS-REGION REPLICATION                    │
                    │                                                     │
                    │  • PostgreSQL: Streaming replication (async)        │
                    │  • Redis: Active-passive replication                 │
                    │  • Kafka: MirrorMaker 2 (bidirectional)             │
                    │  • S3: Cross-region replication (CRR)               │
                    │  • ClickHouse: Cross-cluster replication            │
                    └─────────────────────────────────────────────────────┘
```

### 9.2 Kubernetes Topology (per Region)

| Namespace | Components | Resource Requests | HPA |
|-----------|-----------|------------------|-----|
| `usora-edge` | Ingress Controller, Cert Manager | 2 CPU, 4Gi | 2-5 |
| `usora-gateway` | API Gateway (Rust) | 2 CPU, 1Gi/pod | 3-20 |
| `usora-orchestration` | Orchestration (Java), Camunda | 4 CPU, 8Gi/pod | 3-10 |
| `usora-compute` | Document, Biometric, Risk, Fraud workers | 4-8 CPU, 8-32Gi/pod | 3-200 |
| `usora-data` | PostgreSQL, Redis, Kafka, ClickHouse, ES | Variable | N/A (StatefulSet) |
| `usora-observability` | Prometheus, Grafana, Loki, Tempo | 4 CPU, 16Gi | 1-3 |
| `usora-security` | Vault, Falco, OPA | 2 CPU, 4Gi | 3 (Vault) |

---

## 10. Observability Stack

### 10.1 Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         OBSERVABILITY STACK                                       │
│                                                                                   │
│  ┌─────────────────────┐    ┌─────────────────────┐    ┌──────────────────────┐  │
│  │   OpenTelemetry      │    │   Prometheus         │    │   Structured Logs   │  │
│  │   (All Services)     │    │   (All Services)     │    │   (All Services)    │  │
│  │                     │    │                      │    │                      │  │
│  │  • Distributed      │    │  • RED metrics       │    │  • JSON format      │  │
│  │    traces (W3C)     │    │  • Rate/Errors/Dur   │    │  • trace_id+span_id │  │
│  │  • Span exports     │    │  • Histograms        │    │  • tenant_id+user_id│  │
│  │  • Baggage prop     │    │  • Service monitors  │    │  • Log levels       │  │
│  └─────────┬───────────┘    └──────────┬──────────┘    └──────────┬───────────┘  │
│            │                          │                          │               │
│            ▼                          ▼                          ▼               │
│  ┌─────────────────────┐    ┌─────────────────────┐    ┌──────────────────────┐  │
│  │   Tempo (Traces)     │    │   Prometheus Server  │    │   Loki (Logs)       │  │
│  │                     │    │                      │    │                      │  │
│  │  • Trace ID query   │    │  • 30-day retention  │    │  • Log aggregation  │  │
│  │  • Service graph    │    │  • Alerting rules    │    │  • Label indexing   │  │
│  │  • Span metrics     │    │  • Recording rules   │    │  • LogQL queries    │  │
│  └─────────────────────┘    └─────────────────────┘    └──────────────────────┘  │
│            │                          │                          │               │
│            └──────────────────────────┼──────────────────────────┘               │
│                                       │                                           │
│                                       ▼                                           │
│  ┌───────────────────────────────────────────────────────────────────────────┐   │
│  │                       Grafana (Single Pane of Glass)                        │   │
│  │                                                                             │   │
│  │  • Dashboards: Architecture overview, Service health, Tenant metrics,      │   │
│  │    Business KPIs, SLO burn-rate, Cost analytics, Security overview          │   │
│  │  • Alerting: Unified alert management, Silence management,                 │   │
│  │    Notification policies (PagerDuty, Slack, Email)                         │   │
│  │  • Explore: Ad-hoc metrics, logs, and trace queries                        │   │
│  └───────────────────────────────────────────────────────────────────────────┘   │
│                                                                                   │
│  ┌───────────────────────────────────────────────────────────────────────────┐   │
│  │                       Alertmanager                                         │   │
│  │  • Alert deduplication, grouping, inhibition                               │   │
│  │  • Route to PagerDuty (SEV1/SEV2), Slack (SEV3+), Email (Info)            │   │
│  │  • Silence management (maintenance windows)                                │   │
│  └───────────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 10.2 Key Monitoring Dashboards

| Dashboard | Purpose | Key Panels |
|-----------|---------|------------|
| **Service Overview** | Global health of all services | Request rate, error rate, latency (p50/p95/p99), CPU/memory |
| **Gateway Analytics** | API Gateway performance | Requests by endpoint, tenant, status; rate limit hits; circuit breaker state |
| **Verification Pipeline** | End-to-end verification flow | Verifications by status, duration per step, approval/rejection rate |
| **Compute Workers** | ML inference performance | Task throughput, queue depth, inference latency, model version distribution |
| **Tenant Health** | Per-tenant monitoring | Quota usage, error rate, latency, active verifications |
| **Database Performance** | Data layer health | Connection pool usage, query latency, replication lag, cache hit ratio |
| **Security Overview** | Security events | Failed auth attempts, rate limit violations, audit log volume, anomaly alerts |
| **Cost Analytics** | Infrastructure cost | Cost by namespace, service, tenant; spot instance savings; storage growth |

### 10.3 Alerting Thresholds

| Severity | Condition | Response Time | Channel |
|----------|-----------|---------------|---------|
| **SEV1** | Error rate > 1% for 2 min | 15 min | PagerDuty + Slack |
| **SEV1** | Verification p99 > 500ms for 5 min | 15 min | PagerDuty + Slack |
| **SEV1** | Tenant isolation breach | 5 min | PagerDuty + Slack + Email |
| **SEV2** | Error rate > 0.1% for 5 min | 30 min | PagerDuty + Slack |
| **SEV2** | Database replication lag > 5s | 30 min | PagerDuty + Slack |
| **SEV2** | Compute queue backlog > 100K | 30 min | PagerDuty + Slack |
| **SEV3** | Rate limit hits > 100/sec | 1 hr | Slack |
| **SEV3** | Cache hit ratio < 70% | 1 hr | Slack |
| **SEV4** | Certificate expiry < 7 days | Next business day | Email |

### 10.4 Structured Logging Format

```json
{
  "timestamp": "2026-07-21T23:31:00.123Z",
  "level": "INFO",
  "service": "usora-api-gateway",
  "trace_id": "0af7651916cd43dd8448eb211c80319c",
  "span_id": "b7ad6b7169203331",
  "tenant_id": "tenant_acme",
  "user_id": "user_12345",
  "request_id": "550e8400-e29b-41d4-a716-446655440000",
  "message": "Verification created successfully",
  "verification_id": "ver_7f8a9b2c3d4e5f6a",
  "duration_ms": 42,
  "metadata": {
    "workflow_id": "standard-kyc-v3",
    "priority": "standard"
  }
}
```

---

*USORA System Architecture Overview — v1.0.0*
