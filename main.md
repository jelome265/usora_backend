# `main.md`

```markdown
# USORA — Main Project Entrypoint (main.md)

> **Version:** 1.0.0  
> **Last Updated:** 2026-07-25  
> **Classification:** Internal — Engineering Reference  
> **Purpose:** Central navigation hub for all USORA project documents and resources.

---

## 1. Welcome to USORA

USORA is an enterprise-grade, security-first multi-tenant KYC platform built for the modern digital economy. This document is your starting point for navigating the entire USORA engineering ecosystem.

**Quick Stats:**
- **40+ AI engineering agents** across 8 domains
- **4 architectural layers:** Rust Gateway, Java Orchestration, Rust Compute, TypeScript Frontend
- **16 compliance frameworks** mapped and maintained
- **99.99% uptime SLA** with multi-region active-active deployment

---

## 2. Document Navigation

### 2.1 Start Here

| Document | What You'll Find | Read If You... |
|----------|-----------------|----------------|
| `readme.md` | Project overview, quick start, architecture summary | Are new to the project |
| `agent.md` | Complete agent registry, activation rules, cross-dependencies | Need to know which agent to invoke |
| `product.md` | Product specification, market context, feature requirements | Need business context |
| `design.md` | System design, technology rationale, service boundaries | Need architectural deep-dive |

### 2.2 API & Integration

| Document | What You'll Find |
|----------|-----------------|
| `api-spec.md` | REST/gRPC API specification, auth flows, error codes, rate limits |
| `compliance-mapping.md` | Regulatory framework mapping (SOC 2, ISO 27001, GDPR, etc.) |

### 2.3 Operations

| Document | What You'll Find |
|----------|-----------------|
| `runbook.md` | Incident response, SOPs, disaster recovery, on-call procedures |

### 2.4 Architecture Decisions

All ADRs are in `/adr/`:

| ADR | Decision | Status |
|-----|----------|--------|
| `adr-001-rust-gateway.md` | Custom Rust API Gateway over Kong/AWS | Accepted |
| `adr-002-java-orchestration.md` | Spring Boot + Camunda for orchestration | Accepted |
| `adr-003-rust-compute.md` | Rust + Tokio for compute-intensive workloads | Accepted |
| `adr-004-schema-per-tenant.md` | Schema-per-tenant PostgreSQL isolation | Accepted |
| `adr-005-redis-namespacing.md` | Redis namespace-per-tenant strategy | Accepted |
| `adr-006-camunda-workflow.md` | Camunda BPMN for workflow engine | Accepted |
| `adr-007-kafka-topics.md` | Kafka topic design for event-driven architecture | Accepted |
| `adr-008-grpc-communication.md` | gRPC for internal service communication | Accepted |

### 2.5 Agent Specifications

All agent specs are in `/agents/`. See `agent.md` §4 for the full registry.

**Quick Access by Domain:**

- **Platform:** `platform-gateway`, `platform-identity`, `platform-infra`, `platform-observability`, `platform-secrets`
- **Security:** `security-zero-trust`, `security-threat-model`, `security-audit`, `security-penetration`
- **Orchestration:** `orchestrator-core`, `orchestrator-workflow`, `orchestrator-case`, `orchestrator-tenant`, `orchestrator-compliance`
- **Compute:** `compute-document-analysis`, `compute-identity-verification`, `compute-biometric-matching`, `compute-risk-scoring`, `compute-fraud-detection`, `compute-aml-screening`
- **Data:** `data-postgresql`, `data-redis`, `data-kafka`, `data-s3`, `data-retention`, `data-clickhouse`, `data-elasticsearch`
- **Frontend:** `frontend-portal`, `frontend-applicant`, `frontend-mobile`, `frontend-design-system`
- **Integration:** `integration-webhook`, `integration-banking`, `integration-credit`, `integration-government`
- **AI/ML:** `ai-feature-store`, `ai-model-ops`, `ai-nlp`, `ai-explainability`
- **DevOps:** `devops-cicd`, `devops-sre`, `devops-testing`, `devops-cost`

### 2.6 UI/UX Specifications

All page specs are in `/usora-pages/`:

| Page | Description |
|------|-------------|
| `01_Core_Platform.md` | Platform management, tenant provisioning, system health |
| `02_KYC_Operations.md` | Verification workflows, case queues, review processes |
| `03_Customer_Management.md` | Customer profiles, verification history, data subjects |
| `04_Document_Verification.md` | Document upload, OCR results, forensic analysis |
| `05_Risk_Compliance.md` | Risk scores, compliance reports, regulatory dashboards |
| `06_Admin_Settings.md` | Tenant configuration, user management, billing |
| `07_Security_Identity.md` | IAM, audit logs, security policies, access reviews |
| `08_Analytics_Reporting.md` | Analytics dashboards, reports, data exports |
| `09_API_Developer.md` | API keys, webhooks, SDK documentation, sandbox |

---

## 3. Technology Stack Summary

| Layer | Technology | Version | Why |
|-------|-----------|---------|-----|
| API Gateway | Rust + Tokio + Axum | Latest stable | Zero-GC latency, memory safety, millions of connections |
| Orchestration | Java 21 + Spring Boot 4.1 | LTS | Virtual Threads, mature BPMN ecosystem |
| Compute | Rust + Tokio + ONNX | Latest stable | CPU-intensive ML without GC pauses |
| Frontend | TypeScript 5 + React 19 + Tailwind 4 | Latest stable | Type safety, Server Components, modern DX |
| Data (Primary) | PostgreSQL 16 | LTS | ACID, per-tenant schema isolation |
| Data (Cache) | Redis 7 | LTS | Sub-millisecond cache, session store |
| Data (Events) | Kafka 3.x | Latest stable | Event-driven backbone, stream processing |
| Data (Analytics) | ClickHouse | Latest stable | Columnar analytics, time-series |
| Data (Search) | Elasticsearch 8.x | Latest stable | Full-text search, audit indexing |
| Data (Objects) | S3 / MinIO | Latest stable | Document storage, encryption at rest |
| Secrets | HashiCorp Vault | Latest stable | Dynamic credentials, auto-rotation |
| Identity | SPIFFE/SPIRE | Latest stable | Workload identity, mTLS everywhere |
| Observability | OpenTelemetry + Prometheus + Grafana | Latest stable | Distributed tracing, metrics, dashboards |
| CI/CD | GitHub Actions + ArgoCD | Latest stable | GitOps, canary deployments |
| Infrastructure | Terraform + Kubernetes | Latest stable | IaC, container orchestration |

---

## 4. Quick Reference

### 4.1 Performance Targets

| Metric | Target | Measured At |
|--------|--------|-------------|
| Gateway p99 latency | < 10ms | API Gateway |
| Orchestration p99 latency | < 50ms | Spring Boot services |
| Compute p99 latency | < 200ms | Rust ML workers |
| End-to-end (simple) | < 100ms | Full verification flow |
| End-to-end (complex) | < 5s | Multi-step verification |
| Database query (cached) | < 5ms | PostgreSQL + Redis |
| Database query (uncached) | < 50ms | PostgreSQL |

### 4.2 Security Posture

| Control | Implementation |
|---------|---------------|
| Authentication | OAuth 2.0 + PKCE, API Key + HMAC, mTLS |
| Authorization | RBAC + ABAC with OPA/Rego |
| Encryption at rest | AES-256-GCM, tenant-specific keys in HSM |
| Encryption in transit | TLS 1.3, mTLS for internal services |
| Tenant isolation | Schema-per-tenant, namespace-per-tenant, row-level security |
| Audit logging | Immutable, blockchain-anchored quarterly |
| Secret management | HashiCorp Vault, 1h TTL dynamic credentials |

### 4.3 Compliance Frameworks

> **Status key change (2026-07-31):** this table previously stated several
> frameworks as "Certified"/"Compliant" in present tense. Per
> `docs/architecture-security-review-2026-07-31.md`, several controls this
> platform's compliance posture depends on (tenant isolation, AML/sanctions
> screening gating, audit-trail signing) were found to be bypassable or
> non-functional as implemented, and have since been patched but not yet
> independently re-verified. Until re-verified, this table reflects
> **target state**, not evidenced current compliance — do not present it to
> an auditor, customer, or investor as proof of current certification.

| Framework | Status (target — pending independent verification) |
|-----------|--------|
| SOC 2 Type II | Target — not yet certified |
| ISO 27001:2022 | Target — In Progress (Q4 2026) |
| ISO 27701 | Target — In Progress (Q4 2026) |
| PCI DSS Level 1 | Target — In Progress (Q1 2027) |
| GDPR | Target — controls patched, not yet independently verified |
| CCPA/CPRA | Target — controls patched, not yet independently verified |
| LGPD | Target — controls patched, not yet independently verified |
| PIPEDA | Target — controls patched, not yet independently verified |
| PDPA (Singapore) | Target — controls patched, not yet independently verified |
| FATF Recommendations | Target — controls patched, not yet independently verified |
| EU AML5/AML6 | Target — controls patched, not yet independently verified |
| US BSA/Patriot Act | Target — controls patched, not yet independently verified |
| PSD2/SCA | Target — controls patched, not yet independently verified |
| MiFID II | Target — controls patched, not yet independently verified |

---

## 5. Getting Started

### 5.1 For New Engineers

1. Read `readme.md` for project overview
2. Read `design.md` §1-2 for architecture philosophy
3. Read `agent.md` §4 to understand the agent taxonomy
4. Pick your domain and read the relevant `*.agent.md` files
5. Review `api-spec.md` §2-3 for authentication and common patterns

### 5.2 For Product Managers

1. Read `product.md` for full product specification
2. Read `usora-pages/*.md` for UI/UX specifications
3. Review `compliance-mapping.md` for regulatory context

### 5.3 For Security Auditors

1. Read `security-zero-trust.agent.md` for security architecture
2. Read `compliance-mapping.md` for control evidence
3. Review `adr/adr-001*` through `adr-008*` for architectural decisions
4. Read `runbook.md` for incident response procedures

### 5.4 For DevOps/SRE

1. Read `platform-infra.agent.md` for infrastructure
2. Read `devops-cicd.agent.md` and `devops-sre.agent.md` for operations
3. Read `runbook.md` for operational procedures
4. Review `platform-observability.agent.md` for monitoring setup

---

## 6. Contributing

### 6.1 Document Changes

All changes to core documents (`main.md`, `readme.md`, `agent.md`, `product.md`, `design.md`, `api-spec.md`, `compliance-mapping.md`, `runbook.md`) require:

1. Update to the document's change log
2. Cross-reference update in `main.md` if navigation changes
3. ADR if the change represents a new architectural decision

### 6.2 Agent Changes

Changes to agent specifications require:

1. Update to the agent's `*.agent.md` file
2. Update to `agent.md` §4 if the agent taxonomy changes
3. Update to `agent.md` §5 if cross-agent dependencies change

### 6.3 ADR Process

New architectural decisions require:

1. New file in `/adr/` following the `adr-NNN-title.md` naming convention
2. Update to `main.md` §2.4
3. Status: `Proposed` → `Accepted` / `Rejected` / `Superseded`

---

## 7. Support & Contact

| Role | Contact | Responsibility |
|------|---------|---------------|
| Architecture Team | `#usora-architecture` | Design decisions, tech stack, ADRs |
| Security Team | `#usora-security` | Threat models, penetration tests, incidents |
| Platform Team | `#usora-platform` | Infrastructure, gateway, observability |
| Orchestration Team | `#usora-orchestration` | Business logic, workflows, compliance |
| Compute Team | `#usora-compute` | ML inference, document/biometric processing |
| Data Team | `#usora-data` | Databases, caching, streaming, retention |
| Frontend Team | `#usora-frontend` | Web portal, applicant flows, mobile SDKs |
| AI/ML Team | `#usora-ai` | Feature store, model ops, NLP, explainability |
| DevOps Team | `#usora-devops` | CI/CD, SRE, testing, cost optimization |

---

## 8. Change Log

| Date | Version | Change | Author |
|------|---------|--------|--------|
| 2026-07-25 | 1.0.0 | Initial release — complete navigation hub with document registry, quick reference, and getting started guides | USORA Architecture Team |

---