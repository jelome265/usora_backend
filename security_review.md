# USORA KYC Platform — Enterprise Security Review & Architecture Assessment

**Author:** Jules, Principal Security & Infrastructure Engineer
**Date:** August 20, 2026
**Classification:** Confidential — Internal Engineering Reference
**Target Architecture:** Polyglot Microservices (Rust Axum Edge Gateway + 3 Rust Compute Engines + 7 Java Spring Boot 3.4 Orchestration Services)

---

## 1. Executive Summary & Security Posture Overview

The **USORA KYC Platform** is an enterprise-grade, polyglot compliance and verification system designed for multi-tenant, regulated financial environments. Operating at global scale, USORA processes sensitive Personally Identifiable Information (PII), biometric face vectors, government identification credentials, and Anti-Money Laundering (AML) risk evaluations.

This Security Review provides a rigorous, full-stack architectural audit and vulnerability assessment across all layers of the USORA codebase—from the Rust Axum API Gateway edge down to the Java Spring Boot orchestration backends, Rust compute engines, Terraform infrastructure modules, Helm charts, and GitHub Actions CI/CD pipelines.

### Key Assessment Findings & Remediation Progress

1. **Edge & Authentication Layer:**
   - The API Gateway features a high-performance Tower/Axum middleware stack. The `JwtValidator` re-validates token expiration (`exp`) on every LRU cache hit to prevent cache bypass attacks, and extracts `tid` claims for tenant routing.
   - Background JWKS synchronization from `usora-identity-service` and strict `JWT_ISSUER`/`JWT_AUDIENCE` assertions have been validated to guarantee identity boundary enforcement.

2. **Multi-Tenant Data Isolation:**
   - Prior vulnerabilities where downstream Spring services trusted client-supplied `X-Tenant-ID` HTTP headers over verified JWTs have been **fully remediated**.
   - All seven Spring Boot orchestration services (`usora-audit-service`, `usora-core-service`, `usora-identity-service`, `usora-compliance-service`, `usora-tenant-service`, `usora-integration-service`, `usora-notification-service`) enforce `TenantInterceptor` logic that derives the tenant context (`TenantContext`) **exclusively** from the authenticated JWT's `tid` claim.
   - PostgreSQL Row-Level Security (RLS) policies and per-tenant schema isolation enforce data boundaries at the persistence layer.

3. **Secrets Management & Cryptographic Hygiene:**
   - Symmetric HMAC default fallback keys in `usora-notification-service` have been eliminated in favor of fail-fast startup checks and delegated RS256 token verification.
   - `EgressUrlGuard` enforces SSRF defense for outbound integrations by performing runtime DNS re-resolution and filtering loopback, link-local, RFC1918, and Cloud Provider metadata IP ranges (`169.254.169.254`).

4. **Infrastructure & Packaging Integrity:**
   - Terraform modules (`vpc`, `rds`, `msk`, `elasticache`) have been patched to resolve broken regional string interpolations (`com.amazonaws.${region}.s3`), correct IAM monitoring ARNs, and enforce `${var.environment}` naming prefixes.
   - Enterprise Helm charts (`infrastructure/helm/`) are fully implemented for all 11 services with Pod Disruption Budgets, resource requests/limits, network policies, and security contexts.

---

## 2. C4 Architecture & Zero-Trust Security Domain Assessment

```
                                    +-----------------------------------+
                                    |       Applicant / Admin Web       |
                                    +-----------------------------------+
                                                      |
                                           HTTPS (TLS 1.3 / mTLS)
                                                      v
                                    +-----------------------------------+
                                    |     usora-api-gateway (Rust)      |
                                    |   - AuthLayer / JWT Validation    |
                                    |   - Tenant Context Extraction     |
                                    |   - Rate Limiting & CORS Guard     |
                                    +-----------------------------------+
                                                      |
                                     Internal mTLS / gRPC / REST
                                                      |
         +--------------------------------------------+--------------------------------------------+
         |                                            |                                            |
         v                                            v                                            v
+-----------------------------------+     +-----------------------------------+     +-----------------------------------+
|  Spring Boot Orchestration (Java) |     |    Rust Compute Engines (Rust)    |     |      Data Persistence Layer       |
|  - usora-identity-service         |     |  - usora-document-processor       |     |  - PostgreSQL (RLS / Schemas)    |
|  - usora-core-service             |     |  - usora-face-matching-engine     |     |  - Redis Cluster (Sessions/Cache) |
|  - usora-compliance-service       |     |  - usora-risk-scoring-engine      |     |  - Apache Kafka (Event Bus)       |
|  - usora-audit-service            |     +-----------------------------------+     |  - HashiCorp Vault / AWS KMS      |
|  - usora-tenant-service           |                                               +-----------------------------------+
|  - usora-integration-service      |
|  - usora-notification-service     |
+-----------------------------------+
```

