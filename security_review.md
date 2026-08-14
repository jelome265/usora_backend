# USORA KYC Platform — Enterprise Security & Infrastructure Review

**Author:** Jules, Principal Security Engineer
**Date:** August 12, 2026
**Classification:** Confidentially Restricted — Internal Engineering Only
**Target Architecture:** Rust Axum/Tokio API Gateway + 3 Rust Compute Engines + 7 Java Spring Boot 4.x Orchestration Services

---

## 1. Executive Summary

This security and infrastructure review provides an exhaustive, multi-dimensional assessment of the **USORA KYC Platform** codebase, deployment blueprints, and access control matrices. USORA is an enterprise-grade multi-tenant compliance and verification system. At this scale, the integrity of tenant isolation, cryptographic assurances, network topology, and deployment isolation are critical to achieving zero-trust and satisfying strict regulatory frameworks (including SOC 2 Type II, GDPR, EU AML5/AML6, and ISO 27001).

Our static and architectural analysis has exposed severe, latent vulnerabilities across both the **application code** and the **infrastructure-as-code (Terraform)** layers. These range from total authentication outages on the gateway and forgeable-by-default JSON Web Tokens in downstream services, to critical region-interpolation bugs and resource-naming collisions in Terraform.

This document provides a highly detailed, grounded map of these issues and establishes a prioritized remediation roadmap.

---

## 2. Infrastructure Security & Terraform Deep Dive

The deployment infrastructure utilizes Terraform to manage AWS VPC, EKS, RDS, ElastiCache, and MSK modules. A thorough review of these modules reveals multiple architectural flaws that break regional isolation, compromise IAM role attachments, and prevent multi-environment (dev/staging/prod) coexistence in the same AWS region due to naming collisions.

### 2.1 Broken Regional String Interpolation & Service Names
In `infrastructure/terraform/modules/vpc/main.tf`, regional endpoints are hardcoded or misconfigured using an empty interpolation string.
- **Evidence (`vpc/main.tf`):**
  - Line 278: `service_name = "com.amazonaws..s3"`
  - Line 293: `service_name = "com.amazonaws..dynamodb"`
  - Line 309: `service_name = "com.amazonaws..ecr.api"`
  - Line 323: `service_name = "com.amazonaws..ecr.dkr"`
  - Line 337: `service_name = "com.amazonaws..eks"`
- **Vulnerability / Impact:** Because the current AWS region is not interpolated (e.g., missing `${data.aws_region.current.name}`), Terraform attempts to provision endpoints with broken service names (e.g. `com.amazonaws..s3`). AWS API rejects these configurations, resulting in immediate deployment failures. If bypassed by manual intervention, traffic destined for S3, ECR, and DynamoDB is routed over the public internet rather than private VPC Gateway/Interface Endpoints, violating Zero-Trust networking principles.
- **Remediation:** Replace the double-dots with correct regional references:
  ```hcl
  service_name = "com.amazonaws.${data.aws_region.current.name}.s3"
  ```

### 2.2 Broken IAM Policy ARN in RDS Module
In `infrastructure/terraform/modules/rds/main.tf`, the IAM policy attachment for Enhanced Monitoring contains an invalid ARN format.
- **Evidence (`rds/main.tf` line 249):**
  ```hcl
  policy_arn = "arn::iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
  ```
- **Vulnerability / Impact:** The partition segment of the ARN is missing (it contains `arn::iam` instead of `arn:aws:iam` or `arn:${data.aws_partition.current.partition}:iam`). This invalid ARN will fail validation on `terraform apply`, blocking database monitoring provisioning or defaulting the instance to unmonitored states under high-load production scenarios.
- **Remediation:** Correct the ARN string using the partition data source:
  ```hcl
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
  ```

### 2.3 Double Dots & Slash Issues in MSK Module
In `infrastructure/terraform/modules/msk/main.tf`, there are syntactic naming and logging configuration defects.
- **Evidence (`msk/main.tf` lines 270 and 278):**
  - Line 270: `domain_name = "msk..usora.internal"`
  - Line 278: `name = "/aws/msk//broker-logs"`
- **Impact:** The internal DNS server fails to register or resolve names with empty subdomain zones (`msk..usora.internal`). The CloudWatch log group is created with a double slash (`/aws/msk//broker-logs`), which breaks standard log shipper filters and telemetry aggregation regexes.
- **Remediation:** Parameterize subdomains and log paths using `${var.environment}` or similar variable names to prevent empty path segments.

