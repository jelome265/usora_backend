# USORA — Agent Registry & Context Document (agent.md)

> **Version:** 1.0.0  
> **Last Updated:** 2026-07-25  
> **Classification:** Internal — Engineering Reference  
> **Purpose:** Central registry of all AI engineering agents, their domains, dependencies, and activation rules for the USORA platform.

---
## backend only.
---

---
## always use agents only.
---

---
## always use c4 coding standards with enterprise indentation 
---

## 1. Document Purpose

This document serves as the single source of truth for all AI agents in the USORA ecosystem. It defines:

- **Agent taxonomy** — how agents are organized by domain and layer
- **Agent capabilities** — what each agent owns and is responsible for
- **Cross-agent dependencies** — which agents must collaborate for end-to-end features
- **Activation rules** — when to invoke which agent(s) for a given task
- **Shared context** — architectural principles, conventions, and guardrails all agents must follow

When you (the AI assistant) are asked to work on any USORA component, consult this document to identify the correct agent(s) to activate and the boundaries you must respect.

---

## 2. USORA Architecture at a Glance

USORA is a polyglot, multi-tenant KYC platform with four architectural layers:

| Layer | Technology | Responsibility |
|-------|-----------|----------------|
| **Edge & Gateway** | Rust + Tokio | TLS termination, auth, routing, rate limiting |
| **Orchestration** | Spring Boot 4.1 + Java 21 (Virtual Threads) | Business workflows, state machines, compliance logic |
| **Compute** | Rust + Tokio | ML inference, document analysis, biometric matching, fraud detection |
| **Data** | PostgreSQL, Redis, Kafka, S3, ClickHouse, Elasticsearch | Persistence, caching, events, analytics |
| **Frontend** | TypeScript 5 + React 19 + Tailwind 4 | Web portal, applicant flows, admin dashboards |
| **Platform** | Kubernetes, Terraform, ArgoCD | Infrastructure, observability, secrets, identity |

Every agent in this registry maps to one or more of these layers.

---

## 3. Agent Taxonomy

Agents are organized into **8 domains**, each covering a vertical slice of the platform:

```
usora/
├── platform/          # Foundation infrastructure & shared services
├── security/          # Security, compliance, zero-trust architecture
├── orchestrator/      # Business logic, workflows, case management
├── compute/           # ML/AI workloads, document & biometric processing
├── data/              # Persistence, caching, streaming, retention
├── frontend/          # User-facing applications & design system
├── integration/       # Third-party connectors & webhook infrastructure
├── ai/                # AI/ML platform services (feature store, model ops, NLP)
└── devops/            # CI/CD, SRE, testing, cost optimization
```

---

## 4. Agent Registry

### 4.1 Platform Domain (`platform-*`)

| Agent | File | Layer | Responsibility |
|-------|------|-------|----------------|
| `platform-gateway` | `platform-gateway.agent.md` | Rust (Gateway) | Custom API gateway: TLS, auth, routing, rate limiting, protocol translation |
| `platform-identity` | `platform-identity.agent.md` | Rust + Java | SPIFFE/SPIRE identity federation, service-to-service mTLS |
| `platform-infra` | `platform-infra.agent.md` | Terraform/K8s | Infrastructure as Code, multi-region deployment, networking |
| `platform-observability` | `platform-observability.agent.md` | Rust/Java/TS | OpenTelemetry tracing, Prometheus metrics, Grafana dashboards, alerting |
| `platform-secrets` | `platform-secrets.agent.md` | HashiCorp Vault | Secret lifecycle, dynamic credentials, certificate rotation |

### 4.2 Security Domain (`security-*`)

| Agent | File | Layer | Responsibility |
|-------|------|-------|----------------|
| `security-zero-trust` | `security-zero-trust.agent.md` | All | Zero-trust architecture: never trust, always verify, least privilege |
| `security-threat-model` | `security-threat-model.agent.md` | All | Threat modeling, attack surface analysis, risk registers |
| `security-audit` | `security-audit.agent.md` | All | Immutable audit logging, blockchain anchoring, compliance evidence |
| `security-penetration` | `security-penetration.agent.md` | All | Penetration testing, red team exercises, vulnerability management |

