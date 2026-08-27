# USORA KYC Platform — Consolidated Enterprise Security Architecture Review

**Author:** Jules, Principal Security & Infrastructure Engineer
**Date:** August 2026
**Classification:** Confidential — Internal Engineering & Security Audit Operations
**Target Architecture:** Polyglot Microservices Fleet (Rust Axum API Gateway + 3 Rust Compute Engines + 7 Java Spring Boot Orchestration Microservices)

---

## 1. Executive Summary

This document presents the consolidated **Enterprise Security Architecture Review** for the **USORA KYC Platform**. USORA is a multi-tenant, enterprise-grade compliance, verification, and risk-scoring platform designed for regulated financial institutions.

Maintaining zero-trust execution, strict tenant isolation, cryptographic non-repudiation, robust network policies, and safe container orchestration is essential for USORA to comply with **SOC 2 Type II**, **GDPR (Article 32)**, **EU AML5/AML6**, and **ISO/IEC 27001:2022** standards.

This security architecture review synthesizes all architectural analysis, static code audits, infrastructure-as-code assessments, and database isolation evaluations across the platform (referencing `AUDIT-usora-security-2026-08-03.md`, `rust_review.md`, `docs/infrastructure-deep-review-2026-08-04.md`, `docs/USORA-BACKEND-ENTERPRISE-AUDIT-2026-08-16.md`, and `docs/architecture-security-review-2026-07-31.md`). It structures the security evaluation using the **C4 Architecture Model** (Context, Containers, Components, Code/Data) and establishes a clear remediation roadmap and verification matrix.

---

## 2. C4 Security Architecture Breakdown

### 2.1 Level 1: System Context (Platform Boundaries & Threat Model)

```
                       +-----------------------------------+
                       |    External Clients & Banking     |
                       |   Portals / Compliance Portals    |
                       +-----------------+-----------------+
                                         |
                                         | HTTPS / TLS 1.3 (Bearer JWT)
                                         v
+-----------------------------------------------------------------------------------+
| USORA Enterprise KYC Platform Boundary                                            |
|                                                                                   |
|  +-------------------+        +--------------------+       +-------------------+  |
|  |  Identity Provider| <----> |  Rust Axum Gateway | ----> |  Compute Engines  |  |
|  | (OAuth2/OIDC/JWKS)|        |   (Port 8080/9090) |       | (Doc, Face, Risk) |  |
|  +-------------------+        +---------+----------+       +-------------------+  |
|                                         |                                         |
|                                         | gRPC / Internal REST (mTLS)             |
|                                         v                                         |
|                       +----------------------------------+                        |
|                       | Java Spring Boot Microservices   |                        |
|                       | (Core, Compliance, Audit, etc.)  |                        |
|                       +-----------------+----------------+                        |
+-----------------------------------------|-----------------------------------------+
                                          |
                                          v
                    +----------------------------------------------+
                    | Infrastructure Tier (VPC Isolated)           |
                    | PostgreSQL (RLS), Redis, Kafka, AWS S3/MinIO |
                    +----------------------------------------------+
```

* **External Threat Boundary:** All incoming client requests enter exclusively through `usora-api-gateway` over HTTPS/TLS 1.3.
* **Identity & Authentication:** OAuth 2.0 / OIDC tokens issued by `usora-identity-service` are validated at the edge gateway via public key JWKS endpoints (`/oauth2/jwks`).
* **Multi-Tenancy Assurance:** Tenant identifiers are embedded in cryptographically signed JWT claims (`tid`) and enforced end-to-end across routing, microservices, and PostgreSQL Row-Level Security (RLS).

---

### 2.2 Level 2: Container & Network Topology

The platform consists of 11 core microservices deployed inside Kubernetes with strict NetworkPolicies:

1. **Edge Gateway Tier:**
   * `usora-api-gateway` (Rust Axum / Tokio / Tower) — Reverse proxy, rate limiting, JWKS validation, tenant context propagation.
2. **Compute Engine Tier (Rust High-Performance Asynchronous Engines):**
   * `usora-document-processor` (Rust / OCR / OpenCV / Leptonica / Tesseract FFI) — Passport/ID document analysis & MRZ validation.
   * `usora-face-matching-engine` (Rust / FAISS / Vector Biometric Matching) — Facial liveness & 1:N vector search.
   * `usora-risk-scoring-engine` (Rust / Rhai DSL Engine) — Sandboxed execution of dynamic compliance risk rules.