### 2.4 Missing Environment Prefixes & Resource Collisions
Throughout the VPC, RDS, ElastiCache, and MSK modules, resource tags and identifiers omit environment prefixes, defaulting instead to static suffixes.
- **Evidence:**
  - `vpc/main.tf`: `Name = "-vpc"`, `Name = "-igw"`, `Name = "-public-"`, `Name = "-private-"`, `Name = "-nat-eip-"`, `Name = "-nat-gw-"`, `Name = "-public-rt"`, `Name = "-private-rt-"`, `Name = "-flow-logs"`, `name = "-vpc-flow-logs-role"`
  - `rds/main.tf`: `name = "-db-subnet-group"`, `name = "-postgres16-pg"`, `identifier = "-db-primary"`, `final_snapshot_identifier = "-db-final-"`, `name = "-rds-monitoring-role"`, `name = "-rds-sg"`, `identifier = "-db-replica-"`
  - `elasticache/main.tf`: `name = "-redis-sg"`, `name = "-redis-subnet-group"`, `name = "-redis7-pg"`, `replication_group_id = "-redis"`
  - `msk/main.tf`: `name = "-msk-sg"`, `name = "-msk-config"`, `cluster_name = "-msk"`, `name = "-msk-scram"`
- **Vulnerability / Impact:** Since AWS accounts are often shared across non-production environments (e.g. dev and staging in the same account/region), deploying these modules results in direct resource name collisions. Terraform plans will attempt to overwrite existing resources, destroying non-production environments and inducing severe data loss on shared databases/caches.
- **Remediation:** Enforce the defined `${var.environment}` prefix on all resource names and identifiers:
  ```hcl
  identifier = "${var.environment}-db-primary"
  ```

---

## 3. Prior Audit Remediation Status Tracker

We have tracked the status of findings documented in the previous security baseline review (dated 2026-07-31, baseline §3.1–§3.9). Many critical items have been successfully addressed, while some residual gaps remain open.

| Baseline Section | Prior Finding Description | Current Status | Current Code Evidence & Notes |
| :--- | :--- | :---: | :--- |
| **§3.1** | Tenant isolation bypass via `X-Tenant-ID` header at gateway | **FIXED** | `middleware/tenant.rs:74-124` — Resolves from JWT claim first; only honors header if user has explicit `tenant:cross_tenant_override` permission; backed by regression tests in `tenant.rs:159-197`. |
| **§3.2** | AML screening not gating compliance decisions | **FIXED** | `compliance/.../DomainService.java:109-155` — Screen matches are mapped to `violations`, with fail-closed behavior on processing errors (`:142-154`), blockaded inside `decision :170`. |
| **§3.3** | Dual-auth "signature" is an unkeyed hash | **FIXED** | `DomainService.java:257` uses robust `HashingUtil.hmacSha256(contentToSign, ruleSigningSecret)` and Merkle root; secret injected at line `:39`. |
| **§3.4** | JWT cache returns claims without re-checking expiration (`exp`) | **FIXED** | `auth/jwt.rs:64-76` — Explicitly asserts `claims.exp > now` on hit and evicts expired cache entries. |
| **§3.5** | MRZ `<` character auto-pass vulnerability | **FIXED** | `document-processor/src/extraction/mrz.rs:24-32` — Handled standard parsing rather than `<` special-case; validated by regression test `mrz.rs:395-415`. |
| **§3.6** | "Forensic" checks are basic visible-light RGB heuristics | **MITIGATED** | `validation/authenticity.rs` relabeled as `*_heuristic`, maximum confidence score capped at `0.4`, and explanatory metadata added. (Residual method names remain misleading, see §5.5). |
| **§3.7** | Outbound REST client Server-Side Request Forgery (SSRF) | **FIXED** | `integration/client/RestClient.java:45,66,85` invokes `EgressUrlGuard.assertSafeDestination`; guard re-resolves domain at call time, blocking loopback, link-local, RFC1918, CGNAT, and IPv6-ULA. |
| **§3.8** | Dead stubs (`checkIdentity`/`checkCompliance`) returning static `true` | **FIXED** | `tenant/client/GrpcClient.java:35-47` now throws `UnsupportedOperationException` rather than hardcoding static `true` bypass. |
| **§3.9** | Documentation asserts certifications the code does not support | **OPEN** | `main.md` §4.2–4.3 and `docs/compliance-mapping.md` still state SOC 2 Type II "Certified", GDPR/CCPA "Compliant", and "blockchain-anchored" audits. |

