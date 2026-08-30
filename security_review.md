# USORA KYC Platform — Consolidated Enterprise Security Architecture & Infrastructure Review

**Author:** Jules, Principal Security & Infrastructure Engineer
**Date:** August 2026
**Classification:** Confidentially Restricted — Internal Engineering Only
**Target Architecture:** Rust Axum/Tokio API Gateway + 3 Rust Compute Engines + 7 Java Spring Boot Orchestration Services
**Framework Standard:** C4 Architecture Model (Context, Containers, Components, Code/Data)

---

## 1. Executive Summary

This document presents a comprehensive, multi-dimensional security, reliability, and infrastructure audit of the **USORA KYC Platform** structured according to the **C4 Architecture Model**. USORA is a high-performance, polyglot compliance and verification platform designed for multi-tenant, regulated enterprise environments. At this scale, maintaining zero-trust architecture, strict tenant isolation, cryptographic assurances, robust network topologies, and clean container packaging is paramount to satisfy SOC 2 Type II, GDPR, EU AML5/AML6, and ISO 27001 compliance standards.

Our static analysis, codebase reviews, and architectural deep-dives have revealed several critical and high-severity gaps across both the **application code** (e.g., total gateway auth outages, forgeable downstream JWTs, tenant-spoofing header trust, unimplemented gRPC control plane) and the **infrastructure-as-code / orchestration layers** (e.g., broken regional endpoint interpolations in Terraform, wide-open database egress policies in Kubernetes, un-compilable Kustomize overlays, and sequential CI/CD workflows).

This consolidated review fuses findings from all prior security audits (`AUDIT-usora-security-2026-08-03.md`, `rust_review.md`, `docs/infrastructure-deep-review-2026-08-04.md`, and `docs/architecture-security-review-2026-07-31.md`) to establish a single, authoritative, and actionable remediation roadmap.

---

## 2. Level 1: System Context (C1)

The System Context level defines the regulatory boundaries, actors, and high-level zero-trust perimeter of the USORA platform.

### 2.1 Regulatory Boundary & Compliance Baseline
- **Context:** The platform processes sensitive personally identifiable information (PII), biometric templates, government issued documents, and financial compliance records. Regulatory targets include SOC 2 Type II, GDPR, EU AML5/AML6, and ISO 27001.
- **Defect:** Documentation overclaims (`main.md`, `compliance-mapping.md`) assert "SOC 2 Type II Certified" and 99.99% SLA metrics before full operational staging validation.
- **Risk:** Regulatory compliance misrepresentation during external enterprise compliance audits.

### 2.2 Multi-Tenant Data & Identity Isolation Model
- **Context:** USORA mandates strict tenant separation at rest and in transit.
- **Defect:** Header-trust fallback (`X-Tenant-ID`) in Spring microservices invalidates edge tenant isolation boundaries.
- **Risk:** Cross-tenant access and attribution falsification if internal backend services are reached directly.

---

## 3. Level 2: Container Architecture & Infrastructure (C2)

The Container level details the interactions between the Rust API Gateway, Java Spring Boot Orchestration microservices, Rust Compute engines, Terraform IaC, K8s manifests, and CI/CD pipelines.

### 3.1 Infrastructure-as-Code (Terraform Modules)

#### 3.1.1 Broken Regional String Interpolations (VPC Endpoint Failure)
- **Vulnerability:** In `infrastructure/terraform/modules/vpc/main.tf` (lines 239–303), AWS service names for private VPC Gateway/Interface Endpoints miss regional interpolation:
  - `service_name = "com.amazonaws..s3"`
  - `service_name = "com.amazonaws..dynamodb"`
  - `service_name = "com.amazonaws..ecr.api"`
  - `service_name = "com.amazonaws..ecr.dkr"`
  - `service_name = "com.amazonaws..eks"`
- **Impact:** Terraform execution fails. If bypassed manually, traffic to S3/ECR/DynamoDB routes over the public internet instead of private VPC endpoints.
- **Remediation:** Parameterize with region data source: `service_name = "com.amazonaws.${data.aws_region.current.name}.s3"`.

#### 3.1.2 Broken IAM Policy ARN in RDS Module
- **Vulnerability:** In `infrastructure/terraform/modules/rds/main.tf` (line 286), IAM policy attachment uses invalid ARN format:
  `policy_arn = "arn::iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"`
