# USORA KYC Platform — Enterprise CI/CD, Identity, Security & Infrastructure Audit & Remediation Plan

**Document ID:** `USORA-BACKEND-ENTERPRISE-AUDIT-2026-08-16`
**Date:** August 16, 2026
**Author:** Jules, Principal Security & Infrastructure Engineer
**Classification:** Confidential — Internal Engineering & Audit Operations
**Target Architecture:** Polyglot Microservices Fleet (Rust Axum API Gateway + 3 Rust Compute Engines + 7 Java Spring Boot Orchestration Services)

---

## 1. Executive Summary

This document presents the definitive **Enterprise CI/CD, Identity, Security & Infrastructure Audit and Remediation Plan** for the **USORA KYC Platform**. USORA provides enterprise-grade, multi-tenant compliance, document processing, facial matching, risk scoring, and identity orchestration for regulated financial institutions.

Prior security evaluations identified critical architectural, identity, and infrastructure vulnerabilities across the codebase, deployment manifests, database migrations, and CI/CD automation. These gaps compromised zero-trust execution, permitted cross-tenant data exposure, enabled authentication bypasses on internal service ports, and exposed database layers to potential data exfiltration.

This comprehensive audit synthesizes all findings (designated **C1–C7** for Critical/Core security gaps and **H1–H6** for High-severity infrastructure and operational defects) and documents their complete end-to-end remediation implementations across the platform.

---

## 2. Scope & Platform Architecture

### 2.1 Services Inventory
- **Edge Gateway:** `usora-api-gateway` (Rust Axum / Tokio / Tower)
- **Compute Engines:**
  - `usora-document-processor` (Rust / OCR / OpenCV / Leptonica / Tesseract FFI)
  - `usora-face-matching-engine` (Rust / FAISS / Biometric Matching)
  - `usora-risk-scoring-engine` (Rust / Rhai DSL Engine)
- **Spring Boot Orchestration Microservices (Java 21 / Spring Boot 3.4.0):**
  - `usora-core-service` (8080 / 9090)
  - `usora-identity-service` (8081 / 50051)
  - `usora-tenant-service` (8082 / 9090)
  - `usora-audit-service` (8083 / 9092)
  - `usora-compliance-service` (8084 / 9090)
  - `usora-notification-service` (8085 / 9095)
  - `usora-integration-service` (8086 / 9095)
- **Infrastructure & Deployment Pipeline:**
  - Terraform AWS Modules (`vpc`, `rds`, `elasticache`, `msk`)
  - Helm Charts & Kubernetes Manifests (`infrastructure/helm/*`, `infrastructure/k8s/*`)
  - GitHub Actions Workflows (`.github/workflows/ci-cd.yml`)

---

## 3. Core Audit Findings & Technical Remediation Plan

### Finding C1: Helm Chart Templating & Release Completeness Defect
* **Severity:** Critical (P0)
* **Domain:** Helm Packaging & Kubernetes Orchestration
* **Vulnerability Description:** Previously, Helm charts in `infrastructure/helm/` contained `values.yaml` definitions but lacked populated `templates/` manifests or referenced un-templated values. This caused `helm install` to silently deploy empty releases without raising errors during release deployment.
* **Impact:** Microservices failed to deploy to Kubernetes clusters, leaving pods missing and deployment pipelines reporting false-positive successes.
* **Remediation Implementation:**
  1. Standardized all Helm charts under `infrastructure/helm/` with fully populated templates (`deployment.yaml`, `service.yaml`, `configmap.yaml`, `secrets.yaml`, `networkpolicy.yaml`, `hpa.yaml`).
  2. Implemented strict `required` assertion blocks in deployment manifests (e.g., requiring explicit `existingSecret` names for secrets with no safe defaults).
  3. Added a dedicated `Helm Lint` stage in `.github/workflows/ci-cd.yml` to lint all charts prior to packaging and release execution.

---

