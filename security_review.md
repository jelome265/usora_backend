# USORA KYC Platform — Consolidated Enterprise Security, Reliability & Infrastructure Review

**Author:** Jules, Principal Security & Infrastructure Engineer
**Date:** August 13, 2026
**Classification:** Confidentially Restricted — Internal Engineering Only
**Target Architecture:** Rust Axum/Tokio API Gateway + 3 Rust Compute Engines + 7 Java Spring Boot 3.4.0 Orchestration Services

---

## 1. Executive Summary

This document presents a comprehensive, multi-dimensional security and infrastructure audit of the **USORA KYC Platform**. USORA is a high-performance, polyglot compliance and verification system designed for multi-tenant, regulated enterprise environments. At this scale, maintaining zero-trust, strict tenant isolation, cryptographic assurances, robust network topologies, and clean container packaging is paramount to satisfy SOC 2 Type II, GDPR, EU AML5/AML6, and ISO 27001 compliance standards.

Our static analysis, codebase reviews, and architectural deep-dives have revealed several critical and high-severity gaps across both the **application code** (e.g., total gateway auth outages, forgeable downstream JWTs, tenant-spoofing header trust) and the **infrastructure-as-code / orchestration layers** (e.g., broken regional endpoint interpolations in Terraform, wide-open database egress policies in Kubernetes, un-compilable Kustomize overlays, and sequential, highly inefficient CI/CD workflows).

This consolidated review fuses findings from all prior security audits and deep technical evaluations to establish a single, authoritative, and actionable remediation roadmap.

---

## 2. Infrastructure & Orchestration Security (Terraform & Kubernetes)

The USORA deployment infrastructure leverages Terraform for AWS resource provisioning (VPC, EKS, RDS, ElastiCache, MSK) and Kubernetes/Helm/Kustomize for container orchestration. Static analysis reveals multiple architectural vulnerabilities that break regional isolation, compromise data confidentiality, and threaten multi-environment separation.

### 2.1 Broken Regional String Interpolations (Terraform VPC Endpoint Failure)
- **Vulnerability:** In `infrastructure/terraform/modules/vpc/main.tf` (lines 239–303), regional AWS service names for private VPC Gateway/Interface Endpoints are hardcoded with an empty regional segment.
  - **Evidence:**
    - Line 239: `service_name = "com.amazonaws..s3"`
    - Line 256: `service_name = "com.amazonaws..dynamodb"`
    - Line 273: `service_name = "com.amazonaws..ecr.api"`
    - Line 288: `service_name = "com.amazonaws..ecr.dkr"`
    - Line 303: `service_name = "com.amazonaws..eks"`
- **Vulnerability Impact:** Because the current AWS region variable (e.g., `${data.aws_region.current.name}`) is omitted, Terraform fails to compile or apply due to invalid service names. If bypassed manually, traffic destined for S3, ECR, and DynamoDB is routed over the public internet instead of the secure AWS private backbone, violating Zero-Trust networking principles.
- **Remediation:** Correctly interpolate the AWS region data source:
  ```hcl
  service_name = "com.amazonaws.${data.aws_region.current.name}.s3"
  ```

### 2.2 Broken IAM Policy ARN in RDS Module
- **Vulnerability:** In `infrastructure/terraform/modules/rds/main.tf` (line 286), the IAM policy attachment for Enhanced Monitoring uses an invalid AWS partition/ARN format.
  - **Evidence:**
    ```hcl
    policy_arn = "arn::iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
    ```
- **Vulnerability Impact:** The partition segment of the ARN is missing (`arn::iam` instead of `arn:aws:iam`). This invalid string will trigger a validation error during `terraform apply`, preventing database monitoring provisioning and leaving instances vulnerable to silent resource exhaustion under production loads.
- **Remediation:** Parameterize the ARN with the correct partition reference:
  ```hcl
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
  ```