### 4.3 Orchestrator Domain (`orchestrator-*`)

| Agent | File | Layer | Responsibility |
|-------|------|-------|----------------|
| `orchestrator-core` | `orchestrator-core.agent.md` | Java (Spring Boot) | Core business services, domain models, transaction management |
| `orchestrator-workflow` | `orchestrator-workflow.agent.md` | Java + Camunda | BPMN workflow engine, verification lifecycle orchestration |
| `orchestrator-case` | `orchestrator-case.agent.md` | Java | Case management, human-in-the-loop review queues, escalations |
| `orchestrator-tenant` | `orchestrator-tenant.agent.md` | Java | Tenant lifecycle, schema provisioning, isolation enforcement |
| `orchestrator-compliance` | `orchestrator-compliance.agent.md` | Java | Regulatory rule engine, AML screening triggers, compliance decisions |

### 4.4 Compute Domain (`compute-*`)

| Agent | File | Layer | Responsibility |
|-------|------|-------|----------------|
| `compute-document-analysis` | `compute-document-analysis.agent.md` | Rust | OCR, document forensics, tampering detection, format validation |
| `compute-identity-verification` | `compute-identity-verification.agent.md` | Rust | Identity cross-reference, watchlist matching, sanction screening |
| `compute-biometric-matching` | `compute-biometric-matching.agent.md` | Rust | Face/fingerprint matching, liveness detection, deepfake detection |
| `compute-risk-scoring` | `compute-risk-scoring.agent.md` | Rust | ML-powered risk scoring, behavioral analytics, anomaly detection |
| `compute-fraud-detection` | `compute-fraud-detection.agent.md` | Rust | Real-time fraud detection, graph neural networks, link analysis |
| `compute-aml-screening` | `compute-aml-screening.agent.md` | Rust | AML/CTF screening, PEP checks, adverse media monitoring |

### 4.5 Data Domain (`data-*`)

| Agent | File | Layer | Responsibility |
|-------|------|-------|----------------|
| `data-postgresql` | `data-postgresql.agent.md` | PostgreSQL | Per-tenant schema isolation, query optimization, replication |
| `data-redis` | `data-redis.agent.md` | Redis | Session store, distributed cache, rate limit counters |
| `data-kafka` | `data-kafka.agent.md` | Kafka | Event bus, stream processing, topic design, consumer groups |
| `data-s3` | `data-s3.agent.md` | S3/MinIO | Document storage, artifact lifecycle, encryption at rest |
| `data-retention` | `data-retention.agent.md` | All | Data retention policies, automated deletion, secure wiping |
| `data-clickhouse` | `data-clickhouse.agent.md` | ClickHouse | Analytics warehouse, time-series metrics, aggregation queries |
| `data-elasticsearch` | `data-elasticsearch.agent.md` | Elasticsearch | Full-text search, audit log indexing, compliance search |

### 4.6 Frontend Domain (`frontend-*`)

| Agent | File | Layer | Responsibility |
|-------|------|-------|----------------|
| `frontend-portal` | `frontend-portal.agent.md` | TypeScript/React | Admin portal, tenant dashboards, analytics visualization |
| `frontend-applicant` | `frontend-applicant.agent.md` | TypeScript/React | End-user KYC flows, document upload, biometric capture |
| `frontend-mobile` | `frontend-mobile.agent.md` | TypeScript/React Native | iOS/Android SDKs, native biometric integration |
| `frontend-design-system` | `frontend-design-system.agent.md` | TypeScript/Tailwind | Component library, accessibility (WCAG 2.1 AA), theming |

### 4.7 Integration Domain (`integration-*`)