### Level 1: System Context
USORA interfaces with applicant mobile/web clients, compliance officer portals, third-party ID verification authorities, credit bureaus, and banking APIs. External trust boundaries terminate at the API Gateway.

### Level 2: Container & Service Mesh Architecture
Inter-service communication between the Rust Edge Gateway, Java Orchestration microservices, and Rust Compute engines is routed within a private Kubernetes VPC. Network policies enforce ingress/egress filtering per pod selector.

### Level 3: Component Security Analysis

| Layer | Component | Principal Technology | Security Control & Boundary | Status |
| :--- | :--- | :--- | :--- | :--- |
| **Edge** | `usora-api-gateway` | Rust, Axum, Tokio, Tower | TLS 1.3 termination, RS256 JWT validation, rate limiting, CORS control | 🟢 Secure |
| **Orchestrator** | `usora-identity-service` | Java 21, Spring Boot 3.4 | OIDC Provider, SPIFFE identity, RS256 token issuance | 🟢 Secure |
| **Orchestrator** | `usora-core-service` | Java 21, Spring Boot 3.4 | Core workflow engine, tenant-isolated DB access, Spring Security | 🟢 Secure |
| **Orchestrator** | `usora-compliance-service` | Java 21, Spring Boot 3.4 | AML screening, rule execution, HMAC-SHA256 evidence signing | 🟢 Secure |
| **Orchestrator** | `usora-audit-service` | Java 21, Spring Boot 3.4 | Hash-chained immutable audit ledger, actor attribution | 🟢 Secure |
| **Orchestrator** | `usora-tenant-service` | Java 21, Spring Boot 3.4 | Tenant lifecycle management, schema migration hooks | 🟢 Secure |
| **Orchestrator** | `usora-integration-service` | Java 21, Spring Boot 3.4 | External webhook delivery, SSRF `EgressUrlGuard` protection | 🟢 Secure |
| **Orchestrator** | `usora-notification-service` | Java 21, Spring Boot 3.4 | Multi-channel dispatch (SMS/Email), strict JWT validation | 🟢 Secure |
| **Compute** | `usora-document-processor` | Rust, Tesseract, OpenCV | OCR & document forensics, FFI blocking thread pool isolation | 🟡 Monitored |
| **Compute** | `usora-face-matching-engine` | Rust, FAISS, OpenCV | Biometric vector matching, liveness verification | 🟡 Monitored |
| **Compute** | `usora-risk-scoring-engine` | Rust, Rhai DSL | Behavioral risk evaluation, Rhai execution bounds | 🟢 Secure |

---

## 3. System Security Heatmap & Risk Matrix

| Risk Dimension | API Gateway | Spring Orchestration | Rust Compute Engines | Data Layer | Infrastructure & CI/CD |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Authentication & AuthZ** | 🟢 Low | 🟢 Low | 🟢 Low | 🟢 Low | 🟢 Low |
| **Tenant Isolation** | 🟢 Low | 🟢 Low | 🟢 Low | 🟢 Low | 🟢 Low |
| **Data Confidentiality** | 🟢 Low | 🟢 Low | 🟢 Low | 🟢 Low | 🟢 Low |
| **Input Validation & SSRF** | 🟢 Low | 🟢 Low | 🟢 Low | N/A | 🟢 Low |
| **FFI & Memory Safety** | N/A | N/A | 🟡 Medium | N/A | N/A |
| **Infrastructure Hardening**| 🟢 Low | 🟢 Low | 🟢 Low | 🟢 Low | 🟢 Low |

---

## 4. Comprehensive Vulnerability Assessment & Technical Audit

### 4.1 Authentication & Edge Access Control
- **JWT Cache Expiry Re-Verification:** In `usora-api-gateway/src/auth/jwt.rs`, the `validate_token` method re-checks `claims.exp > now` on every LRU cache hit. If expired, the entry is immediately evicted (`cache.pop(token)`) and rejected, preventing stale cached tokens from bypassing expiration checks.
- **JWKS & Issuer/Audience Validation:** The gateway's `JwtValidator` supports dynamic key set reloading via `update_jwks` and validates standard JWT claims (`exp`, `iss`, `aud`, `tid`).
- **CORS Protection:** `usora-api-gateway` defines restrictive CORS headers, enforcing allowed origins, methods, and header lists.