### 2.3 Syntactic Defects in MSK Module
- **Vulnerability:** In `infrastructure/terraform/modules/msk/main.tf` (lines 270 & 278), there are domain and logging formatting bugs.
  - **Evidence:**
    - Line 270: `domain_name = "msk..usora.internal"` (Broken empty subdomain zone)
    - Line 278: `name = "/aws/msk//broker-logs"` (Double slash in CloudWatch log group)
- **Vulnerability Impact:** Internal DNS resolution fails to register or resolve subdomains containing empty segments. Additionally, double slashes in CloudWatch log paths break standard log shipper forwarding regexes and telemetry filters.
- **Remediation:** Parameterize subdomains and log paths cleanly using `${var.environment}` to guarantee well-formed paths.

### 2.4 Missing Environment Prefixes & Resource Collisions
- **Vulnerability:** Multiple modules provision resources using static, hardcoded naming suffixes (e.g. `Name = "-vpc"`, `identifier = "-db-primary"`, `replication_group_id = "-redis"`, `cluster_name = "-msk"`) omitting environment-specific variables.
- **Vulnerability Impact:** If non-production environments (e.g., dev and staging) share the same AWS account and region, applying these manifests causes immediate resource collisions. Terraform will attempt to destroy or overwrite existing instances, risking severe data loss on shared databases/caches.
- **Remediation:** Prefix all resource identifiers with `${var.environment}`:
  ```hcl
  identifier = "${var.environment}-db-primary"
  ```

### 2.5 Overly Permissive Kubernetes Network Policies (Kubernetes Egress Loophole)
- **Vulnerability:** In `infrastructure/k8s/base/network-policies.yml`, inter-service and egress database rules are excessively open.
  - **Evidence (Inter-Service Wildcard):**
    ```yaml
    spec:
      podSelector:
        matchLabels:
          app.kubernetes.io/component: service
      ingress:
        - ports: [ {port: 8080}, {port: 9090} ]
          from:
            - podSelector:
                matchLabels:
                  app.kubernetes.io/component: service
    ```
    This matches *any* service pod and exposes all microservices to each other.
  - **Evidence (Database Egress Wildcard):**
    ```yaml
    spec:
      podSelector:
        matchExpressions:
          - key: app.kubernetes.io/component
            operator: In
            values: [service, compute]
      egress:
        - to:
            - ipBlock:
                cidr: 0.0.0.0/0   # <-- Wide-open internet egress
          ports: [ {port: 5432} ]
    ```
- **Vulnerability Impact:** If any microservice is compromised (e.g. via RCE), an attacker can easily bypass network controls, pivot to other pods (e.g., accessing sensitive auditing services), and exfiltrate database contents directly to public, attacker-controlled servers over standard database ports (`5432`/`6379`).
- **Remediation:** Restrict inter-service connection flows using exact microservice pod selectors instead of wildcard label matches, and constrain egress targets for database ports specifically to the internal VPC CIDR ranges (e.g. `10.2.0.0/16`).

### 2.6 Broken and Empty Kustomize Dev Overlay
- **Vulnerability:** The dev overlay `infrastructure/k8s/overlays/dev/kustomization.yml` references `../../base`, which only defines namespaces and network policies. It then attempts to apply strategic patches to `usora-gateway`, `usora-core`, and `usora-document-processor` deployments that are entirely missing from the base.
- **Vulnerability Impact:** Any execution of `kustomize build` fails immediately, making local development orchestration and automated deployments non-functional.
- **Remediation:** Move the baseline deployment templates into `infrastructure/k8s/base` to ensure overlays compile correctly, or fully consolidate deployment definitions into unified Helm charts.

---

## 3. Application Security & Cryptography