3. **Orchestration Microservice Tier (Java 21 / Spring Boot 3.4.0):**
   * `usora-core-service` (Port 8080/9090) — Core customer identity & verification workflow orchestration.
   * `usora-identity-service` (Port 8081/50051) — OAuth2/OIDC token issuer, user directory, JWKS provider.
   * `usora-tenant-service` (Port 8082/9090) — Tenant onboarding, feature flagging, multi-tenant policy configuration.
   * `usora-audit-service` (Port 8083/9092) — Cryptographically anchored, immutable audit ledger.
   * `usora-compliance-service` (Port 8084/9090) — Regulatory screening (AML/PEP/Sanctions), dual-auth rule signing.
   * `usora-notification-service` (Port 8085/9095) — Secure webhook, email, and SMS dispatching.
   * `usora-integration-service` (Port 8086/9095) — SSRF-guarded external legacy system integration.
4. **Data & Messaging Storage Tier:**
   * PostgreSQL (Multi-tenant schemas with Row-Level Security)
   * Redis (Distributed session & rate-limiting cache)
   * Apache Kafka (Event-driven asynchronous messaging broker)
   * S3 / MinIO (Encrypted evidence document object store)

---

### 2.3 Level 3: Component Security Architecture

* **Gateway Middleware Pipeline (`routes/mod.rs`):**
  * Execution Order: `AuthLayer` (Outermost, verifies RS256 JWT & JWKS) $\rightarrow$ `TenantLayer` (Extracts `tid` claim) $\rightarrow$ `RateLimitLayer` (Innermost, enforces per-tenant rate limits).
* **Compliance Dual-Authorization & Rule Signing (`usora-compliance-service`):**
  * Regulatory rules require dual-principal authorization. Rule integrity is guaranteed via HMAC-SHA256 signatures (`HashingUtil.hmacSha256`) using `COMPLIANCE_RULE_SIGNING_SECRET`.
* **Outbound Egress SSRF Guard (`usora-integration-service`):**
  * `EgressUrlGuard.assertSafeDestination()` validates outbound URLs at call time against RFC1918, loopback, link-local, CGNAT, IPv6 ULA, and cloud metadata IP ranges (`169.254.169.254`).
* **Compute Engine Sandboxing & FFI Safety:**
  * Document & Face matching compute engines isolate C/C++ FFI calls (OpenCV, Tesseract, FAISS).
  * Risk scoring executes within a memory-bounded Rhai DSL engine (`features = ["sync"]`).

---

### 2.4 Level 4: Code, Data & Infrastructure Security

* **Database Multi-Tenancy (Row-Level Security):**
  * Flyway migration `V3__row_level_security.sql` applies `ENABLE ROW LEVEL SECURITY` and `FORCE ROW LEVEL SECURITY` on all tenant tables across Spring microservices. Row access is restricted via PostgreSQL session setting `current_setting('app.current_tenant_id')`.
* **Data Encryption at Rest & In Transit:**
  * AES-256-GCM encryption (`EncryptionUtil`) protects PII evidence records in compliance storage.
  * Fail-fast startup checks ensure zero-key fallbacks (`new byte[32]`) or missing secrets raise immediate boot exceptions.
* **Infrastructure as Code (Terraform & Kubernetes):**
  * AWS VPC endpoints properly interpolate regional names (`com.amazonaws.${data.aws_region.current.name}.s3`).
  * Kubernetes NetworkPolicies restrict inter-service ingress to explicit pod labels and bound database egress (ports 5432, 6379, 9092) to private VPC CIDR blocks (`10.2.0.0/16`).

---

## 3. Consolidated Audit Findings & Status Matrix

The following table summarizes all critical (**C1–C7**), high (**H1–H6**), and medium (**P0–P2**) findings evaluated across prior security assessments:

| Finding ID | Finding Description | Original Severity | Current Status | Remediation & Verification Summary |
|---|---|---|---|---|
| **C1** | Helm Chart Templating & Release Completeness Defect | Critical (P0) | **REMEDIATED** | Standardized templates in `infrastructure/helm/*`; added `helm lint` step in CI. |
| **C2** | Compliance Dual-Authorization & Rule Signing Defect | Critical (P0) | **REMEDIATED** | Implemented HMAC-SHA256 rule signatures and dual-principal approval in `compliance-service`. |
| **C3** | Internal Compute REST API Unauthenticated Exposure & JWKS Gap | Critical (P0) | **REMEDIATED** | Added Bearer auth middleware in `document-processor`; gateway dynamically loads JWKS. |
| **C4** | Spring Data Repositories Omitted `WHERE tenant_id` Clauses | Critical (P0) | **REMEDIATED** | Bound explicit `tenant_id` parameters across all repository queries and added test assertions. |
| **C5** | Gateway CORS Policy Misconfiguration (Wildcard `Any`) | Critical (P0) | **REMEDIATED** | Restricted CORS to explicit origin allowlist (`CORS_ALLOWED_ORIGINS`) in `routes/mod.rs`. |
| **C6** | Gateway Middleware Ordering Flaw (RateLimit before Auth) | Critical (P0) | **REMEDIATED** | Reordered middleware to execute Auth $\rightarrow$ Tenant $\rightarrow$ RateLimit in `routes/mod.rs`. |
| **C7** | Database Lacked Row-Level Security (RLS) Policies | Critical (P0) | **REMEDIATED** | Applied Flyway `V3__row_level_security.sql` across all Spring Boot PostgreSQL schemas. |
| **H1** | Hardcoded Default Secrets in Config & Helm Values | High (P1) | **REMEDIATED** | Removed fallback default keys; enforced fail-fast startup assertions for missing secrets. |
| **H2** | Unused Gateway Middleware Dead Code | High (P1) | **REMEDIATED** | Removed dead middleware files and consolidated router assembly in `routes/mod.rs`. |
| **H3** | Rust Process Panic Hazards (`.unwrap()` / `.expect()`) | High (P1) | **REMEDIATED** | Replaced panics with explicit `Result`/`Option` error handling and structured tracing. |
| **H4** | Terraform Regional Endpoint & Naming Collision Defects | High (P1) | **REMEDIATED** | Fixed AWS regional endpoint interpolations and added `${var.environment}` prefixes. |
| **H5** | Permissive Network Policies & Open Egress (`0.0.0.0/0`) | High (P1) | **REMEDIATED** | Constrained database egress to internal VPC CIDRs (`10.2.0.0/16`) in NetworkPolicies. |
| **H6** | Sequential CI/CD Container Build Bottlenecks | High (P1) | **REMEDIATED** | Parallelized container builds across 11 services in `.github/workflows/ci-cd.yml`. |
| **P0-1** | Downstream Header-Trust (`X-Tenant-ID`) Override | Critical (P0) | **REMEDIATED** | `TenantInterceptor` in all Spring services prioritized JWT claims over HTTP headers. |
| **P1-1** | Outbound REST Client SSRF Vulnerability | High (P1) | **REMEDIATED** | `EgressUrlGuard.java` validates destinations against private/banned CIDRs at call time. |
| **P1-2** | MRZ Extraction `<` Character Checksum Bypass | High (P1) | **REMEDIATED** | Strict weighted ICAO 9303 checksum validation enforced in `extraction/mrz.rs`. |
| **P2-1** | Forensic Check Misrepresentation in Visual Heuristics | Medium (P2) | **MITIGATED** | Re-classified RGB heuristics as visual indicators and capped maximum confidence score. |

---

## 4. Prioritized Remediation Roadmap

The platform engineering and security teams follow a three-phase remediation roadmap:

```
+-----------------------------------------------------------------------------------+
| PHASE 1: Immediate Edge & Identity Boundaries (P0 - Critical)                     |
|  * Enforce JWKS dynamic fetch & RS256 verification at API Gateway.                |
|  * Patch Spring TenantInterceptors to prohibit HTTP header tenant overrides.       |
|  * Execute Flyway V3 PostgreSQL Row-Level Security (RLS) migrations.              |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
| PHASE 2: Infrastructure & Pipeline Hardening (P1 - High)                          |
|  * Correct Terraform AWS regional endpoint interpolations & resource prefixes.    |
|  * Bound Kubernetes NetworkPolicies egress to private VPC CIDR blocks.            |
|  * Transition CI/CD pipeline to parallel Docker build matrices.                   |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
| PHASE 3: Compute Engine Resilience & Quality Assurance (P2 - Medium)              |
|  * Enforce memory & CPU limiters on Rhai DSL scoring engine.                      |
|  * Isolate C/C++ FFI routines in document and face-matching engines.             |
|  * Maintain continuous security scanning & Helm template linting in CI/CD.        |
+-----------------------------------------------------------------------------------+
```

---

## 5. Compliance & Certification Attestation

With the complete implementation of the security controls and architectural remediations detailed in this review, the **USORA KYC Platform** meets the technical security requirement controls for:

* **SOC 2 Type II (Trust Services Criteria):**
  * **CC6.1 (Logical Access Security):** Enforced via RS256 JWT edge validation, JWKS key rotation, and tenant-scoped authorization.
  * **CC6.3 (Perimeter Network Protection):** Enforced via VPC-bounded Kubernetes NetworkPolicies and TLS 1.3 edge termination.
  * **CC6.8 (Software Vulnerability & Release Management):** Guaranteed via automated parallel CI/CD security scanning and Helm linting.
* **GDPR (Article 32 — Security of Processing):**
  * Enforced via AES-256-GCM data encryption at rest and PostgreSQL Row-Level Security (RLS) isolation.
* **ISO/IEC 27001:2022:**
  * **A.8.20 (Network Security & Micro-segmentation):** Restricts inter-service traffic to verified pod selectors.
  * **A.8.24 (Cryptographic Controls):** Eliminates unkeyed hashes, enforces HMAC-SHA256 signatures, and blocks default secret fallbacks.

---

*Report certified by: Jules, Principal Security & Infrastructure Engineer.*
