# USORA KYC Platform — Consolidated Enterprise Security Architecture & Vulnerability Review

**Author:** Jules, Principal Security & Infrastructure Engineer
**Date:** August 2026
**Classification:** Confidentially Restricted — Internal Engineering Only
**Target Architecture:** Rust Axum/Tokio API Gateway + 3 Rust Compute Engines + 7 Java Spring Boot Orchestration Services

---

## 1. Executive Summary

This document presents a comprehensive, multi-dimensional enterprise security architecture and vulnerability audit of the **USORA KYC Platform**. USORA is a high-performance, polyglot compliance and verification system designed for multi-tenant, regulated enterprise environments. At this scale, maintaining zero-trust architecture, strict tenant isolation, cryptographic assurances, robust network topologies, and clean container packaging is paramount to satisfy SOC 2 Type II, GDPR, EU AML5/AML6, and ISO 27001 compliance standards.

Our static analysis, codebase reviews, and architectural deep-dives have revealed several critical and high-severity gaps across both application code (e.g., edge gateway auth outages, forgeable downstream JWTs, tenant-spoofing header trust, unimplemented gRPC control plane) and infrastructure-as-code / orchestration layers (e.g., broken regional endpoint interpolations in Terraform, wide-open database egress policies in Kubernetes, un-compilable Kustomize overlays, and sequential CI/CD workflows).

This review incorporates all findings from prior security audits (`AUDIT-usora-security-2026-08-03.md`, `rust_review.md`, `docs/infrastructure-deep-review-2026-08-04.md`, `docs/USORA-BACKEND-ENTERPRISE-AUDIT-2026-08-16.md`, and `docs/architecture-security-review-2026-07-31.md`) into a single, authoritative, C4-structured enterprise security model and actionable remediation roadmap.

---

## 2. C4 Architecture Security Model

To systematically evaluate the attack surface, security boundaries, and data flows of the USORA KYC Platform, this review follows the **C4 Architecture Model** across Context, Containers, Components, and Code/Data levels.

```
+-----------------------------------------------------------------------------------+
| C1: CONTEXT LEVEL                                                                |
| External Enterprise Clients / SDKs / Web App ---> [ USORA KYC Platform ]         |
+-----------------------------------------------------------------------------------+
                                                          |
                                                          v
+-----------------------------------------------------------------------------------+
| C2: CONTAINER LEVEL                                                               |
| [ API Gateway (Rust) ] ---> [ Spring Orchestrators ] ---> [ Compute Engines (Rust)] |
|        |                          |                              |                |
|        v                          v                              v                |
| [ Redis Cache ]            [ PostgreSQL DB ]             [ Vector Index / FFI ]   |
+-----------------------------------------------------------------------------------+
                                                          |
                                                          v
+-----------------------------------------------------------------------------------+
| C3: COMPONENT LEVEL                                                               |
| AuthLayer / JwtValidator | DomainService / Interceptors | FFI Wrappers / Rhai DSL |
+-----------------------------------------------------------------------------------+
                                                          |
                                                          v
+-----------------------------------------------------------------------------------+
| C4: CODE & DATA LEVEL                                                             |
| HMAC Keys, RS256 Tokens, AES-256 Storage, Kafka Payload Hashes, SQL Schemas       |
+-----------------------------------------------------------------------------------+
```

### 2.1 Context Level (System Scope & Trust Boundaries)
- **Primary Boundary:** The perimeter separating untrusted external client networks (web clients, partner enterprise systems) from the internal USORA VPC/EKS cluster.
- **Identity & Access Management:** External clients obtain OAuth2 / OIDC Bearer tokens issued by the Identity Service (`usora-identity-service`).
- **Security Posture & Findings:**
  - Public entry points are constrained to the API Gateway (`usora-api-gateway`).
  - Lack of rate limiting on unauthenticated endpoints could permit denial-of-service (DoS) or credential brute-forcing against authentication endpoints.

### 2.2 Container Level (Microservices Fleet & Infrastructure)
- **Services Inventory:**
  - **Edge Gateway:** `usora-api-gateway` (Rust Axum / Tokio / Tower) — Port 8080/9090
  - **Spring Boot Orchestration Services (Java 21 / Spring Boot 3.4.0):**
    - `usora-core-service` (8080 / 9090)
    - `usora-identity-service` (8081 / 50051)
    - `usora-tenant-service` (8082 / 9090)
    - `usora-audit-service` (8083 / 9092)
    - `usora-compliance-service` (8084 / 9090)
    - `usora-notification-service` (8085 / 9095)
    - `usora-integration-service` (8086 / 9095)
  - **Rust Compute Engines:**
    - `usora-document-processor` (Rust / OCR / OpenCV / Leptonica / Tesseract FFI)
    - `usora-face-matching-engine` (Rust / FAISS / Biometric Matching)
    - `usora-risk-scoring-engine` (Rust / Rhai DSL Engine)