### 3.1 Gateway Authentication Dead Code (Total Edge Auth Outage)
- **Vulnerability:** In `rust-services/usora-api-gateway`, the `AuthLayer` constructs its `JwtValidator` via `JwtValidator::new(None, None)` (without providing an issuer or audience), and leaves the JWKS map completely empty. The `update_jwks()` function is defined in `auth/jwt.rs` but is never invoked.
- **Vulnerability Impact:** Every incoming request containing an `Authorization: Bearer <token>` header is parsed, but signature verification fails because the internal JWKS key map is empty, resulting in a `401 Unauthorized` response. Consequently, 100% of valid bearer tokens are rejected, putting the entire authenticated KYC surface **100% offline**. If bypassed by operators by naively configuring JWKS without specifying the issuer and audience, the system will accept any token from *any* co-signed client, creating an authentication bypass vulnerability.
- **Remediation:** Implement a background thread in Axum that queries the Identity Service JWKS endpoint (`https://identity/oauth2/jwks`) on startup and periodically updates the validator keys. Populate the validator with explicit, non-default `JWT_ISSUER` and `JWT_AUDIENCE` configurations.

### 3.2 Forgeable notification JWT via Committed Default HMAC Secret
- **Vulnerability:** In `usora-notification-service`, the `JwtTokenProvider` utilizes a symmetric HMAC-SHA key sourced from Spring properties (`security.jwt.secret`), which defaults to the hardcoded, committed string: `defaultSecretKeyMustBeOverriddenInProduction`. Furthermore, `validateToken()` only executes signature parsing without verifying expiration (`exp`), issuer (`iss`), or audience (`aud`).
- **Vulnerability Impact:** Any actor with knowledge of this default key can mint arbitrary JWTs containing spoofed user roles and tenant IDs. They can bypass all authentication checks on the Notification service REST (8085) and gRPC (9095) endpoints to trigger malicious notifications or intercept sensitive communication.
- **Remediation:** Enforce a fail-fast validator in `SecurityConfig.java` that terminates the Spring Boot process at startup if `security.jwt.secret` is unset, empty, or matches the default development key. Align authentication mechanics on RS256 token verification delegated from the Identity Service.

### 3.3 Downstream Tenant Header Spoofing (Attribution Falsification)
- **Vulnerability:** While the Gateway correctly extracts the tenant ID from token claims first, the downstream Spring Boot orchestration services re-introduce the header-trust vulnerability.
  - **Evidence:** `TenantInterceptor.java` across the `audit`, `core`, `identity`, `notification`, `compliance`, and `integration` services extract the `X-Tenant-ID` header and unconditionally override the authenticated JWT principal context.
- **Vulnerability Impact:** If an attacker can bypass the gateway edge (e.g. via direct pod access, sidecars, or unauthenticated internal load balancers), they can supply a falsified `X-Tenant-ID` header. In `usora-audit-service`, this allows a malicious actor to inject falsified compliance audits, overriding the logged `tenantId` and `actor`, which completely invalidates the integrity of the compliance ledger.
- **Remediation:** Standardize all Spring `TenantInterceptor` classes to resolve the tenant ID strictly from verified JWT claims first. Disallow HTTP header overrides unless the request is associated with a trusted global super-admin principal, and log such occurrences as high-severity audit events.