- **Impact:** RDS enhanced monitoring provisioning fails during `terraform apply`.
- **Remediation:** Fix partition reference: `policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"`.

#### 3.1.3 Syntactic Defects in MSK Module
- **Vulnerability:** In `infrastructure/terraform/modules/msk/main.tf` (lines 270 & 278):
  - `domain_name = "msk..usora.internal"` (empty subdomain segment)
  - `name = "/aws/msk//broker-logs"` (double slash in log group)
- **Impact:** Internal DNS resolution failure and CloudWatch log ingestion regex breakage.
- **Remediation:** Cleanly interpolate `${var.environment}` into DNS and CloudWatch log paths.

#### 3.1.4 Resource Naming Collisions
- **Vulnerability:** Modules provision resources using static suffixes (e.g., `-vpc`, `-db-primary`, `-redis`, `-msk`) omitting `${var.environment}` prefixes.
- **Impact:** Resource name collisions across dev/staging/prod environments within the same AWS account.
- **Remediation:** Prefix all Terraform resource names with `${var.environment}-`.

### 3.2 Kubernetes & Network Security

#### 3.2.1 Permissive Network Policies (Egress Loophole)
- **Vulnerability:** In `infrastructure/k8s/base/network-policies.yml`, egress database port rules (`5432`/`6379`) specify `cidr: 0.0.0.0/0`. Inter-service rules use wildcard component matching.
- **Impact:** Compromised service pods can exfiltrate database contents directly to public internet IPs.
- **Remediation:** Restrict inter-service connection flows using exact microservice pod selectors and constrain database egress specifically to internal VPC CIDRs (e.g. `10.2.0.0/16`).

#### 3.2.2 Broken Kustomize Dev Overlay
- **Vulnerability:** `infrastructure/k8s/overlays/dev/kustomization.yml` patches deployment resources absent from `../../base`.
- **Impact:** `kustomize build` fails during deployment.
- **Remediation:** Consolidate base deployment manifests or use unified Helm chart templates.

### 3.3 Software Packaging & CI/CD Pipelines

#### 3.3.1 Rust Dockerfile Target Mismatch
- **Vulnerability:** `infrastructure/docker/Dockerfile.rust` copies non-existent binary target `/app/target/release/usora-service`.
- **Impact:** Cargo Docker builds fail.
- **Remediation:** Scope Dockerfile to build specific workspace binaries (e.g., `usora-api-gateway`).

#### 3.3.2 Spring Boot Un-scoped Build Context
- **Vulnerability:** `infrastructure/docker/Dockerfile.spring-boot` copies full root context without scoping to target microservices.
- **Impact:** Bloated images and slow build cycles.
- **Remediation:** Use Maven `-pl` flags to scope dependencies per microservice.

#### 3.3.3 Sequential CI/CD Execution
- **Vulnerability:** `.github/workflows/ci-cd.yml` builds 11 container images sequentially in a loop and uses `continue-on-error: true` on Trivy scans.
- **Impact:** Extremely long build pipelines (2-4 hours) and silent pass-through of critical container CVEs.
- **Remediation:** Parallelize container builds with matrix strategy and enforce fail-fast security scanning gates.

---

## 4. Level 3: Component Architecture (C3)

The Component level analyzes the inner mechanics of the API Gateway, Spring Boot security components, gRPC control plane, and compute execution engines.

### 4.1 Gateway Authentication & Key Management (Dead Auth Layer)
- **Vulnerability:** In `rust-services/usora-api-gateway`, `AuthLayer` initializes `JwtValidator::new(None, None)` with an empty JWKS map (`auth/jwt.rs`), and `update_jwks()` is never invoked.
- **Impact:** 100% of bearer tokens fail key lookup (`MissingKey`), returning `401 Unauthorized` for all authenticated endpoints. Total edge service outage. If JWKS is populated without setting `issuer`/`audience`, authentication bypass occurs for tokens from unintended issuers.
- **Remediation:** Fetch JWKS from Identity Service (`/oauth2/jwks`) on startup and periodic refresh; enforce explicit `JWT_ISSUER` and `JWT_AUDIENCE` validation.

