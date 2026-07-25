system-design.md
Markdown
Copy
Code
Preview
# USORA — System Design Document (system-design.md)

> **Version:** 1.0.0  
> **Last Updated:** 2026-07-25  
> **Classification:** Internal — Engineering Reference  
> **Purpose:** Comprehensive system design covering architecture, data flow, component interactions, scalability, and operational considerations for the USORA KYC platform.

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [System Context](#2-system-context)
3. [Architecture Overview](#3-architecture-overview)
4. [Component Design](#4-component-design)
5. [Data Architecture](#5-data-architecture)
6. [Security Architecture](#6-security-architecture)
7. [Compute Layer Design](#7-compute-layer-design)
8. [Orchestration Layer Design](#8-orchestration-layer-design)
9. [Gateway Layer Design](#9-gateway-layer-design)
10. [Frontend Architecture](#10-frontend-architecture)
11. [Integration Architecture](#11-integration-architecture)
12. [Deployment Architecture](#12-deployment-architecture)
13. [Observability Design](#13-observability-design)
14. [Scalability & Performance](#14-scalability--performance)
15. [Disaster Recovery](#15-disaster-recovery)
16. [Operational Considerations](#16-operational-considerations)
17. [Appendices](#17-appendices)

---

## 1. Executive Summary

USORA is a polyglot, multi-tenant KYC platform designed to process millions of identity verifications daily across 200+ countries and 7,000+ document types. The system design prioritizes:

- **Sub-100ms p99 latency** for simple verifications
- **99.99% uptime SLA** through multi-region active-active deployment
- **Hardware-enforced tenant isolation** from network to database row level
- **Real-time ML inference** for document analysis, biometric matching, and fraud detection
- **Immutable audit trails** blockchain-anchored for regulatory compliance

The architecture separates concerns into four distinct layers — Gateway (Rust), Orchestration (Java), Compute (Rust), and Data — communicating via gRPC with mTLS and Kafka for async events.

---

## 2. System Context

### 2.1 Stakeholders

| Stakeholder | Concern | Design Response |
|-------------|---------|-----------------|
| **End Users** | Fast, frictionless onboarding | Sub-100ms responses, progressive disclosure, mobile-optimized flows |
| **Tenants** | Customizable workflows, data sovereignty | Per-tenant BPMN workflows, schema isolation, regional deployment |
| **Compliance Officers** | Audit trails, regulatory adherence | Immutable logs, 16 framework mappings, automated reporting |
| **Security Teams** | Threat mitigation, zero-trust | mTLS everywhere, SPIFFE identity, runtime security (Falco) |
| **Platform Engineers** | Operability, observability | OpenTelemetry tracing, Prometheus metrics, structured logging |
| **Data Scientists** | Model deployment, feature serving | Feature store, model versioning, A/B testing infrastructure |

### 2.2 External Systems

| System | Integration | Protocol | Purpose |
|--------|-------------|----------|---------|
| CloudFlare | Edge | DNS/HTTP | DDoS protection, CDN, geo-routing |
| Government ID APIs | Integration | REST/SOAP | Identity verification, registry lookups |
| Credit Bureaus | Integration | REST/gRPC | Credit checks, scoring |
| Banking APIs (Open Banking) | Integration | REST/OAuth2 | Account verification, transaction history |
| Sanctions/Watchlists | Integration | REST/CSV | AML screening, PEP checks |
| Blockchain (Polygon) | Security | JSON-RPC | Audit log anchoring |
| SIEM (Splunk/Sentinel) | Security | Syslog/HTTP | Security event ingestion |
| HashiCorp Vault | Platform | HTTPS | Secret management, dynamic credentials |

### 2.3 Use Case Summary

| Use Case | Actors | Frequency | Criticality |
|----------|--------|-----------|-------------|
| Create verification | Tenant API, End User | 10M/day | Critical |
| Upload document | End User | 10M/day | Critical |
| Analyze document | System (Compute) | 10M/day | Critical |
| Capture biometric | End User | 8M/day | Critical |
| Match biometric | System (Compute) | 8M/day | Critical |
| Compute risk score | System (Compute) | 10M/day | Critical |
| Manual review | Reviewer | 500K/day | High |
| Generate compliance report | Compliance Officer | 1K/day | Medium |
| Tenant onboarding | Platform Admin | 10/day | High |
| Model deployment | Data Scientist | 5/week | Medium |

---

## 3. Architecture Overview

### 3.1 High-Level Architecture Diagram
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              CLIENT LAYER                                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐ │
│  │  Web Portal  │  │  Mobile SDK  │  │  API Clients │  │  Partner/3rd Party   │ │
│  │  (React 19)  │  │(iOS/Android) │  │  (REST/gRPC) │  │  (Webhook/OAuth2)  │ │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────────┬───────────┘ │
└─────────┼─────────────────┼─────────────────┼─────────────────────┼─────────────┘
│                 │                 │                     │
▼                 ▼                 ▼                     ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              EDGE LAYER                                          │
│  CloudFlare / AWS CloudFront — DDoS Protection, CDN, Geo-Routing, WAF           │
└─────────────────────────────────────────────────────────────────────────────────┘
│
▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│  LAYER 1: API GATEWAY  (Rust + Tokio — Custom Implementation)                    │
│  ┌────────────────────────────────────────────────────────────────────────────┐  │
│  │  Protocol Servers: Axum (HTTP/2) │ Tonic (gRPC) │ Tokio-Tungstenite (WS)   │  │
│  └────────────────────────────────────────────────────────────────────────────┘  │
│                                    │                                              │
│                                    ▼                                              │
│  ┌────────────────────────────────────────────────────────────────────────────┐  │
│  │  TOWER MIDDLEWARE PIPELINE (ordered execution)                              │  │
│  │  1. TLS Termination (rustls, TLS 1.3, mTLS)                                │  │
│  │  2. Request ID Injection (UUID v7, time-sortable)                            │  │
│  │  3. Trace Context Propagation (OpenTelemetry W3C)                            │  │
│  │  4. DDoS/WAF Filtering (per-IP rate limits, geo-blocking)                    │  │
│  │  5. Tenant Resolution (subdomain → tenant_id, header fallback)              │  │
│  │  6. Authentication (JWT validation, mTLS cert extraction)                  │  │
│  │  7. Authorization (RBAC/ABAC via OPA/Rego, cached decisions)               │  │
│  │  8. Rate Limiting (token bucket per tenant, Redis-backed)                  │  │
│  │  9. Request Validation (OpenAPI schema, body size limits)                    │  │
│  │  10. Circuit Breaker (per-tenant, per-upstream, Resilience4j pattern)      │  │
│  │  11. Request Transformation (REST ↔ gRPC, header injection)                │  │
│  └────────────────────────────────────────────────────────────────────────────┘  │
│                                    │                                              │
│                                    ▼                                              │
│  ┌────────────────────────────────────────────────────────────────────────────┐  │
│  │  ROUTING ENGINE                                                               │  │
│  │  • Service Discovery (Kubernetes DNS / Consul)                                │  │
│  │  • Load Balancing (least-connections, weighted)                               │  │
│  │  • Traffic Splitting (canary, blue-green, A/B via headers)                    │  │
│  │  • Sticky Sessions (WebSocket affinity)                                       │  │
│  │  • Direct Compute Bypass (health checks, simple inference skip orchestration)│  │
│  └────────────────────────────────────────────────────────────────────────────┘  │
│                                    │                                              │
│                                    ▼                                              │
│  ┌────────────────────────────────────────────────────────────────────────────┐  │
│  │  RESPONSE PIPELINE                                                            │  │
│  │  • gRPC → REST Translation                                                    │  │
│  │  • Response Caching (Redis, TTL per endpoint, cache-control headers)         │  │
│  │  • Audit Log Emission (async Kafka, non-blocking)                            │  │
│  │  • Metrics Recording (Prometheus counters/histograms)                       │  │
│  │  • Distributed Tracing Span Closure                                           │  │
│  └────────────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────────┘
│                              │
│ gRPC + mTLS                  │ gRPC + mTLS (bypass path)
▼                              ▼
┌────────────────────────┐    ┌────────────────────────┐
│  LAYER 2: ORCHESTRATION │    │  LAYER 2b: COMPUTE     │
│  (Java 21 + Spring Boot)│    │  DIRECT (Rust + Tokio) │
│  • Camunda BPMN Engine  │    │  • Health checks       │
│  • Business Services    │    │  • Metrics endpoints   │
│  • Saga Management      │    │  • Simple inference    │
│  • State Machines       │    │    (bypass orchestration)│
└────────────────────────┘    └────────────────────────┘
│
│ gRPC + mTLS / Kafka (events)
▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│  LAYER 3: COMPUTE  (Rust + Tokio — High-Throughput Worker Pools)                 │
│  ┌────────────────────────────────────────────────────────────────────────────┐  │
│  │  TASK CONSUMER (rdkafka, async consumer group)                                │  │
│  │  • Manual offset commit (at-least-once delivery)                              │  │
│  │  • Dead-letter topic (max 3 retries, exponential backoff)                     │  │
│  │  • Priority partitioning: P0 (express), P1 (standard), P2 (batch)            │  │
│  └────────────────────────────────────────────────────────────────────────────┘  │
│                                    │                                              │
│                                    ▼                                              │
│  ┌────────────────────────────────────────────────────────────────────────────┐  │
│  │  WORKER POOLS (Tokio runtime, per-tenant resource quotas enforced)          │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │  │
│  │  │ Document    │  │ Biometric   │  │ Risk        │  │ Fraud Detection     │  │  │
│  │  │ Analysis    │  │ Matching    │  │ Scoring     │  │ (Graph Neural Net)  │  │  │
│  │  │ Worker Pool │  │ Worker Pool │  │ Worker Pool │  │ Worker Pool         │  │  │
│  │  │ CPU-bound   │  │ CPU-bound   │  │ Mixed async │  │ Mixed async + CPU   │  │  │
│  │  │ spawn_block │  │ spawn_block │  │ + CPU       │  │                     │  │  │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────────────┘  │  │
│  └────────────────────────────────────────────────────────────────────────────┘  │
│                                    │                                              │
│                                    ▼                                              │
│  ┌────────────────────────────────────────────────────────────────────────────┐  │
│  │  RESULT PUBLISHER                                                             │  │
│  │  • Kafka result topic (for orchestration consumption)                         │  │
│  │  • Webhook delivery (retry + circuit breaker + HMAC signature)              │  │
│  │  • Metrics + tracing emission (OpenTelemetry)                                 │  │
│  └────────────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────────┘
│
│ JDBC / SQLx / Redis / S3 / Elasticsearch
▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│  LAYER 4: DATA LAYER                                                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │ PostgreSQL  │  │ Redis       │  │ Kafka       │  │ S3 / MinIO              │  │
│  │ (Per-tenant │  │ (Session /  │  │ (Event      │  │ (Documents /            │  │
│  │  schema iso)│  │  Cache / RL)│  │  Bus)       │  │  Artifacts)             │  │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────────────────┘  │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │ ClickHouse  │  │ Elastic-    │  │ Blockchain  │  │ HashiCorp Vault         │  │
│  │ (Analytics) │  │ search      │  │ (Audit      │  │ (Secrets / Keys /       │  │
│  │             │  │ (Search)    │  │  Anchor)    │  │  Dynamic Creds)         │  │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────────┘
plain

### 3.2 Design Principles

| Principle | Rationale | Implementation |
|-----------|-----------|----------------|
| **Performance as Feature** | User abandonment spikes with latency | Sub-100ms p99, zero-GC gateway, connection pooling |
| **Security by Construction** | Compliance is non-negotiable | mTLS everywhere, tenant isolation at every layer |
| **Failure is Normal** | Services fail, networks partition | Circuit breakers, graceful degradation, saga patterns |
| **Observability is Mandatory** | Cannot operate what you cannot measure | OpenTelemetry, structured logging, metrics on every request |
| **Change is Constant** | Regulations evolve, fraud adapts | Feature flags, A/B testing, independent deployment |

### 3.3 Technology Selection Matrix

| Concern | Primary | Alternative | Decision Rationale |
|---------|---------|-------------|-------------------|
| High-concurrency gateway | Rust + Tokio | Go, C++ | Memory safety + zero-GC + ecosystem |
| Business orchestration | Java 21 + Spring Boot | Go, Kotlin | Camunda BPMN + Virtual Threads |
| CPU-intensive compute | Rust + Tokio | C++, Python | Memory safety + no GC pauses + ONNX |
| Frontend | TypeScript + React 19 | Vue, Svelte | Ecosystem, Server Components, DX |
| Primary database | PostgreSQL 16 | MySQL, CockroachDB | Schema-per-tenant maturity, JSONB |
| Cache | Redis 7 | Memcached | Data structures, pub/sub, persistence |
| Event bus | Kafka 3.x | RabbitMQ, NATS | Replay, partitioning, ecosystem |
| Analytics | ClickHouse | BigQuery, Druid | Real-time, self-hosted, cost |
| Object storage | S3 / MinIO | Azure Blob, GCS | Multi-cloud, S3 API compatibility |
| Secrets | HashiCorp Vault | AWS SM, Azure KV | Multi-cloud, dynamic credentials, PKI |
| Service mesh | SPIFFE/SPIRE + Cilium | Istio, Linkerd | eBPF performance, workload identity |

---

## 4. Component Design

### 4.1 API Gateway (Rust)

#### 4.1.1 Responsibilities

- Accept all inbound traffic (REST, gRPC, WebSocket)
- Terminate TLS 1.3, enforce mTLS for internal routing
- Authenticate and authorize every request
- Resolve tenant context and enforce per-tenant quotas
- Route requests to appropriate downstream services
- Translate protocols (REST ↔ gRPC) when needed
- Cache responses and emit audit logs
- **Never contain business logic**

#### 4.1.2 Explicitly NOT Responsible For

- Business workflow decisions
- Document analysis or ML inference
- Database transactions
- Long-running state management

#### 4.1.3 Deployment Model

- Stateless, horizontally scaled
- 3+ replicas minimum per region
- HPA: 3–20 pods based on CPU/connections
- Resource: 2 cores, 1Gi memory per pod
- PodDisruptionBudget: minAvailable 3

#### 4.1.4 Component Diagram
┌─────────────────────────────────────────────────────────────────┐
│                     RUST API GATEWAY                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐ │
│  │ HTTP/2      │  │ gRPC        │  │ WebSocket               │ │
│  │ Server      │  │ Server      │  │ Handler                 │ │
│  │ (Axum)      │  │ (Tonic)     │  │ (Tokio-Tungstenite)     │ │
│  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────────┘ │
│         │                │                     │                │
│         └────────────────┴─────────────────────┘                │
│                            │                                    │
│                            ▼                                    │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              REQUEST PIPELINE (Tower Middleware)         │   │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌───────────────┐ │   │
│  │  │ TLS     │ │ Request │ │ Tenant  │ │ AuthN/AuthZ   │ │   │
│  │  │ Term    │ │ ID      │ │ Resolve │ │ (JWT/mTLS)    │ │   │
│  │  └─────────┘ └─────────┘ └─────────┘ └───────────────┘ │   │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌───────────────┐ │   │
│  │  │ Rate    │ │ Circuit │ │ Request │ │ Routing       │ │   │
│  │  │ Limit   │ │ Breaker │ │ Validate│ │ Engine      │ │   │
│  │  └─────────┘ └─────────┘ └─────────┘ └───────────────┘ │   │
│  └─────────────────────────────────────────────────────────┘   │
│                            │                                    │
│                            ▼                                    │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              RESPONSE PIPELINE                         │   │
│  │  • gRPC → REST translation                             │   │
│  │  • Response caching (Redis)                            │   │
│  │  • Audit log emission (Kafka)                        │   │
│  │  • Metrics (Prometheus)                                │   │
│  │  • Tracing span closure                                │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
plain

### 4.2 Orchestration Layer (Java)

#### 4.2.1 Responsibilities

- Manage verification lifecycle (create → process → complete)
- Execute BPMN workflows with Camunda
- Coordinate multi-step verification flows
- Make business decisions (approve, reject, escalate)
- Maintain saga state for distributed transactions
- Enforce compliance rules and regulatory logic
- Publish domain events to Kafka
- Handle human-in-the-loop review queues

#### 4.2.2 Explicitly NOT Responsible For

- Raw HTTP client handling (gateway does this)
- CPU-intensive ML inference (compute does this)
- Direct document image processing

#### 4.2.3 Deployment Model

- Stateful (workflow state in PostgreSQL)
- Horizontally scaled with partition-aware routing
- Leader election for scheduled tasks
- 3+ replicas, HPA 3–10
- Resource: 4 cores, 8Gi memory per pod (Virtual Threads)
- JVM flags: `-XX:+UseContainerSupport`, `-XX:MaxRAMPercentage=75.0`

### 4.3 Compute Layer (Rust)

#### 4.3.1 Responsibilities

- Execute CPU-intensive tasks: OCR, document forensics, biometric matching, ML inference
- Process tasks asynchronously from Kafka queues
- Return structured results to orchestration via Kafka
- Maintain model weights and feature caches in memory
- Enforce per-tenant resource quotas
- Provide health and metrics endpoints

#### 4.3.2 Explicitly NOT Responsible For

- HTTP request handling (gateway does this)
- Business workflow decisions (orchestration does this)
- Direct client communication

#### 4.3.3 Deployment Model

- Stateless workers, horizontally scaled
- GPU nodes for deep learning inference (optional)
- Separate node pools per workload type
- Document: 5–50 pods, 8 cores, 16Gi
- Biometric: 3–30 pods, 8 cores, 16Gi
- Risk: 3–20 pods, 4 cores, 8Gi
- Fraud: 2–10 pods, 8 cores, 32Gi (graph analysis)

### 4.4 Frontend Layer (TypeScript/React)

#### 4.4.1 Responsibilities

- Web portal for tenant admins and reviewers
- Applicant-facing KYC flows (document upload, biometric capture)
- Mobile SDKs (iOS/Android via React Native)
- Design system and component library
- Real-time verification status via WebSocket

#### 4.4.2 Deployment Model

- Static site generation + CDN (CloudFlare)
- Server Components for data-heavy pages
- Client Components for interactive elements
- Edge functions for geolocation and A/B testing

---

## 5. Data Architecture

### 5.1 Data Flow Diagram
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

### 5.2 Tenant Isolation Strategy

#### 5.2.1 PostgreSQL: Schema-per-Tenant with Row-Level Security

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
Connection Pooling:
PgBouncer with transaction-level pooling
10,000+ concurrent connections across all tenants
Connection multiplexing reduces PostgreSQL process overhead
```

### 5.2.2 Redis: Key Namespacing
plain
tenant:{tenant_id}:session:{session_id}        → Session data
tenant:{tenant_id}:rate_limit:{client_id}        → Rate limit counters
tenant:{tenant_id}:cache:{cache_key}             → Cached responses
tenant:{tenant_id}:feature_flags               → Tenant configuration
tenant:{tenant_id}:jwt_blacklist:{jti}           → Token revocation
### 5.2.3 Kafka: Topic Design with Tenant Partitioning
Table
Topic	Producer	Consumer	Schema	Retention	Partitions
verification.commands	Gateway, Admin	Orchestration	Protobuf	7 days	24 (by tenant_id hash)
verification.events	Orchestration	Gateway, Compute, Analytics	Protobuf	30 days	24
verification.results	Compute	Orchestration	Protobuf	30 days	24
document.tasks	Orchestration	Compute (Document)	Protobuf	1 day	48
biometric.tasks	Orchestration	Compute (Biometric)	Protobuf	1 day	48
risk.tasks	Orchestration	Compute (Risk)	Protobuf	1 day	48
fraud.tasks	Orchestration	Compute (Fraud)	Protobuf	1 day	48
audit.logs	All services	ClickHouse, Blockchain	Avro	7 years	12
webhook.delivery	Orchestration	Gateway	Protobuf	1 day	24
compliance.alerts	Orchestration	Admin, SIEM	Protobuf	1 year	12


### 5.3 Audit Trail Design

Every action produces an immutable audit record:
protobuf
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
Hash Chain:
plain
Record_N.hash = SHA256(Record_N.payload + Record_{N-1}.hash)
Blockchain Anchoring:
Merkle root of hourly audit batches published to Ethereum L2 (Polygon)
Smart contract stores root hash with timestamp
Any tampering detectable by hash mismatch
### 5.4 Data Retention
``` Table
Data Type	Retention	Deletion Method	Compliance Driver
Verification records	7 years	Soft delete + archive	AML5/6, BSA
Document images	90 days active, 7 years archive	Secure wipe (DoD 5220.22-M)	GDPR, CCPA
Biometric templates	Duration of relationship + 7 years	Cancelable hash destruction	GDPR Article 9
Audit logs	7 years	Immutable (no deletion)	SOC 2, ISO 27001
Session data	24 hours	TTL expiration	Privacy by design
Analytics raw data	2 years	Automated partition drop	Internal policy
Kafka topics (events)	30 days	Log compaction	Operational
Kafka topics (audit)	7 years	WORM storage	Compliance
```
### 6. Security Architecture
6.1 Threat Model
Table
Threat	Vector	Likelihood	Impact	Mitigation
Tenant data leakage	Application bug	Medium	Critical	Schema-per-tenant + RLS + encryption
Man-in-the-middle	Network interception	Low	Critical	TLS 1.3 + mTLS everywhere
Credential theft	Compromised client	Medium	High	Short-lived JWTs, refresh token rotation
DDoS attack	Volumetric traffic	Medium	Medium	Edge DDoS + per-tenant rate limits
Insider threat	Malicious employee	Low	Critical	JIT access, session recording, least privilege
Supply chain attack	Compromised dependency	Medium	High	SLSA Level 3, signed artifacts, SBOMs
Model poisoning	Adversarial training data	Low	High	Model versioning, A/B testing, drift detection
Deepfake injection	Fraudulent biometric	Medium	High	Multi-modal liveness, temporal analysis
SQL injection	Malicious input	Low	Critical	Parameterized queries, schema isolation
Privilege escalation	RBAC bypass	Low	Critical	ABAC with OPA, regular access reviews
6.2 Authentication Architecture
plain
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
JWT Claims:
JSON
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
6.3 Authorization Model
RBAC (Role-Based Access Control):
50+ predefined roles across tenant admin, reviewer, operator, auditor
Roles assigned at tenant level
Hierarchical: super_admin > tenant_admin > reviewer > viewer
ABAC (Attribute-Based Access Control):
OPA/Rego policies for fine-grained decisions
Contextual attributes: time of day, IP range, device posture
Dynamic policies: risk score thresholds, compliance status
Policy Example (Rego):
rego
package usora.authz

default allow = false

allow {
    input.user.roles[_] == "verifier"
    input.action == "READ"
    input.resource.type == "VERIFICATION"
    input.resource.tenant_id == input.user.tenant_id
}

allow {
    input.user.roles[_] == "admin"
    input.resource.tenant_id == input.user.tenant_id
}
6.4 Secret Management
HashiCorp Vault Integration:
plain
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
│  │  • Encryption keys (per-tenant)│   │
│  └─────────────────────────────────┘   │
│  ┌─────────────────────────────────┐   │
│  │  Transit Engine                  │   │
│  │  • Data encryption/decryption    │   │
│  │  • Key rotation automation       │   │
│  └─────────────────────────────────┘   │
└─────────────────────────────────────────┘
Dynamic Database Credentials:
Vault generates short-lived PostgreSQL credentials (1-hour TTL)
Each service instance gets unique credentials
Automatic revocation on pod termination
6.5 Network Security
Zero-Trust Network Model:
plain
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
7. Compute Layer Design
7.1 Document Analysis Worker
rust
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
7.2 Biometric Matching Worker
Template Storage:
Templates stored as cancelable biometric hashes (irreversible)
FAISS index for approximate nearest neighbor search (1M+ templates)
Per-tenant index partitioning prevents cross-tenant leakage
Matching Pipeline:
plain
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
7.3 Risk Scoring Worker
Feature Engineering:
rust
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
Model Ensemble:
XGBoost for tabular feature interpretation
Neural network for non-linear feature interactions
Weighted average with tenant-specific calibration
8. Orchestration Layer Design
8.1 BPMN Workflow Engine
Camunda Integration:
BPMN 2.0 process definitions deployed per tenant
Process variables stored in PostgreSQL (tenant-isolated)
External task workers for long-running operations
History cleanup with tenant-specific retention policies
Example Process Definition:
bpmn
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
8.2 Saga Pattern for Distributed Transactions
Verification Saga:
plain
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
Compensating Actions:
Document analysis fails → Delete uploaded document from S3, release quota
Biometric analysis fails → Delete biometric template, release quota
Risk scoring fails → Mark verification as incomplete, notify user
8.3 State Machine
java
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
9. Gateway Layer Design
9.1 Request Lifecycle
plain
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
│     • Body size enforcement (max 50MB for uploads)            │
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
9.2 Middleware Stack (Tower)
rust
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
9.3 Rate Limiting Algorithm
Token Bucket per Tenant:
rust
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
10. Frontend Architecture
10.1 Component Hierarchy
plain
frontend/
├── apps/
│   ├── portal/                    # Admin portal (Next.js 15)
│   │   ├── app/                   # App Router (React Server Components)
│   │   │   ├── (dashboard)/      # Dashboard layout group
│   │   │   ├── verifications/    # Verification management
│   │   │   ├── cases/            # Case review queues
│   │   │   ├── analytics/        # Reports and dashboards
│   │   │   └── settings/         # Tenant configuration
│   │   └── components/           # Client Components
│   │
│   └── applicant/                 # KYC applicant flow (Next.js 15)
│       ├── app/
│       │   ├── [tenant]/         # Tenant-branded routes
│       │   ├── upload/           # Document upload
│       │   ├── biometric/        # Biometric capture
│       │   └── review/           # Review and submit
│       └── components/
│
├── packages/
│   ├── design-system/             # Shared UI components
│   │   ├── src/
│   │   │   ├── components/       # Buttons, Inputs, Cards, etc.
│   │   │   ├── primitives/       # Headless UI primitives
│   │   │   ├── tokens/           # Colors, typography, spacing
│   │   │   └── patterns/         # Page layouts, navigation
│   │   └── tailwind.config.ts
│   │
│   ├── shared/                    # Shared utilities
│   │   ├── api-client/           # Generated API client (OpenAPI)
│   │   ├── validation/           # Zod schemas
│   │   ├── i18n/                 # Localization
│   │   └── utils/                # Helper functions
│   │
│   └── mobile-sdk/                # React Native SDK
│       ├── src/
│       │   ├── components/       # Native UI components
│       │   ├── native-modules/   # iOS/Android bridges
│       │   └── api/              # SDK API surface
│       └── package.json
10.2 State Management
Table
Layer	Technology	Use Case
Server State	React Server Components + Server Actions	Data fetching, mutations
Client State	Zustand	UI state, form state, ephemeral data
Cache	TanStack Query (React Query)	API response caching, background refetch
Real-time	WebSocket (via gateway)	Verification status updates
10.3 Performance Strategy
Server Components for data-heavy pages (reduce client JS)
Streaming SSR for progressive rendering
Image optimization via Next.js Image component (WebP/AVIF)
Code splitting at route level
Edge caching via CloudFlare (static assets, API responses)
Prefetching on hover for internal links
11. Integration Architecture
11.1 Webhook Infrastructure
Delivery Contract:
http
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
Signature Verification:
plain
signature = HMAC-SHA256(webhook_secret, timestamp + "." + payload)
Retry Policy:
Immediate, 1s, 2s, 4s, 8s, 16s, 32s, 64s, 128s, 256s, 512s
Max 24 hours total
Exponential backoff with jitter
11.2 Third-Party Connectors
Table
Integration	Protocol	Auth	Data Flow
Government ID APIs	REST/SOAP	API Key + IP whitelist	Outbound lookup
Credit Bureaus	REST/gRPC	OAuth 2.0 + mTLS	Outbound query
Open Banking	REST/OAuth2	PSD2 SCA	Outbound + callback
Sanctions Lists	REST/CSV	API Key	Scheduled sync
PEP Databases	REST	API Key	Real-time query
Adverse Media	REST	API Key	Real-time search
11.3 Integration Resilience
Circuit breakers per integration (5 failures → open)
Fallback to cached data where appropriate
Timeout: 5s for real-time, 30s for batch
Retry: 3 attempts with exponential backoff
Dead letter queue for failed integrations
12. Deployment Architecture
12.1 Kubernetes Topology
plain
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
12.2 Multi-Region Deployment
plain
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
Failover Strategy:
Automatic failover to nearest healthy region
DNS TTL: 30 seconds for rapid propagation
Data replication lag monitoring: alert if >5 seconds
Read-only mode during partial outages (writes queued)
13. Observability Design
13.1 Metrics
Gateway Metrics (Prometheus):
plain
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
Orchestration Metrics:
plain
# Workflow execution
usora_orchestration_workflow_duration_seconds{workflow_id, tenant_id, status}
usora_orchestration_workflow_active{workflow_id, tenant_id}

# Task queue depth
usora_orchestration_task_queue_depth{task_type, tenant_id}

# Manual review SLA
usora_orchestration_review_wait_seconds{tenant_id, priority}
Compute Metrics:
plain
# Task processing
usora_compute_tasks_processed_total{worker_type, tenant_id, status}
usora_compute_task_duration_seconds{worker_type, tenant_id, model_version}

# Model inference
usora_compute_inference_duration_seconds{model_name, model_version, tenant_id}
usora_compute_inference_errors_total{model_name, error_type}

# Resource utilization
usora_compute_cpu_usage_percent{worker_type, pod_name}
usora_compute_memory_usage_bytes{worker_type, pod_name}
### 13.2 Distributed Tracing
Trace Structure:
plain
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
Context Propagation:
W3C Trace Context headers (traceparent, tracestate)
Baggage for tenant_id, user_id, request_id
OpenTelemetry SDK in all services
### 13.3 Alerting Rules
yaml
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
### 14. Scalability & Performance
### 14.1 Horizontal Scaling Strategy
Table
Layer	Scaling Trigger	Min	Max	Bottleneck
Gateway	CPU >70% or connections >80%	3	100	Network bandwidth
Orchestration	Task queue depth >1000	3	50	Database connections
Compute (Document)	Queue lag >30s	5	200	GPU/CPU availability
Compute (Biometric)	Queue lag >30s	3	150	Memory for FAISS index
Compute (Risk)	Queue lag >30s	3	20	Model inference throughput
Compute (Fraud)	Queue lag >60s	2	10	Graph memory
PostgreSQL (Read)	CPU >80%	2	10	Write throughput
PostgreSQL (Write)	CPU >80%	1	1 (per region)	Disk I/O
Redis	Memory >80%	6	24	Network I/O
Kafka	Consumer lag >1000	3	12	Disk throughput
14.2 Backpressure Handling
plain
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
Backpressure Signals:
Gateway: 429 Too Many Requests (rate limit)
Gateway: 503 Service Unavailable (upstream overload)
Kafka: Consumer group lag metrics
Compute: Task queue depth, worker pool saturation
### 14.3 Caching Strategy
Table
Cache Layer	Technology	TTL	Invalidation	Hit Rate Target
Edge	CloudFlare CDN	1 hour	Cache tags	95%
Gateway response	Redis	5 min	Event-driven	80%
Tenant config	Redis	1 min	Pub/sub	99%
JWT public keys	In-memory	1 hour	Background refresh	99.9%
OPA policies	In-memory	5 min	Webhook	99%
Model weights	In-memory	N/A (load at startup)	Rolling restart	100%
Feature vectors	Redis	1 hour	TTL	85%
15. Disaster Recovery
15.1 RPO / RTO Targets
Table
Data Class	RPO	RTO	Strategy	Test Frequency
Verification transactions	0 (sync replication)	15 min	Multi-region active-active	Monthly
Audit logs	0 (append-only, replicated)	15 min	Kafka MirrorMaker 2	Monthly
Document images	1 hour	30 min	S3 cross-region replication	Quarterly
Analytics data	4 hours	2 hours	ClickHouse replication	Quarterly
Configuration	0 (GitOps)	5 min	Git + ArgoCD	Monthly
Secrets	0 (Vault HA)	5 min	Vault auto-unseal + replication	Monthly
15.2 Backup Strategy
PostgreSQL:
Continuous WAL archiving to S3
Hourly incremental backups
Daily full backups (retained 30 days)
Weekly backups (retained 1 year)
Monthly backups (retained 7 years)
Kafka:
Topic replication factor: 3
Min ISR: 2
Retention: 30 days (events), 7 years (audit)
S3 Documents:
Versioning enabled
Cross-region replication
Object lock (WORM) for audit documents
Lifecycle: Glacier after 90 days
15.3 Failover Procedures
Regional Failover (Automated):
Health checks detect region degradation
DNS failover to healthy region (30s TTL)
Read replicas promoted to primary in target region
Kafka consumer groups rebalanced
Alert fired to on-call engineer
Database Failover:
Patroni/RepMgr detects primary failure
Automatic promotion of most current replica
Connection poolers (PgBouncer) reconfigured
Applications reconnect transparently
16. Operational Considerations
16.1 Capacity Planning
Table
Metric	Current	6-Month Target	12-Month Target
Verifications/day	1M	5M	10M
Peak RPS	500	2,500	5,000
Active tenants	50	200	500
Document storage	10TB	50TB	100TB
Biometric templates	5M	25M	50M
Kafka throughput	100MB/s	500MB/s	1GB/s
16.2 Cost Optimization
Table
Strategy	Implementation	Savings
Spot instances	Compute workers on spot (tolerate eviction)	60-70% compute
Reserved instances	PostgreSQL, Redis on 1-year reserved	40% database
S3 lifecycle	Glacier after 90 days, Deep Archive after 1 year	80% storage
Right-sizing	HPA + VPA for all workloads	20-30% overall
CDN caching	CloudFlare edge caching	50% bandwidth
16.3 Operational Runbooks
See runbook.md for detailed procedures including:
Incident response (4-tier on-call)
Database failover
Certificate