### 3.4 Symmetric Key Fallback in Compliance Encryption
- **Vulnerability:** The `EncryptionUtil` inside `usora-compliance-service` defaults to a zeroed-out 256-bit AES key (`new byte[32]`) if the environment variable `COMPLIANCE_ENCRYPTION_KEY` is not present.
- **Vulnerability Impact:** KYC evidence uploaded during compliance processes is encrypted at rest using a publicly known, zeroed-out static key. This compromises the confidentiality of highly sensitive personal documents (e.g., passports, driver's licenses) stored within the system.
- **Remediation:** Throw a runtime exception at application startup if `COMPLIANCE_ENCRYPTION_KEY` is undefined, empty, or lacks sufficient entropy.

---

## 4. gRPC Control Plane & Network Security

### 4.1 Unimplemented gRPC Services (Gateway Failure Loop)
- **Vulnerability:** The gateway declares multiple gRPC clients (for `identity`, `document`, `tenant`, `audit`, `compliance`, and `notification` services) to handle critical tasks like real-time token validation and tenant resolution. However, a repository-wide inspection reveals that the Spring Boot services declare `grpc.server.port` but implement **zero** actual `@GrpcService` or generated `*ImplBase` handlers.
- **Vulnerability Impact:** The gateway's gRPC calls fail with an `UNIMPLEMENTED` status code. This prevents the gateway from communicating with the orchestrators for tenant configurations or compliance lookups, leading to systematic cascading errors and total system unavailability.
- **Remediation:** Implement the respective gRPC services extending generated protobuf stub base classes in each Spring Boot service, or safely fallback to highly-optimized, authenticated internal REST communications.

### 4.2 Plaintext gRPC Channels
- **Vulnerability:** Gateway gRPC clients are configured with `https://orchestrator:9090` or `https://compute:9090` but build plaintext channels using `Channel::from_shared` with `connect_lazy()`, lacking `.tls_config()` and `.call_credentials()`.
- **Vulnerability Impact:** Tonic defaults to plaintext channels if TLS configurations are missing. In the absence of TLS protection, all internal control plane traffic (including sensitive PII, compliance rule queries, and verification decisions) travels in plaintext across the network, making it vulnerable to sniffing and man-in-the-middle attacks.
- **Remediation:** Secure Tonic client setups by supplying valid TLS trust anchors and loading client certificates for mutual TLS (mTLS):
  ```rust
  let connector = ClientTlsConfig::new()
      .ca_certificate(ca_cert)
      .identity(identity);
  ```

---

## 5. Compute Layer Reliability & Code Integrity (Rust Engines)

### 5.1 FFI Failsafe & Process Crash Hazards
- **Vulnerability:** In `usora-document-processor` and `usora-face-matching-engine`, heavy C/C++ libraries (such as **Leptonica**, **Tesseract**, **OpenCV**, and **FAISS**) are integrated via foreign-function interface (FFI) bindings.
- **Vulnerability Impact:** FFI operations bypass Rust's standard safety guarantees. Any segmentation fault, memory corruption, or native `panic`/`abort` inside the C++ library will immediately terminate the entire Rust OS process. A single malformed image payload or corrupt FAISS index file can cause a segment violation (SIGSEGV) in the native FFI library, taking down the entire service replica.
- **Remediation:** Isolate the raw FFI operations into a separate pool of worker processes (process-level isolation). Use standard IPC or lightweight RPCs to communicate between the core async Rust service and the FFI execution process. Implement robust image dimension and mime pre-validation *before* passing pointers to C/C++ FFI.

### 5.2 Dynamic Code Execution Risks via Rhai Scripting (Risk Engine)
- **Vulnerability:** In `usora-risk-scoring-engine/src/rules/dsl.rs`, dynamic rules are evaluated via the `Rhai` scripting engine. The engine is instantiated using `Engine::new()` and lacks strict memory allocation boundaries.
- **Vulnerability Impact:** Attackers or malicious tenants could compile scripts containing infinite loops, deep array nesting, or memory-heavy calculations, leading to symmetric resource exhaustion and Out-Of-Memory (OOM) crashes.
- **Remediation:** Use `Engine::new_raw()` to disable default file I/O and system access. Implement custom memory allocation limiters and progressive CPU limit checks on the engine.

### 5.3 FAISS Index Lock Contention & Statefulness Barriers
- **Vulnerability:** The `usora-face-matching-engine` protects FAISS indices via a global standard library mutex (`Mutex<HashMap<String, Box<dyn faiss::Index>>>`) and persists index files to local disk.
- **Vulnerability Impact:** Multi-threaded concurrent matches face severe lock contention, degrading p99 throughput. Furthermore, persisting indices to local container storage prevents horizontal stateless scaling—scaling up replicas will result in inconsistent index states across pods.
- **Remediation:** Migrate from local disk-based FAISS persistence to a managed, multi-tenant-native distributed vector database (e.g., Qdrant, Milvus, or pgvector).

### 5.4 Forensic Check Name Misrepresentation
- **Vulnerability:** In `usora-document-processor/src/validation/authenticity.rs`, checks named `detect_uv_fluorescence` and `detect_ir_absorption` are implemented using basic RGB color-variance and grayscale heuristics on visible-light captures.
- **Vulnerability Impact:** This creates a substantial compliance and audit risk. The system implies true forensic verification capability (which requires specialized physical UV/IR lighting sources) to downstream API clients and auditors, potentially leading to a false sense of security regarding document liveness.
- **Remediation:** Rename the functions to `heuristic_visible_*` and explicitly cap their maximum confidence weight (e.g. `0.3`) in the JSON output, clarifying that they are visible-light visual indicators, not true forensic physical signal checks.

---

## 6. Software Packaging & CI/CD Pipelines

### 6.1 Rust Dockerfile Broken Target Path
- **Vulnerability:** `infrastructure/docker/Dockerfile.rust` targets a hardcoded binary path `/app/target/release/usora-service` that does not exist in the cargo workspace.
- **Vulnerability Impact:** The container build process fails completely.
- **Remediation:** Parameterize the binary output path or create dedicated multi-stage builds mapping to specific cargo workspace members (e.g. `usora-api-gateway`).

### 6.2 Spring Boot Un-scoped Build Context
- **Vulnerability:** `infrastructure/docker/Dockerfile.spring-boot` copies all project files (`COPY pom.xml ./` and `COPY src ./src`) without scoping to individual services under `spring-boot-services/`.
- **Vulnerability Impact:** Bloated build contexts, long compile times, and potential Maven compilation errors due to module layout mismatches.
- **Remediation:** Scope the maven build specifically using `-pl` module target flags and copy only the respective target module dependencies.

### 6.3 Sequential, Inefficient CI/CD Pipeline
- **Vulnerability:** In `.github/workflows/ci-cd.yml`, 11 distinct service containers are built sequentially in a single job shell-loop, and security scan failures are ignored via `continue-on-error: true`.
- **Vulnerability Impact:** Build times can easily range from 2 to 4 hours, creating a massive delivery bottleneck. Additionally, silent scan failures allow vulnerable containers containing critical CVEs to bypass security gates into production.
- **Remediation:** Re-architect the build job into parallel task matrices utilizing Docker buildx with caching, and enforce strict, fail-fast thresholds on vulnerability scanners.

---

## 7. Status of Prior Audit Findings

| Prior Finding (2026-07-31) | Original Severity | Current Status | Verification Context (Evidence) |
|---|---|---|---|
| **SSRF in Outbound REST Client** | P2 | **RESOLVED** | `integration/.../RestClient.java` calls `EgressUrlGuard.assertSafeDestination()` which checks against RFC1918, loopback, and link-local ranges at call time. |
| **MRZ Checksum `<` Filler Bypass** | P1 | **RESOLVED** | `document-processor/.../mrz.rs` now properly implements the weighted ICAO 9303 checksum and catches `<` edge-cases. |
| **JWT Cache Expiry Bypass** | P1 | **RESOLVED** | `api-gateway/.../jwt.rs` has been patched to validate `claims.exp > now` on every LRU cache hit, evicting expired entries. |
| **Silent AML Screening Failures** | P0 | **RESOLVED** | `compliance/.../DomainService.java` collects screening matches inside violations and fails-closed on system/gRPC errors. |
| **Unkeyed Dual-Auth Hash** | P1 | **RESOLVED** | `compliance/.../DomainService.java` uses `HashingUtil.hmacSha256` backed by a secure rule signing secret instead of a plain SHA-256 digest. |

---

## 8. Remediation Roadmap & Prioritization Backlog

| Phase | Priority | Security Domain | Target Module | Remediation Action |
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

## 9. Conclusion

The USORA platform possesses a highly scalable, high-performance polyglot architecture. However, several critical gaps must be closed before the platform can be considered secure and compliant. Remediating the gateway authentication outage, removing downstream header-trust overlaps, correcting regional/naming defects in the Terraform blueprints, and hardening network egress paths are absolute prerequisites for production readiness.

Implementing the Phase 1 remediation items outlined in this consolidated report will immediately elevate the platform's security posture to satisfy rigorous compliance audits and secure tenant boundaries.

*Report compiled by: Jules, Principal Security & Infrastructure Engineer.*
