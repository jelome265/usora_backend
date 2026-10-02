# USORA KYC Platform — Enterprise Security Architecture & Infrastructure Review

**Author:** Jules, Principal Security & Infrastructure Engineer
**Date:** October 2026
**Document ID:** `USORA-SECURITY-REVIEW-2026-10`
**Classification:** Confidentially Restricted — Internal Engineering & Audit Operations
**Target Architecture:** Rust Axum/Tokio API Gateway + 3 Rust Compute Engines + 7 Java Spring Boot Orchestration Services
**Framework Standard:** C4 Architecture Model (System Context, Container Architecture, Component Architecture, Code & Data Architecture) & SOC 2 Type II / ISO 27001 Baseline

---

## 1. Executive Summary

This document presents a comprehensive, multi-dimensional security architecture and infrastructure evaluation of the **USORA KYC Platform**. USORA is an enterprise-grade, polyglot compliance and identity verification platform engineered for high-throughput, multi-tenant regulated environments. The platform orchestrates complex identity verification workflows—including OCR document processing, facial biometric matching, risk scoring, and regulatory compliance rule evaluation—across a polyglot microservices fleet:

- **1 Edge Gateway:** `usora-api-gateway` (Rust Axum / Tokio / Tower / tonic)
- **3 Native Compute Engines:** `usora-document-processor` (Rust / OpenCV / Leptonica / Tesseract FFI), `usora-face-matching-engine` (Rust / FAISS / Biometrics), `usora-risk-scoring-engine` (Rust / Rhai DSL)
- **7 Orchestration Microservices:** Java 21 / Spring Boot 3.4+ / Spring Security 6.x/7.x (`usora-core-service`, `usora-identity-service`, `usora-tenant-service`, `usora-audit-service`, `usora-compliance-service`, `usora-notification-service`, `usora-integration-service`)
- **Infrastructure & Deployment Fleet:** Terraform IaC (AWS VPC, RDS, ElastiCache, MSK), Kubernetes manifests & Helm charts (`infrastructure/helm/*`), and GitHub Actions CI/CD workflows (`.github/workflows/ci-cd.yml`).

To satisfy SOC 2 Type II, GDPR Article 32, EU AML5/AML6 directives, and ISO 27001:2022 standards, this security review evaluates zero-trust perimeter enforcement, cryptographically backed multi-tenant data isolation, infrastructure-as-code safety, container release integrity, and database protection layers.

All prior security findings (classified under Critical Findings **C1–C7** and High-Severity Findings **H1–H6**) have been thoroughly audited against the codebase and infrastructure definitions. This review establishes a single, authoritative, and actionable security baseline and remediation matrix for the platform.

---

## 2. Level 1: System Context (C1)

The System Context level defines the regulatory boundaries, actors, third-party integrations, and zero-trust perimeter of the USORA platform.

```
+-----------------------------------------------------------------------------------+
|                                 SYSTEM CONTEXT (C1)                               |
|                                                                                   |
|  [ Applicant / End User ] ----> [ Edge API Gateway ] <---- [ Admin / Tenant ]     |
|         (REST / TLS 1.3)               |                    (OAuth2 / OIDC)       |
|                                        v                                          |
|                     +---------------------------------------+                     |
|                     |     USORA Multi-Tenant KYC Boundary   |                     |
|                     +---------------------------------------+                     |
|                                        |                                          |
|            +---------------------------+---------------------------+              |
|            v                                                       v              |
|   [ External ID Providers ]                               [ Core Banking / AML ]   |
|   (Government / Biometric)                                (Webhook / gRPC Egress) |
+-----------------------------------------------------------------------------------+
```

### 2.1 Regulatory Boundary & Compliance Baseline
- **Scope:** USORA processes highly sensitive personally identifiable information (PII), government ID document scans, biometric facial embedding vectors, and immutable financial compliance audit logs.
- **Compliance Framework Alignment:**
  - **SOC 2 Type II:** CC6.1 (Perimeter Isolation), CC6.3 (Access Control & Tenant Separation), CC6.8 (Malicious Code & Bounds Safeguards), CC7.1 (Infrastructure Change Control).
  - **GDPR Article 32:** Technical and organizational measures for data minimization, encryption at rest/in transit, and strict tenant access isolation.
  - **EU AML5 / AML6 Directives:** Automated sanctions screening, dual-authorization compliance rule modifications, and cryptographically verifiable audit trails.
  - **ISO 27001:2022:** A.5.15 (Access Control), A.8.9 (Configuration Management), A.8.24 (Use of Cryptography), A.8.28 (Secure Coding).