| Agent | File | Layer | Responsibility |
|-------|------|-------|----------------|
| `integration-webhook` | `integration-webhook.agent.md` | Rust/Java | Webhook delivery, retry logic, circuit breakers, signature verification |
| `integration-banking` | `integration-banking.agent.md` | Java | Banking API connectors (Open Banking, SWIFT, core banking) |
| `integration-credit` | `integration-credit.agent.md` | Java | Credit bureau integrations, scoring API adapters |
| `integration-government` | `integration-government.agent.md` | Java | Government ID verification, tax authority, registry lookups |

### 4.8 AI/ML Domain (`ai-*`)

| Agent | File | Layer | Responsibility |
|-------|------|-------|----------------|
| `ai-feature-store` | `ai-feature-store.agent.md` | Rust/Java | Feature engineering, online/offline feature serving, versioning |
| `ai-model-ops` | `ai-model-ops.agent.md` | Rust/Python | Model training, versioning, A/B testing, drift detection |
| `ai-nlp` | `ai-nlp.agent.md` | Rust/Python | Natural language processing, document classification, entity extraction |
| `ai-explainability` | `ai-explainability.agent.md` | Rust/Python | Model explainability (SHAP, LIME), bias detection, fairness metrics |

### 4.9 DevOps Domain (`devops-*`)

| Agent | File | Layer | Responsibility |
|-------|------|-------|----------------|
| `devops-cicd` | `devops-cicd.agent.md` | GitHub Actions/ArgoCD | Build pipelines, SAST/DAST, artifact signing, GitOps |
| `devops-sre` | `devops-sre.agent.md` | Kubernetes | SLO/SLI definitions, incident response, on-call rotation |
| `devops-testing` | `devops-testing.agent.md` | All | Test strategy, chaos engineering, load testing, contract tests |
| `devops-cost` | `devops-cost.agent.md` | All | Cost optimization, FinOps, resource right-sizing, spot instances |

---

## 5. Cross-Agent Dependency Map

### 5.1 Critical Path: Verification Flow

When a user initiates a KYC verification, the following agents collaborate:

```
frontend-applicant
    → platform-gateway (auth, routing)
        → orchestrator-workflow (BPMN lifecycle)
            → compute-document-analysis (OCR/forensics)
            → compute-biometric-matching (liveness/face match)
            → compute-risk-scoring (ML risk score)
            → compute-aml-screening (sanctions/PEP)
        → orchestrator-case (if manual review triggered)
    → integration-webhook (async result delivery)
    → security-audit (immutable audit trail)
    → data-postgresql (state persistence)
    → data-kafka (event propagation)
```

### 5.2 Critical Path: Tenant Onboarding

```
frontend-portal (admin initiates)
    → platform-gateway
        → orchestrator-tenant (tenant provisioning)
            → platform-identity (SPIFFE identity)
            → platform-secrets (encryption keys)
            → data-postgresql (schema creation)
            → data-redis (namespace setup)
            → data-kafka (topic provisioning)
        → orchestrator-compliance (regulatory config)
    → security-audit (onboarding audit trail)
```

### 5.3 Critical Path: Model Deployment

```
ai-model-ops (training complete)
    → devops-cicd (pipeline trigger)
        → compute-ml-inference (model serving)
            → ai-feature-store (feature serving)
            → platform-observability (model metrics)
        → security-audit (model approval trail)
    → orchestrator-workflow (workflow update if needed)
```

---

## 6. Activation Rules

When responding to a user request, use these rules to determine which agent(s) to activate:

| User Intent | Primary Agent(s) | Secondary Agent(s) | Notes |
|-------------|-----------------|-------------------|-------|
| "Build the API gateway" | `platform-gateway` | `platform-identity`, `security-zero-trust` | Gateway is the entrypoint; identity and security are prerequisites |
| "Design a verification workflow" | `orchestrator-workflow` | `orchestrator-core`, `compute-*`, `frontend-applicant` | Workflows touch all layers |
| "Add biometric capture" | `frontend-applicant`, `compute-biometric-matching` | `frontend-mobile`, `platform-observability` | Biometrics span frontend and compute |
| "Set up a new tenant" | `orchestrator-tenant` | `data-postgresql`, `data-redis`, `data-kafka`, `platform-secrets` | Tenant provisioning is data-heavy |
| "Implement fraud detection" | `compute-fraud-detection` | `compute-risk-scoring`, `ai-feature-store`, `ai-model-ops` | Fraud detection is ML-heavy |
| "Configure CI/CD" | `devops-cicd` | `devops-testing`, `devops-sre`, `security-audit` | CI/CD must include security gates |
| "Add a new compliance rule" | `orchestrator-compliance` | `security-audit`, `orchestrator-workflow` | Compliance changes need audit trails |
| "Optimize database queries" | `data-postgresql` | `platform-observability`, `orchestrator-core` | Performance optimization needs metrics |
| "Design the admin dashboard" | `frontend-portal` | `frontend-design-system`, `orchestrator-case` | Dashboards consume orchestrator data |
| "Set up monitoring" | `platform-observability` | `devops-sre`, `platform-infra` | Observability spans infra and apps |

---

## 7. Shared Context & Conventions

All agents must adhere to these architectural invariants:

### 7.1 Tenant Isolation
- Every data access must include `tenant_id` validation
- No cross-tenant queries under any circumstances
- Schema-per-tenant in PostgreSQL; namespace-per-tenant in Redis
- Tenant context propagated via OpenTelemetry baggage

### 7.2 Security Defaults
- All inter-service communication uses mTLS (SPIFFE/SPIRE)
- Secrets never stored in code; always fetched from Vault at runtime
- API keys use HMAC-SHA256 with timestamp + nonce
- JWT tokens: RS256, 1-hour TTL, strict audience validation

### 7.3 Observability
- Every request generates a UUID v7 `X-Request-ID`
- OpenTelemetry traces propagated via W3C Trace Context
- Prometheus metrics: `usora_<domain>_<metric>_total` (counters), `usora_<domain>_<metric>_duration_seconds` (histograms)
- Structured logging: JSON, include `trace_id`, `span_id`, `tenant_id`, `user_id`

### 7.4 Error Handling
- Use the standard USORA error format (see `api-spec.md` §4)
- Never expose internal stack traces to clients
- Log full error context at `ERROR` level with trace correlation
- Circuit breakers: 50% error threshold, 30s timeout, exponential backoff

### 7.5 Data Retention
- Default retention: 7 years (financial regulations)
- PII: encrypted at rest (AES-256-GCM), tenant-specific keys
- Audit logs: immutable, blockchain-anchored quarterly
- Right to erasure: automated within 30 days of request

### 7.6 Performance Targets
- API Gateway p99 latency: < 10ms
- End-to-end verification p99 latency: < 100ms (simple), < 5s (complex)
- Orchestration layer p99 latency: < 50ms
- Compute layer p99 latency: < 200ms (ML inference)
- Database query p99 latency: < 5ms (cached), < 50ms (uncached)

---

## 8. Document Cross-References

| Document | Purpose | Location |
|----------|---------|----------|
| `main.md` | Project entrypoint, navigation, quick links | `/main.md` |
| `readme.md` | Project overview, getting started, architecture summary | `/readme.md` |
| `product.md` | Product specification, market context, feature requirements | `/product.md` |
| `design.md` | System design, technology rationale, service boundaries | `/design.md` |
| `api-spec.md` | REST/gRPC API specification, error codes, auth flows | `/api-spec.md` |
| `compliance-mapping.md` | Regulatory framework mapping, control evidence | `/compliance-mapping.md` |
| `runbook.md` | Operational runbooks, incident response, SOPs | `/runbook.md` |
| `adr/adr-NNN-*.md` | Architecture Decision Records | `/adr/` |
| `agents/*.agent.md` | Individual agent specifications | `/agents/` |
| `usora-pages/*.md` | UI/UX page specifications | `/usora-pages/` |

---

## 9. Change Log

| Date | Version | Change | Author |
|------|---------|--------|--------|
| 2026-07-25 | 1.0.0 | Initial release — complete agent registry with 40 agents across 8 domains | USORA Architecture Team |


---