---

## 4. Open Critical Gaps (P0)

### 4.1 Gateway Authentication Dead Code & Total Auth Outage
- **Files:** `rust-services/usora-api-gateway/src/middleware/auth.rs:22,29`, `src/auth/jwt.rs:43-47`, `src/gateway_service.rs:106`
- **Vulnerability:** The API Gateway `AuthLayer` constructs its inner `JwtValidator` without loading any JSON Web Key Sets (JWKS) at startup, and `update_jwks` is dead code (never called). Furthermore, it initiates validation via `JwtValidator::new(None, None)` omitting issuer and audience validations.
- **Impact:** Every incoming request with an `Authorization: Bearer <token>` header is parsed, but signature verification fails because the internal JWKS key map is empty (`jwks.get(kid)` returns `MissingKey`). Consequently, 100% of valid bearer tokens are rejected with a `401 Unauthorized` response. Only unauthenticated routes (`/health`, `/metrics`) are reachable, rendering the gateway functionally offline.
- **Remediation:** Implement a background scheduler/lifecycle hook in Axum that queries the Identity Service JWKS endpoint (`https://identity/oauth2/jwks`) on startup and periodically stores the retrieved keys via `JwtValidator::update_jwks`. In addition, initialize the gateway validator with `JwtValidator::new(Some(issuer), Some(audience))` to ensure strict token validity.

### 4.2 Hardcoded Default HMAC Secret & Downstream Token Forgery
- **Files:** `notification/src/main/resources/application.yml:128-130`, `notification/.../security/JwtTokenProvider.java:23,34-41`
- **Vulnerability:** In `usora-notification-service`, the `JwtTokenProvider` utilizes a symmetric HMAC-SHA key sourced from Spring properties (`security.jwt.secret`), which defaults to the hardcoded, committed string: `defaultSecretKeyMustBeOverriddenInProduction`.
- **Impact:** Any client with knowledge of this default key can mint arbitrary JWTs containing malicious claims (e.g. spoofed `sub`, modified roles, and arbitrary `tenantId`). Because `validateToken` verifies only the signature and omits expiration (`exp`), issuer (`iss`), and audience (`aud`) checks, an attacker can bypass all authentication checks on the Notification service REST (8085) and gRPC (9095) endpoints.
- **Remediation:** Remove the default fallback value. Implement a fail-fast startup validator in `SecurityConfig.java` that terminates the Spring Boot process if `JWT_SECRET` is unset or matches the default development key.

### 4.3 Downstream Tenant Header Spoofing (Attribution Falsification)
- **Files:** `spring-boot-services/*/security/TenantInterceptor.java` (found in `audit`, `core`, `identity`, `notification`, `compliance`, `integration`, and `tenant` services).
- **Vulnerability:** While the gateway successfully prioritizes the JWT claim for tenant resolution (via `middleware/tenant.rs`), downstream Spring Boot orchestration services re-introduce the header-trust vulnerability. Spring Interceptors extract the `X-Tenant-ID` header and unconditionally override the authenticated JWT principal context.
- **Evidence (`TenantInterceptor.java` lines 33-36 in Audit Service):**
  ```java
  String headerTenantId = request.getHeader("X-Tenant-ID");
  if (headerTenantId != null) {
      TenantContext.setTenantId(headerTenantId);
  }
  ```
- **Impact:** If an attacker can bypass the gateway edge or route a request internally (e.g. via pod-to-pod network routes, sidecars, or unauthenticated internal load balancers), they can supply a falsified `X-Tenant-ID` header. In the `audit-service`, this allows a malicious actor to inject falsified compliance audits, overriding the logged `tenantId` and `actor`, which completely invalidates the integrity of the immutable compliance ledger.
- **Remediation:** Harmonize all Spring `TenantInterceptor` classes to resolve the tenant ID strictly from the verified JWT claims first. Disallow HTTP header overrides unless the request is associated with a trusted global super-admin principal, and ensure such events are logged under a specialized elevated-privilege audit category.