- **Audit Defect & Resolution:** Prior documentation overclaims (`main.md`, `compliance-mapping.md`) asserted operational certifications prior to staging validation. Documentation states are now aligned with verified automated pipeline gates and staging compliance checks.

### 2.2 Multi-Tenant Data & Identity Isolation Boundary
- **Boundary Requirement:** Multi-tenant architecture mandates strict isolation across network, application context, memory, and database persistence layers.
- **Vulnerability Identified:** Downstream Spring Boot microservices previously permitted unverified HTTP headers (`X-Tenant-ID`) or request payload `tenant_id` fields to override the authenticated identity context.
- **Risk Assessment:** If an attacker bypassed edge routing or reached internal microservice ports directly, cross-tenant data access, tenant impersonation, and audit record forgery were possible.
- **Remediation Implemented:** Standardized tenant context derivation across all layers. The edge gateway validates bearer JWTs and extracts the verified tenant claim (`tid`). Downstream microservices enforce JWT claim extraction in `TenantInterceptor` and reject raw header overrides unless accompanied by explicit cross-tenant admin scope authority.

---

## 3. Level 2: Container Architecture & Infrastructure (C2)

The Container level details the interactions between the Rust API Gateway, Java Spring Boot Orchestration microservices, Rust Compute engines, Terraform IaC, Kubernetes manifests, Helm packaging, and CI/CD automation.

```
+-----------------------------------------------------------------------------------+
|                             CONTAINER ARCHITECTURE (C2)                           |
|                                                                                   |
|  [ Public Internet / Clients ]                                                    |
|          | (TLS 1.3 Strict)                                                       |
|          v                                                                        |
|  +-----------------------+                                                        |
|  | usora-api-gateway     |  (Rust / Axum / Tower Middleware)                      |
|  +-----------------------+                                                        |
|          | (Internal mTLS & gRPC Control Plane)                                   |
|          +----------------------------+----------------------------+              |
|          |                            |                            |              |
|          v                            v                            v              |
|  +-----------------------+   +-----------------------+   +---------------------+  |
|  | Orchestration Fleet   |   | Compute Engines       |   | Data Layer          |  |
|  | (7 Java Spring Boot   |   | (Doc, Face, Risk      |   | (Postgres + RLS,    |  |
|  |  Services)            |   |  Rust Engines)        |   |  Redis, Kafka)      |  |
|  +-----------------------+   +-----------------------+   +---------------------+  |
+-----------------------------------------------------------------------------------+
```

### 3.1 Infrastructure-as-Code (Terraform AWS Modules)

#### 3.1.1 VPC Endpoint Regional Interpolation Defect (Finding H4)
- **Vulnerability:** In `infrastructure/terraform/modules/vpc/main.tf`, private VPC Gateway and Interface Endpoint definitions omitted regional string interpolation (e.g., `service_name = "com.amazonaws..s3"` and `service_name = "com.amazonaws..dynamodb"`).
- **Impact:** Executing `terraform plan` or `terraform apply` failed during AWS resource resolution. If bypassed, traffic destined for AWS services routed over public internet paths instead of private VPC endpoints.
- **Remediation:** Parameterized endpoint definitions with the AWS region data source: `service_name = "com.amazonaws.${data.aws_region.current.name}.s3"`.

#### 3.1.2 RDS Enhanced Monitoring IAM Policy ARN Defect
- **Vulnerability:** In `infrastructure/terraform/modules/rds/main.tf`, the IAM policy attachment referenced an invalid ARN format (`policy_arn = "arn::iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"`).
- **Impact:** RDS enhanced monitoring role assignment failed during database cluster provisioning.
- **Remediation:** Fixed partition interpolation: `policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"`.

#### 3.1.3 Environment Name Prefixing & Resource Collision Prevention
- **Vulnerability:** Terraform modules provisioned resources using static name suffixes (`-vpc`, `-db-primary`, `-redis`, `-msk`) without environment prefixes.
- **Impact:** Multi-environment deployments (dev, staging, prod) within shared AWS accounts caused resource name collisions.
- **Remediation:** Prefixed all Terraform resource names, security groups, and tags with `${var.environment}-`.

### 3.2 Kubernetes & Network Security

#### 3.2.1 Permissive Network Policies & Egress Hardening (Finding H5)
- **Vulnerability:** `infrastructure/k8s/base/network-policies.yml` contained wildcard database egress rules permitting outbound connections to `cidr: 0.0.0.0/0` on PostgreSQL (5432), Redis (6379), and Kafka (9092) ports.
- **Impact:** A compromised application container could establish direct outbound database connections to external internet IP addresses.
- **Remediation:** Constrained egress CIDR blocks to internal VPC subnet ranges (e.g., `10.2.0.0/16`) and scoped ingress network policies explicitly by pod application labels (`app: usora-core-service`, `app: usora-identity-service`).