- **Infrastructure Infrastructure-as-Code (Terraform & Kubernetes):**
  - AWS VPC, EKS, RDS PostgreSQL, ElastiCache Redis, MSK Kafka.
- **Security Posture & Findings:**
  - **Terraform Endpoint Regional Failures:** `service_name = "com.amazonaws..s3"` missing `${data.aws_region.current.name}`, causing endpoint creation failures and public internet fallback.
  - **Permissive Kubernetes Network Policies:** Database egress rules allow `0.0.0.0/0` on port 5432, enabling potential exfiltration if a container is compromised.
  - **Broken Dev Kustomize Overlay:** Invalid base references break local cluster orchestration.

### 2.3 Component Level (Internal Logic & Inter-Service Communications)
- **Gateway Auth Component:** `AuthLayer` & `JwtValidator` in `usora-api-gateway`.
  - *Finding:* Constructs validator with `None` issuer/audience and an empty JWKS map without invoking `update_jwks()`, causing 100% auth failure for Bearer tokens.
- **Tenant Interceptor Components:** `TenantInterceptor.java` across Spring Boot services.
  - *Finding:* Overrides JWT authenticated tenant context with `X-Tenant-ID` HTTP header, allowing cross-tenant spoofing if internal edge controls are bypassed.
- **Control Plane gRPC Channels:** Gateway to Spring Boot gRPC interfaces.
  - *Finding:* Gateway calls unimplemented `@GrpcService` handlers over unencrypted plaintext gRPC channels without TLS configuration.
- **Compute Engine FFI & Scripting Components:**
  - `usora-document-processor` & `usora-face-matching-engine`: Unwrapped FFI calls to Leptonica/Tesseract/OpenCV/FAISS can crash OS process on malformed inputs.
  - `usora-risk-scoring-engine`: Rhai DSL engine instantiated with `Engine::new()` lacking CPU step limits and memory caps.

### 2.4 Code & Data Level (Cryptographic & Data Integrity)
- **Notification Service Secret:** Uses committed default HMAC key `defaultSecretKeyMustBeOverriddenInProduction` without enforcing fail-fast startup check.
- **Compliance Encryption Key:** `EncryptionUtil` in `usora-compliance-service` defaults to zeroed-out key (`new byte[32]`) when environment variable is missing.
- **Identity Service IDOR Hazard:** User and role management endpoints in `ApiController.java` accept tenant ID in payload without asserting caller ownership.
- **Forensic Check Misrepresentation:** RGB color heuristics in `authenticity.rs` labeled as UV/IR forensic verification.

---

## 3. Detailed Technical Vulnerability Analysis

### 3.1 Gateway Authentication Outage & OIDC Validation (P0 - Critical)
- **Vulnerability:** In `rust-services/usora-api-gateway`, the `AuthLayer` constructs its `JwtValidator` via `JwtValidator::new(None, None)` (without providing an issuer or audience), and leaves the JWKS map completely empty. The `update_jwks()` function is defined in `auth/jwt.rs` but is never invoked.
- **Vulnerability Impact:** Every incoming request containing an `Authorization: Bearer <token>` header is parsed, but signature verification fails because the internal JWKS key map is empty, resulting in a `401 Unauthorized` response. Consequently, 100% of valid bearer tokens are rejected, putting the entire authenticated KYC surface **100% offline**.
- **Remediation:** Implement a background Tokio task in Axum that queries the Identity Service JWKS endpoint (`https://identity/oauth2/jwks`) on startup and periodically updates the validator keys. Populate the validator with explicit, non-default `JWT_ISSUER` and `JWT_AUDIENCE` configurations.

### 3.2 Downstream Tenant Header Spoofing (P0 - Critical)
- **Vulnerability:** While the Gateway extracts the tenant ID from token claims, downstream Spring Boot orchestration services re-introduce header trust. `TenantInterceptor.java` across the `audit`, `core`, `identity`, `notification`, `compliance`, and `integration` services extract the `X-Tenant-ID` header and unconditionally override the authenticated JWT principal context.
- **Vulnerability Impact:** If an attacker bypasses the gateway edge (e.g. via direct pod routing or internal load balancer access), they can supply a falsified `X-Tenant-ID` header. In `usora-audit-service`, this permits injection of falsified compliance audits, overriding logged `tenantId` and `actor`, completely invalidating audit log integrity.
- **Remediation:** Standardize all Spring `TenantInterceptor` classes to resolve the tenant ID strictly from verified JWT claims first. Disallow HTTP header overrides unless the request is associated with a trusted global super-admin principal.

### 3.3 Infrastructure Regional Interpolations & IAM Policy ARNs (P0 - Critical)
- **Vulnerability:**
  - `infrastructure/terraform/modules/vpc/main.tf`: Regional service names hardcoded with empty regional segment (`com.amazonaws..s3`, `com.amazonaws..dynamodb`, `com.amazonaws..ecr.api`).
  - `infrastructure/terraform/modules/rds/main.tf`: IAM policy attachment uses invalid ARN format (`arn::iam::aws:policy/...`).
  - `infrastructure/terraform/modules/msk/main.tf`: Empty subdomain zone (`msk..usora.internal`) and double slash in CloudWatch log group path (`/aws/msk//broker-logs`).