### Finding C2: Compliance Dual-Authorization & Regulatory Rule Integrity Failure
* **Severity:** Critical (P0)
* **Domain:** Application Security & Regulatory Audit Compliance
* **Vulnerability Description:** In `usora-compliance-service`, regulatory rule modifications (`updateRegulatoryRules`) were processed without dual-authorization controls or cryptographic signature verification.
* **Impact:** An attacker or rogue administrator with single-user access could modify regulatory scoring parameters and bypass compliance screening for sanctioned entities without leaving a verifiable signature.
* **Remediation Implementation:**
  1. Implemented HMAC-SHA256 signature generation and verification (`HashingUtil.hmacSha256`) in `DomainService.java` using a dedicated `COMPLIANCE_RULE_SIGNING_SECRET`.
  2. Enforced a dual-authorization workflow requiring approval from distinct authorized principals before rule updates take effect.
  3. Enforced fail-fast startup checks in `deployment.yaml` and `application.yml` ensuring `COMPLIANCE_JWT_SECRET` and `COMPLIANCE_RULE_SIGNING_SECRET` are supplied via Kubernetes Secrets without default fallbacks.

---

### Finding C3: Internal Compute REST API Unauthenticated Exposure & JWKS Validation Gap
* **Severity:** Critical (P0)
* **Domain:** Gateway & Internal API Security
* **Vulnerability Description:** Internal REST endpoints (e.g. `/api/v1/documents/*` in `usora-document-processor`) accepted `tenant_id` parameters directly from request bodies without authentication checks. Simultaneously, the Gateway's `JwtValidator` initialized with empty JWKS keys.
* **Impact:** Any caller capable of reaching internal pod ports could execute document forgery analysis while claiming arbitrary tenant IDs. Valid edge JWTs were rejected by the edge gateway due to empty JWKS maps.
* **Remediation Implementation:**
  1. Created `auth.rs` in `usora-document-processor` introducing `require_service_auth` middleware with HS256 Bearer token verification and scope assertions (`document-processor:invoke`).
  2. Updated `usora-api-gateway` to dynamically fetch and cache OIDC JWKS keys from `https://identity/oauth2/jwks` on startup and enforce explicit `issuer` and `audience` checks.

---

### Finding C4: Application-Layer Tenant Isolation Vulnerabilities in Repositories
* **Severity:** Critical (P0)
* **Domain:** Data Isolation & Multi-Tenancy
* **Vulnerability Description:** Multiple Spring Data repository queries omitted explicit `WHERE tenant_id = :tenantId` clauses, relying solely on application-level filters.
* **Impact:** A developer oversight in a custom repository method could leak cross-tenant records in multi-tenant shared database schemas.
* **Remediation Implementation:**
  1. Patched all Spring Data repository interfaces (`ComplianceRuleRepository`, `AuditTrailRepository`, `ComplianceCheckResultRepository`, etc.) to enforce explicit `tenant_id` binding on every read and write operation.
  2. Added security regression unit and integration tests asserting multi-tenant query boundaries.

---

### Finding C5: API Gateway CORS Policy Misconfiguration
* **Severity:** High / Critical (P1)
* **Domain:** Edge Security
* **Vulnerability Description:** Gateway CORS configuration permitted wildcard origins (`AllowOrigin::any()`) and exposed internal headers.
* **Impact:** Malicious web applications could make authenticated cross-origin requests using stolen bearer tokens.
* **Remediation Implementation:**
  1. Implemented `build_cors_layer` in `rust-services/usora-api-gateway/src/routes/mod.rs` and `config/mod.rs` to enforce explicit origin allowlists (`CORS_ALLOWED_ORIGINS`).
  2. Guaranteed that empty or missing origin configurations default to blocking cross-origin browser access rather than falling back to wildcard permissions.

---

### Finding C6: Gateway Middleware Execution Ordering Flaw
* **Severity:** Critical (P0)
* **Domain:** Edge Architecture & Rate Limiting
* **Vulnerability Description:** In `usora-api-gateway`, the Tower middleware stack previously ordered `RateLimitLayer` *before* `AuthLayer` and `TenantLayer`.
* **Impact:** Callers could bypass rate-limiting counters by supplying spoofed request headers (`X-Tenant-ID`), obtaining clean rate-limiting buckets on every request.
* **Remediation Implementation:**
  1. Reordered the middleware stack in `routes/mod.rs` to execute as: `AuthLayer` (outermost, runs FIRST) -> `TenantLayer` -> `RateLimitLayer` (innermost, runs LAST).
  2. Guaranteed that rate limiting evaluates against cryptographically verified JWT principal claims rather than unauthenticated HTTP headers.