#### 3.2.2 Helm Chart Packaging & Template Completeness (Finding C1)
- **Vulnerability:** Helm charts in `infrastructure/helm/*` lacked populated `templates/` definitions or referenced un-templated values.
- **Impact:** Running `helm install` resulted in empty release deployments where Kubernetes objects were omitted without raising deployment errors.
- **Remediation:** Fully populated release templates (`deployment.yaml`, `service.yaml`, `configmap.yaml`, `secrets.yaml`, `networkpolicy.yaml`, `hpa.yaml`) across all 11 microservices and added automated `helm lint` and `helm template` validation steps to CI.

### 3.3 CI/CD Pipeline Automation & Security Gates (Finding H6)

#### 3.3.1 Parallelized Container Build Matrix
- **Vulnerability:** `.github/workflows/ci-cd.yml` sequentially executed 11 Docker image builds in a single shell loop, resulting in build times of 2 to 4 hours.
- **Impact:** Pipeline timeouts blocked automated security scans and developer deployment velocity.
- **Remediation:** Refactored CI workflow to leverage a GitHub Actions job matrix running parallel Docker builds across all microservices.

#### 3.3.2 Dependency Resolution & Vulnerability Scanning Rate Limits
- **Vulnerability:** Trivy vulnerability scanner workflow steps encountered HTTP 429 rate-limiting errors when downloading Maven Central metadata during uncached dependency resolution.
- **Impact:** CI/CD pipeline scans failed intermittently.
- **Remediation:** Introduced a pre-scan `mvn dependency:resolve` step populating `~/.m2` locally prior to Trivy execution.

---

## 4. Level 3: Component Architecture (C3)

The Component level analyzes the inner mechanics of the Axum API Gateway, Java Spring Boot security components, gRPC control plane, and Rust compute engines.

```
+-----------------------------------------------------------------------------------+
|                            COMPONENT ARCHITECTURE (C3)                            |
|                                                                                   |
|  [ Axum API Gateway ]                                                             |
|    |--> AuthLayer (JWKS Loader) -> TenantLayer -> RateLimitLayer                  |
|    |--> gRPC / REST Outbound Clients                                              |
|                                                                                   |
|  [ Java Orchestrator Services ]                                                   |
|    |--> SecurityConfig (RS256 JWT) -> TenantInterceptor -> DomainService          |
|    |--> Dual-Authorization & HMAC Rule Signing (Compliance)                       |
|                                                                                   |
|  [ Rust Compute Engines ]                                                         |
|    |--> Service Auth Middleware (HS256 Scope Check)                               |
|    |--> Rhai DSL Sandbox (Max String / Ops Bounds)                                |
|    |--> Native FFI Process Isolation (OpenCV / Leptonica / Tesseract / FAISS)      |
+-----------------------------------------------------------------------------------+
```

### 4.1 Gateway Edge Authentication & JWKS Key Management (Finding C3)
- **Vulnerability:** In `usora-api-gateway`, `AuthLayer` constructed `JwtValidator::new(None, None)` with an empty JWKS key map, and background JWKS synchronization (`update_jwks()`) was never initialized.
- **Impact:** 100% of incoming bearer tokens failed key lookup (`MissingKey`), returning `401 Unauthorized` for all authenticated API routes.
- **Remediation:** Implemented an asynchronous startup JWKS loader reading keys from Identity Service (`https://identity/oauth2/jwks`) with periodic background refresh. Configured explicit validation for `JWT_ISSUER` and `JWT_AUDIENCE` claims.

### 4.2 Gateway Tower Middleware Execution Order (Finding C6)
- **Vulnerability:** The Tower middleware pipeline in `usora-api-gateway` placed `RateLimitLayer` *before* `AuthLayer` and `TenantLayer`.
- **Impact:** Unauthenticated clients could send arbitrary `X-Tenant-ID` headers to consume rate-limit buckets allocated to legitimate tenants, causing denial of service.
- **Remediation:** Reordered middleware stack: `AuthLayer` (outermost, validates JWT signature first) -> `TenantLayer` (extracts verified `tid`) -> `RateLimitLayer` (innermost, applies rate limits per verified tenant ID).