### 4.2 Downstream JWT Forgery via Default HMAC Key
- **Vulnerability:** `usora-notification-service` uses HMAC-SHA symmetric keys defaulting to committed string `defaultSecretKeyMustBeOverriddenInProduction` in `application.yml` and `JwtTokenProvider.java`. `validateToken()` ignores expiration and claim validation.
- **Impact:** Attackers can forge JWTs with arbitrary tenant IDs and roles, bypassing REST/gRPC authentication.
- **Remediation:** Remove default secret fallback, require environment key injection with startup fail-fast check, and align on RS256 token verification delegated from Identity Service.

### 4.3 Downstream Tenant Header Overrides (`X-Tenant-ID` Trust)
- **Vulnerability:** `TenantInterceptor.java` across Spring services (`audit`, `core`, `identity`, `notification`, `compliance`, `integration`) reads `X-Tenant-ID` HTTP header and overrides authenticated JWT context.
- **Impact:** Attacker reaching Spring services directly can spoof tenant identity. In `usora-audit-service`, this corrupts immutable audit trail attribution.
- **Remediation:** Enforce tenant context extraction strictly from validated JWT claims first. Disallow header overrides unless caller possesses explicit super-admin permissions.

### 4.4 Unimplemented gRPC Control Plane
- **Vulnerability:** Gateway defines gRPC clients for `identity`, `tenant`, `audit`, `compliance`, and `notification`. Spring Boot services configure gRPC ports but implement zero `@GrpcService` or generated protobuf `*ImplBase` handlers.
- **Impact:** Gateway gRPC control plane calls fail with `UNIMPLEMENTED`, breaking backend orchestration workflows.
- **Remediation:** Implement missing `@GrpcService` handlers in Spring Boot backends extending protobuf base classes, or route control traffic via authenticated internal REST endpoints.

### 4.5 Plaintext gRPC & Edge TLS Settings
- **Vulnerability:** Gateway gRPC clients connect to `https://orchestrator:9090` without configuring `.tls_config()`, defaulting to unencrypted plaintext channels. Gateway TLS defaults to TLS 1.2 with client auth disabled.
- **Impact:** Lack of mTLS exposes internal gRPC control traffic to eavesdropping and tampering.
- **Remediation:** Configure Tonic gRPC client TLS with CA certs and mutual TLS credentials. Enforce default TLS 1.3 on edge gateway.

### 4.6 Dynamic Code Execution in Compute Engine (Rhai Scripting)
- **Vulnerability:** `usora-risk-scoring-engine` initializes Rhai engine via `Engine::new()` without execution memory/instruction bounds.
- **Impact:** Complex or malicious scripts can trigger CPU loops or OOM panics in compute pods.
- **Remediation:** Initialize Rhai with `Engine::new_raw()`, enforce maximum string size, step limits, and ensure `"sync"` feature flag is enabled in `Cargo.toml`.

### 4.7 Native FFI Failsafe Boundaries
- **Vulnerability:** `usora-document-processor` and `usora-face-matching-engine` call native C/C++ libraries (Leptonica, Tesseract, OpenCV, FAISS) via direct FFI.
- **Impact:** FFI segmentation faults or native memory crashes terminate the host Rust OS process.
- **Remediation:** Isolate raw FFI operations into dedicated worker processes with IPC boundaries and validate image inputs prior to native FFI invocation.

---

## 5. Level 4: Code & Data Architecture (C4)

The Code & Data level evaluates cryptographic implementations, data isolation at rest, audit chain hashing, and IDOR protections.

### 5.1 Symmetric Key Fallback in Compliance Encryption
- **Vulnerability:** `EncryptionUtil.java` in `usora-compliance-service` falls back to an all-zero 256-bit AES key (`new byte[32]`) if `COMPLIANCE_ENCRYPTION_KEY` is not set.
- **Impact:** Sensitive compliance evidence uploaded by applicants is encrypted at rest using a publicly known static zero key.
- **Remediation:** Throw a runtime exception at application startup if `COMPLIANCE_ENCRYPTION_KEY` is missing or insufficient in entropy.

### 5.2 Identity User Administration IDOR Hazard
- **Vulnerability:** `ApiController.java` in `usora-identity-service` accepts `tenantId` in JSON payload bodies for user creation (`POST /api/v1/users`) and role modification (`PUT /api/v1/users/{id}/roles`) without verifying against caller's JWT tenant claim.
- **Impact:** Tenant admin can manipulate users or escalate permissions across different tenants by altering payload `tenantId`.
- **Remediation:** Validate body-supplied `tenantId` against caller's verified JWT `tid` claim in `DomainService.java`.