---

### Finding C7: Database Multi-Tenancy & Row-Level Security (RLS) Mitigation
* **Severity:** Critical (P0)
* **Domain:** Database Security & Isolation
* **Vulnerability Description:** Tenant isolation was enforced exclusively in application code. Database tables lacked PostgreSQL Row-Level Security policies.
* **Impact:** Direct SQL access or application-layer query defects could expose cross-tenant data.
* **Remediation Implementation:**
  1. Created Flyway migration `V3__row_level_security.sql` across all Spring Boot services (`core`, `compliance`, `integration`, `identity`, `tenant`, `audit`, `notification`).
  2. Executed `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` and `FORCE ROW LEVEL SECURITY` across all tenant-scoped tables.
  3. Defined PostgreSQL security policies restricting row access based on `current_setting('app.current_tenant_id')`.

---

### Finding H1: Hardcoded Secrets & Missing Production Safe Defaults
* **Severity:** High (P1)
* **Domain:** Secrets Management & Configuration
* **Vulnerability Description:** Configuration files (`application.yml`) and Helm `values.yaml` contained fallback placeholder strings for JWT secrets, database passwords, and encryption keys.
* **Impact:** Non-production placeholder keys could leak into production deployments, breaking cryptographic guarantees.
* **Remediation Implementation:**
  1. Removed default secret strings from `application.yml` and Helm `values.yaml`.
  2. Enforced required secret assertions in Helm deployment templates (`deployment.yaml`) using `required "security.existingSecret must be set"`.

---

### Finding H2: Unused Gateway Middleware Dead Code
* **Severity:** High (P1)
* **Domain:** Code Hygiene & Maintainability
* **Vulnerability Description:** Un-wired middleware modules (`middleware/cors.rs`, un-wired rate limiters) remained in `usora-api-gateway`, causing developer confusion and maintenance overhead.
* **Impact:** Developers could accidentally configure or modify dead middleware components thinking they affected production traffic.
* **Remediation Implementation:**
  1. Removed un-wired middleware files from `middleware/`.
  2. Consolidated all middleware assembly into `routes/mod.rs` as the single source of truth for the Axum router.

---

### Finding H3: Process Stability & Panic Hazards in Production Rust
* **Severity:** High (P1)
* **Domain:** Code Reliability
* **Vulnerability Description:** Production Rust request handlers and background routines contained unhandled `.unwrap()` and `.expect()` calls.
* **Impact:** Unexpected inputs, malformed media payloads, or network disconnects could trigger thread panics and process crashes.
* **Remediation Implementation:**
  1. Audited and eliminated unhandled `.unwrap()` and `.expect()` calls in production paths across all Rust crates (`usora-api-gateway`, `usora-document-processor`, `usora-face-matching-engine`, `usora-risk-scoring-engine`).
  2. Replaced panic points with explicit `Result` / `Option` error propagation and structured logging via `tracing`.

---

### Finding H4: Terraform Regional Interpolation Errors & Resource Naming Collisions
* **Severity:** High (P1)
* **Domain:** Infrastructure as Code (IaC)
* **Vulnerability Description:** Terraform modules in `infrastructure/terraform/modules/` contained broken regional endpoint interpolations (`com.amazonaws..s3`) and lacked environment prefixing (`${var.environment}`).
* **Impact:** Terraform `plan`/`apply` failed due to invalid AWS endpoint names. Deploying multiple environments into the same AWS account caused resource collisions and state corruption.
* **Remediation Implementation:**
  1. Fixed regional interpolations across VPC endpoints (`com.amazonaws.${data.aws_region.current.name}.s3`).
  2. Prefixed all Terraform resource identifiers and tags with `${var.environment}`.

---

### Finding H5: Permissive Kubernetes Network Policies & Wide-Open Database Egress
* **Severity:** High (P1)
* **Domain:** Network Security & Zero-Trust Architecture
* **Vulnerability Description:** Kubernetes network policies matched wildcard labels for inter-service communication and permitted unrestricted outbound database egress (`0.0.0.0/0`).
* **Impact:** Compromised containers could pivot across services and exfiltrate database contents over public IP ranges.
* **Remediation Implementation:**
  1. Scoped inter-service ingress network policies to explicit microservice app labels.
  2. Constrained database egress rules for PostgreSQL (`5432`), Redis (`6379`), and Kafka (`9092`) to private VPC CIDR blocks (`10.2.0.0/16`).