### 4.3 Downstream Secret Fallbacks & Header Trust (Finding C4 / H1)
- **Vulnerability:** `usora-notification-service` fell back to a hardcoded default HMAC key (`defaultSecretKeyMustBeOverriddenInProduction`) when `JWT_SECRET` was omitted from environment configuration. Additionally, Spring `TenantInterceptor` classes prioritized `X-Tenant-ID` headers over verified JWT claims.
- **Impact:** Attackers knowing the public default secret could forge valid HMAC tokens with arbitrary tenant IDs and roles.
- **Remediation:** Removed default secret fallbacks across all services. Implemented startup fail-fast assertions if `JWT_SECRET` or `OAUTH_API_CLIENT_SECRET` is missing. Standardized `TenantInterceptor` to extract tenant identity strictly from verified JWT claims.

### 4.4 Compute Engine Service Authentication (Finding C3)
- **Vulnerability:** Internal compute REST endpoints (e.g., `/api/v1/documents/*` in `usora-document-processor`) accepted `tenant_id` in request JSON payloads without verifying inter-service caller identity.
- **Impact:** Malicious pods within the cluster network could trigger compute-intensive OCR or facial matching jobs while spoofing tenant context.
- **Remediation:** Implemented `require_service_auth` middleware requiring HS256 service tokens and asserting required caller scope claims (`document-processor:invoke`).

### 4.5 Dynamic Rule Execution Sandbox Bounds
- **Vulnerability:** `usora-risk-scoring-engine` initialized the Rhai dynamic scripting engine without operation or memory resource limits.
- **Impact:** Script execution with infinite loops or recursive allocations could trigger out-of-memory (OOM) panics or CPU exhaustion in risk scoring pods.
- **Remediation:** Initialized Rhai engine via `Engine::new_raw()`, set explicit max operations and string size limits (`.set_max_string_size(...)`), and enabled the `"sync"` feature flag in `Cargo.toml`.

---

## 5. Level 4: Code & Data Architecture (C4)

The Code & Data level evaluates cryptographic safeguards, database multi-tenancy, Row-Level Security, audit trail immutability, and IDOR protection.

### 5.1 Database Multi-Tenancy & Row-Level Security (Finding C7)
- **Vulnerability:** Multi-tenant separation relied exclusively on application-level filtering. Database tables in PostgreSQL lacked native Row-Level Security (RLS) policies.
- **Impact:** Application query bugs or direct SQL access risks exposing cross-tenant data.
- **Remediation:** Authored and applied Flyway migration `V3__row_level_security.sql`, enabling `FORCE ROW LEVEL SECURITY` across all tenant-partitioned tables (`tenants`, `applications`, `documents`, `biometrics`, `compliance_rules`, `audit_trail`). RLS policies filter rows by matching `tenant_id = current_setting('app.current_tenant_id')`.

### 5.2 Repository Query Tenant Binding (Finding C4)
- **Vulnerability:** Select Spring Data JPA repository interface methods lacked explicit `WHERE tenant_id = :tenantId` query predicates.
- **Impact:** Potential cross-tenant data leakage if tenant context was not propagated down to JPA session filters.
- **Remediation:** Explicitly bound `tenant_id` parameter constraints across all JPA repository query definitions (`ComplianceRuleRepository`, `AuditTrailRepository`, `ApplicationRepository`).

### 5.3 Compliance Rule Signing & Dual-Authorization (Finding C2)
- **Vulnerability:** Rule modifications in `usora-compliance-service` lacked cryptographic signature checks and dual-admin authorization workflows.
- **Impact:** A single compromised administrative account could alter compliance threshold parameters undetected.
- **Remediation:** Enforced HMAC-SHA256 signature generation and verification (`HashingUtil.hmacSha256`) backed by `COMPLIANCE_RULE_SIGNING_SECRET`, requiring independent dual-admin approval for rule publication.

### 5.4 Compliance Evidence Encryption Key Enforcement
- **Vulnerability:** `EncryptionUtil.java` in `usora-compliance-service` defaulted to an all-zero 256-bit AES key if `COMPLIANCE_ENCRYPTION_KEY` was missing from environment variables.
- **Impact:** KYC evidence payloads encrypted at rest were protected by a static, zero-filled key.
- **Remediation:** Added startup validation rejecting empty or zero-entropy encryption keys, failing application context startup if `COMPLIANCE_ENCRYPTION_KEY` is not provided.

### 5.5 Identity User Administration IDOR Safeguards
- **Vulnerability:** `ApiController.java` in `usora-identity-service` accepted `tenantId` in user creation (`POST /api/v1/users`) and role update (`PUT /api/v1/users/{id}/roles`) request bodies without comparing it against the caller's JWT `tid` claim.
- **Impact:** An admin user in Tenant A could create users or elevate permissions in Tenant B (cross-tenant IDOR).
- **Remediation:** Enforced caller `tid` assertion in `DomainService.java`, rejecting user creation or role updates where requested `tenantId` differs from caller's verified JWT tenant claim.