- **Vulnerability Impact:** Terraform plan/apply fails. If bypassed, S3 and ECR traffic defaults to public internet egress, violating zero-trust networking principles.
- **Remediation:** Interpolate region and partition variables properly:
  ```hcl
  service_name = "com.amazonaws.${data.aws_region.current.name}.s3"
  policy_arn   = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
  ```

### 3.4 Permissive Kubernetes Network Policies (P0 - Critical)
- **Vulnerability:** In `infrastructure/k8s/base/network-policies.yml`, egress rules permit pods with `component: service` or `compute` to connect to `0.0.0.0/0` on PostgreSQL port `5432`.
- **Vulnerability Impact:** In the event of remote code execution on a microservice container, an attacker can exfiltrate database contents directly to arbitrary external IP addresses over standard database ports.
- **Remediation:** Constrain egress destination CIDRs strictly to internal VPC ranges (`10.2.0.0/16`) and specific database subnets.

---

## 4. Status of Prior Audit Findings

| Prior Finding | Original Severity | Current Status | Verification Context (Evidence) |
|---|---|---|---|
| **SSRF in Outbound REST Client** | P2 | **RESOLVED** | `integration/.../RestClient.java` calls `EgressUrlGuard.assertSafeDestination()` which checks against RFC1918, loopback, and link-local ranges at call time. |
| **MRZ Checksum `<` Filler Bypass** | P1 | **RESOLVED** | `document-processor/.../mrz.rs` properly implements the weighted ICAO 9303 checksum and handles `<` edge-cases correctly. |
| **JWT Cache Expiry Bypass** | P1 | **RESOLVED** | `api-gateway/.../jwt.rs` validates `claims.exp > now` on every LRU cache hit, evicting expired entries. |
| **Silent AML Screening Failures** | P0 | **RESOLVED** | `compliance/.../DomainService.java` collects screening matches inside violations and fails-closed on gRPC/system errors. |
| **Unkeyed Dual-Auth Hash** | P1 | **RESOLVED** | `compliance/.../DomainService.java` uses `HashingUtil.hmacSha256` backed by a secure rule signing secret instead of a plain SHA-256 digest. |
| **Documentation Overclaims** | P3 | **OPEN** | `main.md` and `compliance-mapping.md` claim SOC 2 Type II Certified and 99.99% SLA, which exceed present staging validation. |

---

## 5. Prioritized Remediation Roadmap & Action Plan

| Phase | Priority | Domain | Target Module | Remediation Action |
|---|---|---|---|---|
| **Phase 1** | **P0 - Critical** | Identity & Access Control | `api-gateway` | Load JWKS at gateway boot; enforce strict RS256 issuer/audience validation. |
| **Phase 1** | **P0 - Critical** | Data Isolation | Spring Services | Patch `TenantInterceptor` files to prioritize JWT claims. Remove `X-Tenant-ID` header override. |
| **Phase 1** | **P0 - Critical** | Infrastructure | All TF Modules | Correct regional endpoint interpolation (`com.amazonaws..s3`) and prepend `${var.environment}` prefixes. |
| **Phase 1** | **P0 - Critical** | Secrets Management | `notification-service` | Remove default HMAC secret key. Implement fail-fast on startup if `JWT_SECRET` is missing. |
| **Phase 1** | **P0 - Critical** | Network Security | Kubernetes | Harden Network Policies to restrict database egress to private VPC CIDRs. |
| **Phase 2** | **P1 - High** | Control Plane | Gateway & Spring | Implement missing `@GrpcService` backend servers; configure mutual TLS (mTLS) over gRPC channels. |
| **Phase 2** | **P1 - High** | Cryptography | `compliance-service` | Remove zero-key fallback in `EncryptionUtil`. Correct invalid monitored IAM policy ARN in RDS. |
| **Phase 2** | **P1 - High** | Packaging / CI | Docker & GitHub | Fix Rust binary target path, scope Spring Boot builds, parallelize container builds in CI/CD. |
| **Phase 3** | **P2 - Medium** | Forensic Validation | `document-processor` | Re-classify and rename RGB heuristic "forensics" to visual heuristics. |
| **Phase 3** | **P2 - Medium** | Compute Resilience | Compute Engines | Isolate FFI C-libraries into dedicated worker processes; use process-level isolation. |

---

## 6. Conclusion

The USORA platform possesses a highly scalable, high-performance polyglot architecture. Implementing the C4-structured security recommendations and prioritizing Phase 1 critical remediations will ensure robust zero-trust security boundaries, tenant isolation, and strict regulatory compliance across enterprise deployments.

*Report compiled by: Jules, Principal Security & Infrastructure Engineer.*