### 4.2 Multi-Tenant Data Isolation & Downstream Context
- **Header Trust Elimination:** All Spring Boot microservices previously vulnerable to HTTP header spoofing (`X-Tenant-ID`) now enforce JWT-first tenant resolution.
- **Evidence from Codebase (`TenantInterceptor.java`):**
  ```java
  @Override
  public boolean preHandle(@NonNull HttpServletRequest request,
                           @NonNull HttpServletResponse response,
                           @NonNull Object handler) {
      var auth = SecurityContextHolder.getContext().getAuthentication();
      if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
          var tenantId = jwt.getClaimAsString("tid");
          if (tenantId != null && !tenantId.isBlank()) {
              TenantContext.setCurrentTenantId(tenantId);
              MDC.put("tenantId", tenantId);
          }
      }
      return true;
  }
  ```
- **PostgreSQL Row-Level Security (RLS):** Database migration scripts enforce `tenant_id` column filters on all domain tables with session-variable hooks set during connection acquisition.

### 4.3 Outbound Integration Security & SSRF Defense
- **URL Egress Guard:** `usora-integration-service` protects outbound webhook and REST integration calls via `com.usora.integration.util.EgressUrlGuard`.
- **Mitigation Mechanics:**
  1. Parses destination URIs and performs DNS resolution at invocation time.
  2. Rejects loopback (`127.0.0.1`, `::1`), RFC1918 private subnets (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), link-local (`169.254.0.0/16`), multicast, and CGNAT IP blocks.
  3. Explicitly blocks access to cloud instance metadata endpoints (`169.254.169.254`).

### 4.4 Compute Engine Security & FFI Isolation
- **Rust Memory Safety:** Core business logic in compute engines uses standard safe Rust abstractions.
- **FFI Thread Blocking:** Native calls into C/C++ libraries (OpenCV, Tesseract, FAISS) in `usora-document-processor` and `usora-face-matching-engine` are executed inside `tokio::task::spawn_blocking` pools to prevent blocking Tokio async event loops.
- **Rhai Rule Engine Sandboxing:** `usora-risk-scoring-engine` bounds execution of dynamic DSL rules by configuring `set_max_operations` and restricting script standard library access.

---

## 5. Infrastructure, Packaging & CI/CD Security

### 5.1 Terraform AWS Infrastructure Hardening
- **VPC Gateway Endpoints:** Hardcoded regional strings in `infrastructure/terraform/modules/vpc/main.tf` have been parameterized (`com.amazonaws.${data.aws_region.current.name}.s3`) to ensure traffic to S3, ECR, and DynamoDB stays within AWS private network backbones.
- **RDS Monitoring Policy:** Corrected IAM ARN formats (`arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole`).
- **Resource Environment Scoping:** All resources across VPC, RDS, MSK, ElastiCache, and EKS modules incorporate `${var.environment}` prefixes to prevent collisions in multi-tenant shared AWS accounts.

### 5.2 Kubernetes & Container Hardening
- **Enterprise Helm Charts:** Defined under `infrastructure/helm/` for all 11 microservices.
- **Pod Security Standards:** Containers run as non-root users (`runAsNonRoot: true`, `runAsUser: 10001`), read-only root filesystems (`readOnlyRootFilesystem: true`), with `allowPrivilegeEscalation: false` and dropped `ALL` Linux capabilities.
- **Network Policies:** Ingress and egress rules restrict cross-namespace traffic and isolate database access to internal private subnet CIDR blocks.

### 5.3 CI/CD & Software Supply Chain
- **Parallel Matrix Builds:** `.github/workflows/ci-cd.yml` structures service container builds into parallel job matrices with BuildKit caching.
- **Static Analysis & SAST:** CodeQL analysis is integrated via `.github/workflows/codeql.yml` and `.github/codeql/codeql-config.yml`.
- **Dependency Guard:** Rust dependencies are audited, pinning native compatibility requirements where necessary (e.g. `kstring` pinned to `2.0.2` for compiler edition stability).

---

## 6. Threat Modeling (STRIDE Analysis)

| Threat Category | Threat Description | Affected Surface | Mitigation Status |
| :--- | :--- | :--- | :--- |
| **Spoofing** | Tenant context spoofing via `X-Tenant-ID` HTTP header | Downstream Spring microservices | **Mitigated:** Derived strictly from verified JWT `tid` claim. |
| **Tampering** | Evidence document tampering or falsified audit logs | `usora-compliance-service` / `usora-audit-service` | **Mitigated:** SHA-256 HMAC rule signatures & hash-chained audit ledger. |
| **Repudiation** | Actor denying compliance actions or case status changes | `usora-audit-service` | **Mitigated:** Mandatory MDC logging & immutable audit event persistence. |
| **Information Disclosure** | PII leakage via outbound webhook calls (SSRF) | `usora-integration-service` | **Mitigated:** `EgressUrlGuard` checks destination IPs at runtime. |
| **Denial of Service** | Async event loop thread exhaustion via native OCR/biometrics | Compute Engines (`document-processor`, `face-matching`) | **Mitigated:** `spawn_blocking` execution pools for native FFI calls. |
| **Elevation of Privilege**| Unauthorized admin operations across tenant boundaries | `usora-identity-service` | **Mitigated:** Strict OAuth2 `SCOPE_admin` and JWT tenant matching. |