### 5.3 Forensic Check Misrepresentation
- **Vulnerability:** `authenticity.rs` in `usora-document-processor` contains functions `detect_uv_fluorescence` and `detect_ir_absorption` implemented using basic RGB color-variance heuristics.
- **Impact:** Misleads auditors and API clients into assuming physical UV/IR hardware spectrum validation.
- **Remediation:** Rename functions to `heuristic_visible_*` and cap maximum confidence output weight.

---

## 6. Status of Prior Audit Findings

| Prior Finding | Original Severity | Current Status | Verification Context (Evidence) |
|---|---|---|---|
| **SSRF in Outbound REST Client** | P2 | **RESOLVED** | `integration/.../RestClient.java` calls `EgressUrlGuard.assertSafeDestination()` checking RFC1918, loopback, and link-local ranges. |
| **MRZ Checksum `<` Filler Bypass** | P1 | **RESOLVED** | `document-processor/.../mrz.rs` implements weighted ICAO 9303 checksum and handles `<` edge cases. |
| **JWT Cache Expiry Bypass** | P1 | **RESOLVED** | `api-gateway/.../jwt.rs` checks `claims.exp > now` on LRU cache hits, evicting expired entries. |
| **Silent AML Screening Failures** | P0 | **RESOLVED** | `compliance/.../DomainService.java` collects screening matches in violations and fails-closed on system/gRPC errors. |
| **Unkeyed Dual-Auth Hash** | P1 | **RESOLVED** | `compliance/.../DomainService.java` uses `HashingUtil.hmacSha256` backed by secure rule signing secret. |
| **Documentation Overclaims** | P3 | **OPEN** | `main.md` and `compliance-mapping.md` claim SOC 2 Type II Certified and 99.99% SLA prior to operational audit verification. |

---

## 7. Remediation Roadmap & Prioritization Backlog

| Phase | Priority | Security Domain | Target Module | Remediation Action |
|---|---|---|---|---|
| **Phase 1** | **P0 - Critical** | Identity & Access Control | `api-gateway` | Load JWKS at gateway boot; enforce strict RS256 issuer/audience validation. |
| **Phase 1** | **P0 - Critical** | Data Isolation | Spring Services | Patch `TenantInterceptor` files to prioritize JWT claims over `X-Tenant-ID` headers. |
| **Phase 1** | **P0 - Critical** | Infrastructure | All TF Modules | Fix VPC endpoint interpolation (`com.amazonaws.${data.aws_region.current.name}.s3`) and prepend `${var.environment}` prefixes. |
| **Phase 1** | **P0 - Critical** | Secrets Management | `notification-service` | Remove default HMAC secret key. Implement startup fail-fast check if `JWT_SECRET` is missing. |
| **Phase 1** | **P0 - Critical** | Network Security | Kubernetes | Harden Network Policies to restrict database egress to private VPC CIDRs. |
| **Phase 2** | **P1 - High** | Control Plane | Gateway & Spring | Implement missing `@GrpcService` backend servers; configure mutual TLS (mTLS) over gRPC channels. |
| **Phase 2** | **P1 - High** | Cryptography | `compliance-service` | Remove zero-key fallback in `EncryptionUtil`. Correct invalid monitored IAM policy ARN in RDS. |
| **Phase 2** | **P1 - High** | Packaging / CI | Docker & GitHub | Fix Rust binary target path, scope Spring Boot builds, parallelize container builds in CI/CD. |
| **Phase 3** | **P2 - Medium** | Forensic Validation | `document-processor` | Re-classify and rename RGB heuristic "forensics" to visual heuristics. |
| **Phase 3** | **P2 - Medium** | Compute Resilience | Compute Engines | Isolate FFI C-libraries into dedicated worker processes; enforce Rhai sandbox bounds. |

---

## 8. Conclusion

The USORA platform possesses a highly scalable, high-performance polyglot architecture. However, several critical gaps must be closed before the platform can be considered secure and compliant. Remediating the gateway authentication outage, removing downstream header-trust overlaps, correcting regional/naming defects in the Terraform blueprints, and hardening network egress paths are absolute prerequisites for production readiness.

Implementing the Phase 1 remediation items outlined in this consolidated report will immediately elevate the platform's security posture to satisfy rigorous compliance audits and secure tenant boundaries.

*Report compiled by: Jules, Principal Security & Infrastructure Engineer.*