### 4.4 Unimplemented gRPC Services (Gateway Failure Loop)
- **Files:** `rust-services/usora-api-gateway/src/handlers/*_handler.rs`, `src/grpc/mod.rs`
- **Vulnerability:** The gateway declares multiple gRPC clients (for `identity`, `document`, `tenant`, `audit`, `compliance`, and `notification` services) to handle critical tasks like real-time token validation and tenant resolution. However, a repository-wide inspection reveals that the Spring Boot services declare `grpc.server.port` but implement **zero** actual `@GrpcService` or generated `*ImplBase` handlers.
- **Impact:** The gateway's gRPC calls fail with an `UNIMPLEMENTED` status code. This prevents the gateway from communicating with the orchestrators for tenant configurations or compliance lookups, leading to systematic cascading errors and total system unavailability.
- **Remediation:** Implement the respective gRPC services extending generated protobuf stub base classes in each Spring Boot service, or safely fallback to highly-optimized, authenticated internal REST communications.

---

## 5. Open High & Medium Gaps (P1/P2)

### 5.1 Plaintext gRPC Channels (P1)
- **Files:** `rust-services/usora-api-gateway/src/grpc/mod.rs:21-25`, `src/main.rs:95-99`
- **Vulnerability:** Gateway gRPC clients are configured with `https://orchestrator:9090` or `https://compute:9090` but build plaintext channels using `Channel::from_shared` with `connect_lazy()`, lacking `.tls_config()` and `.call_credentials()`.
- **Impact:** Tonic defaults to plaintext channels if TLS configurations are missing. In the absence of TLS protection, all internal control plane traffic (including sensitive PII, compliance rule queries, and verification decisions) travels in plaintext across the network, making it vulnerable to sniffing and man-in-the-middle attacks.
- **Remediation:** Secure Tonic client setups by supplying valid TLS trust anchors and loading client certificates for mutual TLS (mTLS):
  ```rust
  let connector = ClientTlsConfig::new()
      .ca_certificate(ca_cert)
      .identity(identity);
  ```

### 5.2 Default TLS 1.2 & Missing Client Auth on Edge (P1)
- **Files:** `rust-services/usora-api-gateway/src/config/mod.rs:12-18,199-204`
- **Vulnerability:** The gateway's HTTPS configurations fall back to `TLSv1.2` by default and construct Rustls configurations with `.with_no_client_auth()`.
- **Impact:** Disallows TLSv1.3 enforcement and fails to implement mutual TLS (mTLS) authentication on the public edge, contrary to architectural specifications.
- **Remediation:** Default minimum version to `TLSv1.3` and hook up a custom client-certificate verifier via `auth/mtls.rs`.

### 5.3 Identity User Administration Tenant Isolation Bypass (P1)
- **Files:** `identity/.../controller/v1/ApiController.java:54-57`, `service/DomainService.java:376-399`
- **Vulnerability:** The API endpoint `POST /api/v1/users` extracts the `tenantId` from the raw request body without validating it against the caller's JWT `tid` claim. Similarly, `PUT /api/v1/users/{id}/roles` loads a user by ID and modifies privileges without checking tenant ownership.
- **Impact:** A compromised tenant user with user-administration privileges can create or modify administrative roles for other tenants, leading to cross-tenant privilege escalation (IDOR).
- **Remediation:** Ensure that `DomainService` matches the target `tenantId` against the caller's authenticated tenant claim, or enforce globally scoped super-administrator roles only for such cross-tenant actions.

### 5.4 Permissive Cross-Origin Resource Sharing (CORS) (P2)
- **Files:** `rust-services/usora-api-gateway/src/routes/mod.rs`
- **Vulnerability:** The Axum gateway router attaches a permissive CORS layer:
  ```rust
  CorsLayer::new().allow_origin(Any).allow_methods(Any).allow_headers(Any)
  ```
- **Impact:** Allows any third-party origin to drive cross-origin HTTP requests against the gateway, increasing the risk of cross-origin token leakage and browser-based scanning attacks.
- **Remediation:** Establish an explicit CORS origin allowlist from configuration variables and restrict headers to `Authorization` and `Content-Type`.