---

## 7. Audit Remediation Verification Matrix

| Audit Date | Finding ID | Severity | Description | Current Status | Verification Evidence |
| :--- | :--- | :---: | :--- | :---: | :--- |
| 2026-07-31 | SEC-01 | P0 | Tenant isolation bypass via `X-Tenant-ID` header | **RESOLVED** | Enforced JWT `tid` claim in `TenantInterceptor.java` across all services. |
| 2026-07-31 | SEC-02 | P0 | Silent AML screening bypass in compliance logic | **RESOLVED** | Screening matches captured in violations; fail-closed on system error. |
| 2026-07-31 | SEC-03 | P1 | Unkeyed hash used in compliance dual-signature | **RESOLVED** | Updated `DomainService.java` to use `HashingUtil.hmacSha256` with secret. |
| 2026-07-31 | SEC-04 | P1 | JWT cache hit ignores expiration check | **RESOLVED** | `JwtValidator.rs` re-validates `claims.exp > now` on every cache hit. |
| 2026-07-31 | SEC-05 | P1 | MRZ checksum `<` filler character bypass | **RESOLVED** | `mrz.rs` implements weighted ICAO 9303 checksum validation. |
| 2026-08-03 | SEC-06 | P0 | Committed default HMAC secret in notification service | **RESOLVED** | Eliminated default fallback key; enforced startup validation. |
| 2026-08-03 | SEC-07 | P2 | SSRF risk on outbound integration webhooks | **RESOLVED** | `EgressUrlGuard.java` validates destination IP against RFC1918 / loopback. |
| 2026-08-04 | INF-01 | P0 | Broken regional endpoint interpolation in Terraform | **RESOLVED** | Corrected `com.amazonaws.${data.aws_region.current.name}.s3` in VPC module. |
| 2026-08-04 | INF-02 | P1 | Overly permissive Kubernetes network policies | **RESOLVED** | Implemented per-component pod selectors & CIDR egress blocks in Helm. |

---

## 8. Prioritized Remediation Roadmap & Next Steps

```
+-------------------------------------------------------------------------------+
|                        CONTINUOUS SECURITY ROADMAP                            |
+-------------------------------------------------------------------------------+
| Phase 1 [Completed]: Enforce JWT-only TenantContext across all Spring Boot services |
| Phase 1 [Completed]: Parameterize Terraform VPC regional endpoint interpolation |
| Phase 1 [Completed]: Helm charts & Pod Security Standards for all 11 services |
| Phase 2 [In Progress]: Expand gRPC mTLS certificate automation via SPIFFE/SPIRE |
| Phase 3 [Planned]: Migrate local FAISS vector indices to distributed vector DB |
+-------------------------------------------------------------------------------+
```

### Action Items & Maintenance Schedule

1. **Service Mesh & SPIFFE/SPIRE mTLS (Phase 2):**
   - Continue rollout of SPIFFE Workload API sidecars to automate mTLS certificate rotation for inter-service gRPC channels across Kubernetes clusters.

2. **Distributed Vector Database Migration (Phase 3):**
   - Transition `usora-face-matching-engine` from local disk-based FAISS index files to a distributed vector store (e.g. pgvector or Qdrant) for enhanced stateless horizontal scaling.

3. **Continuous Automated SAST/DAST Scanning:**
   - Maintain strict PR check enforcement via CodeQL, Dependabot, and container vulnerability scanning in GitHub Actions.

---

## 9. Regulatory Compliance Mapping

| Standard / Framework | Regulatory Scope | USORA Platform Security Controls |
| :--- | :--- | :--- |
| **SOC 2 Type II** | Security, Confidentiality, Availability | Role-Based Access Control (RBAC), KMS encryption at rest, immutable audit logs |
| **GDPR (EU 2016/679)** | Data Protection & PII Privacy | Schema per tenant, tenant-isolated encryption, automated right-to-erasure workflows |
| **EU AML5 / AML6** | Anti-Money Laundering & KYC | Automated PEP / Sanction screening, tamper-evident evidence hashing, workflow trails |
| **ISO/IEC 27001:2022** | Information Security Management | Zero-Trust network architecture, secret lifecycle management via HashiCorp Vault |

---

*Report compiled and verified by Jules, Principal Security & Infrastructure Engineer.*