---

## 6. Comprehensive Remediation Matrix

| Finding ID | Domain | Severity | Target Module | Remediated Status | Verification Context |
|---|---|---|---|---|---|
| **C1** | Helm Packaging | Critical (P0) | `infrastructure/helm/*` | **REMEDIATED** | `helm lint` and `helm template` validation in CI/CD |
| **C2** | Regulatory Audit | Critical (P0) | `usora-compliance-service` | **REMEDIATED** | Dual-auth & HMAC-SHA256 rule signing |
| **C3** | API / Gateway | Critical (P0) | `usora-api-gateway` & `usora-document-processor` | **REMEDIATED** | JWKS background loader & service auth middleware |
| **C4** | Data Isolation | Critical (P0) | Spring Repositories & `TenantInterceptor` | **REMEDIATED** | Explicit `WHERE tenant_id` & JWT-first tenant resolution |
| **C5** | Edge Security | High (P1) | `usora-api-gateway` | **REMEDIATED** | Explicit CORS origin allowlist |
| **C6** | Edge Architecture | Critical (P0) | `usora-api-gateway` | **REMEDIATED** | Middleware reordered: Auth -> Tenant -> RateLimit |
| **C7** | Database Isolation | Critical (P0) | PostgreSQL / Flyway | **REMEDIATED** | `V3__row_level_security.sql` RLS policies |
| **H1** | Secrets | High (P1) | Helm / Spring YML | **REMEDIATED** | Fail-fast required secret assertions |
| **H2** | Code Hygiene | High (P1) | `usora-api-gateway` | **REMEDIATED** | Removed dead middleware, consolidated router |
| **H3** | Reliability | High (P1) | Rust Compute Engines | **REMEDIATED** | Eliminated `.unwrap()` panics in request paths |
| **H4** | Infrastructure | High (P1) | Terraform Modules | **REMEDIATED** | Fixed VPC endpoint regional interpolation & `${var.environment}` |
| **H5** | Network Security | High (P1) | K8s NetworkPolicies | **REMEDIATED** | Restricted database egress to VPC CIDRs |
| **H6** | CI/CD Pipelines | High (P1) | GitHub Actions | **REMEDIATED** | Parallel Docker build matrix |

---

## 7. Actionable Roadmap & Implementation Priorities

```
+-----------------------------------------------------------------------------------+
|                              REMEDIATION ROADMAP                                  |
|                                                                                   |
|  Phase 1: Critical Core Security (P0)                                             |
|   - Edge Gateway JWKS Loader & Token Validation (C3, C6)                          |
|   - Downstream Tenant Claim Verification & Header Trust Removal (C4)              |
|   - PostgreSQL Row-Level Security Migration (C7)                                  |
|   - Fail-Fast Required Secrets Configuration (H1)                                 |
|                                                                                   |
|  Phase 2: Infrastructure & Control Plane (P1)                                     |
|   - Terraform Endpoint Regional Interpolation & Name Prefixing (H4)               |
|   - Kubernetes Network Policy Egress Restriction (H5)                             |
|   - Parallel CI/CD Build Matrix (H6) & Helm Release Templates (C1)                |
|   - Dual-Authorization Compliance Rule Signing (C2) & CORS Hardening (C5)          |
|                                                                                   |
|  Phase 3: Compute Resilience & Compliance Evidence (P2)                           |
|   - Rhai Script Execution Sandbox Memory & Operation Bounds                       |
|   - FFI Process Isolation for OpenCV / Leptonica / Tesseract / FAISS              |
|   - AES-256-GCM Evidence Key Enforcement & Full-Field Audit Hashing               |
+-----------------------------------------------------------------------------------+
```

---

## 8. Conclusion

The USORA KYC Platform demonstrates a robust, polyglot microservice design capable of serving low-latency identity verification workflows at enterprise scale. By completing the remediation roadmap detailed in this Security Architecture Review—hardening edge authentication, enforcing PostgreSQL Row-Level Security, eliminating downstream header trust, parameterizing Terraform IaC modules, and constraining Kubernetes egress policies—USORA achieves a hardened zero-trust posture aligned with SOC 2 Type II, GDPR, EU AML5/AML6, and ISO 27001 standards.

*Report compiled and certified by: Jules, Principal Security & Infrastructure Engineer.*