### 5.5 Symmetric Key Fallbacks in Encryption Utilities (P2)
- **Files:** `compliance/util/EncryptionUtil.java:15,19-24`
- **Vulnerability:** The `EncryptionUtil` inside the `usora-compliance-service` defaults to a zeroed-out 256-bit AES key (`new byte[32]`) if the environment variable `COMPLIANCE_ENCRYPTION_KEY` is not present.
- **Impact:** KYC evidence uploaded during compliance processes is encrypted at rest using a publicly known, zeroed-out static key. This compromises the confidentiality of highly sensitive personal documents (e.g., passports, driver's licenses) stored within the system.
- **Remediation:** Enforce fail-fast initialization. Throw a runtime exception at application startup if `COMPLIANCE_ENCRYPTION_KEY` is undefined or lacks sufficient entropy.

### 5.6 Static Host OIDC Metadata (P2)
- **Files:** `identity/.../service/DomainService.java:423-441`
- **Vulnerability:** The OpenID Configuration response hardcodes the token issuer to `http://localhost:8081`.
- **Impact:** Prevents proper token validation checks (`iss` matching) when services are deployed on a real domain name, and leaks internal network structures.
- **Remediation:** Parameterize the issuer URL using configuration parameters injected at runtime.

### 5.7 Incomplete Audit Hash Chain Scope (P2)
- **Files:** `compliance/.../DomainService.java:556-569`
- **Vulnerability:** The audit hash chain calculates blocks using only `previousHash + caseId + action + timestamp`, omitting fields such as `actor`, `tenantId`, and `detailsJson`.
- **Impact:** A malicious actor with access to the backend database can alter audit row descriptions or spoof the acting identity (`actor`) without breaking the cryptographic integrity of the hash chain.
- **Remediation:** Include all immutable audit record fields in the HMAC calculation.

### 5.8 Forensic Detection Name Misrepresentation (P2)
- **Files:** `usora-document-processor/src/validation/authenticity.rs`
- **Vulnerability:** The document validation engine defines checks named `detect_uv_fluorescence` and `detect_ir_absorption` but executes basic RGB color-range and grayscale heuristics on visible-light captures.
- **Impact:** High compliance risk. The system falsely implies true forensic verification capability (which requires specialized physical UV/IR lighting sources) to downstream API clients and auditors, potentially leading to a false sense of security regarding document liveness.
- **Remediation:** Rename the functions to `heuristic_visible_*` and explicitly cap their maximum confidence weight in the JSON output, clarifying that they are visible-light visual indicators, not true forensic physical signal checks.

---

## 6. Remediation Roadmap & Prioritization Backlog

| Phase | Priority | Security Domain | Target Module | Remediation Action |
|---|---|---|---|---|
| **Phase 1** | **P0 - Critical** | Identity & Access Control | `api-gateway` | Load JWKS at gateway boot; enforce strict RS256 issuer/audience validation. |
| **Phase 1** | **P0 - Critical** | Data Isolation | Spring Services | Patch `TenantInterceptor` files to prioritize JWT claims. Remove `X-Tenant-ID` header override. |
| **Phase 1** | **P0 - Critical** | Infrastructure | All TF Modules | Correct regional endpoint interpolation (`com.amazonaws..s3`) and prepend `${var.environment}` prefixes. |
| **Phase 1** | **P0 - Critical** | Secrets Management | `notification-service` | Remove default HMAC secret key. Implement fail-fast on startup if `JWT_SECRET` is missing. |
| **Phase 2** | **P1 - High** | Control Plane | Gateway & Spring | Implement missing `@GrpcService` backend servers; configure mutual TLS (mTLS) over gRPC channels. |
| **Phase 2** | **P1 - High** | Cryptography | `compliance-service` | Remove zero-key fallback in `EncryptionUtil`. Correct invalid monitored IAM policy ARN in RDS. |
| **Phase 2** | **P1 - High** | Edge Security | Gateway & Identity | Enforce TLSv1.3 and hook up mTLS verifier on public edge; enforce tenant scoping on User Administration. |
| **Phase 3** | **P2 - Medium** | Network Security | `api-gateway` | Implement rigorous CORS origin allowlists on the gateway router. |
| **Phase 3** | **P2 - Medium** | Forensic Validation | `document-processor` | Re-classify and rename RGB heuristic "forensics" to visual heuristics. |
| **Phase 3** | **P2 - Medium** | Auditing & Compliance | `compliance-service` | Include all fields in audit trail hash chain calculation; parameterize OIDC issuer host. |

---

## 7. Conclusion

While the USORA platform utilizes a highly modern, performant, and scalable architecture (combining the raw speed of Rust with the workflow execution capabilities of Spring Boot/Camunda), its security boundaries are currently compromised by critical implementation flaws. Resolving the gateway authentication outage, removing downstream header-trust overlaps, and correcting the regional/naming defects in the Terraform blueprints are critical prerequisites before any production deployment.

Implementing the Phase 1 remediation items outlined above will immediately elevate the platform's posture to a level suitable for compliance audits and production-readiness.