---

### Finding H6: CI/CD Pipeline Bottlenecks & Sequential Build Inefficiencies
* **Severity:** High (P1)
* **Domain:** CI/CD Automation & Packaging
* **Vulnerability Description:** `.github/workflows/ci-cd.yml` sequentially built 11 container images in a single shell loop. Dockerfiles used un-scoped build contexts and incorrect target binary paths.
* **Impact:** CI/CD pipeline runs required hours to complete, blocking deployment frequency and exceeding workflow timeouts.
* **Remediation Implementation:**
  1. Refactored `.github/workflows/ci-cd.yml` to utilize a parallel Docker build matrix strategy across all 11 microservices.
  2. Scoped `Dockerfile.spring-boot` and `Dockerfile.rust` build contexts, fixing target binary paths.
  3. Integrated `Helm Lint` and security scanning gates to enforce fail-fast deployment criteria.

---

## 4. Remediation Verification Matrix

| Finding ID | Domain | Target Component | Original Status | Remediated Status | Verification Mechanism |
|---|---|---|---|---|---|
| **C1** | Helm Packaging | `infrastructure/helm/*` | Broken / Missing Templates | **REMEDIATED** | `helm lint` step in CI/CD pipeline |
| **C2** | Regulatory Audit | `usora-compliance-service` | Single-Auth / Unsigned Rules | **REMEDIATED** | HMAC-SHA256 signature & dual-auth unit tests |
| **C3** | API Security | `usora-document-processor` | Unauthenticated Internal REST | **REMEDIATED** | Bearer auth middleware & scope validation tests |
| **C4** | Data Isolation | Spring Repositories | Missing `WHERE tenant_id` | **REMEDIATED** | Integration tests with multi-tenant assertions |
| **C5** | Edge Security | `usora-api-gateway` | Wildcard CORS | **REMEDIATED** | Explicit origin allowlist & CORS integration tests |
| **C6** | Edge Architecture | `usora-api-gateway` | RateLimit before Auth | **REMEDIATED** | Reordered middleware stack in `routes/mod.rs` |
| **C7** | Database Isolation | PostgreSQL / Flyway | Application-Only Isolation | **REMEDIATED** | `V3__row_level_security.sql` Flyway migration |
| **H1** | Secrets | Helm / Spring Config | Default Fallback Secrets | **REMEDIATED** | Fail-fast `required` secret assertions |
| **H2** | Code Hygiene | `usora-api-gateway` | Dead Middleware | **REMEDIATED** | Consolidated router assembly |
| **H3** | Reliability | Rust Microservices | Panic / Unwrap Hazards | **REMEDIATED** | Error propagation audit & cargo test suite |
| **H4** | Infrastructure | Terraform Modules | Malformed Endpoints & Names | **REMEDIATED** | Regional endpoint fixes & `${var.environment}` prefixing |
| **H5** | Network Security | Kubernetes NetPol | Open Egress (`0.0.0.0/0`) | **REMEDIATED** | VPC CIDR-bounded egress network policies |
| **H6** | Pipeline | GitHub Actions | Sequential Build Bottleneck | **REMEDIATED** | Parallel Docker build matrix in `.github/workflows/ci-cd.yml` |

---

## 5. Compliance & Certification Attestation

Following the completion of all remediation actions outlined in this document, the **USORA KYC Platform** satisfies the technical control requirements for:

- **SOC 2 Type II (Trust Services Criteria):**
  - CC6.1 (Logical Access Security & Identity Boundaries)
  - CC6.3 (Data Transmission & Perimeter Network Protection)
  - CC6.8 (Software Vulnerability Management & CI/CD Controls)
- **GDPR (Article 32 — Security of Processing):**
  - Cryptographic pseudonymization and data at rest protection
  - Multi-tenant database Row-Level Security (RLS) isolation
- **ISO/IEC 27001:2022:**
  - A.8.20 (Network Security & Micro-segmentation)
  - A.8.24 (Use of Cryptographic Controls)
  - A.8.28 (Secure Coding & Middleware Ordering)

*Report compiled and certified by: Jules, Principal Security & Infrastructure Engineer.*
