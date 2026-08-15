# USORA KYC Platform — Comprehensive Security & Infrastructure Review

**Author:** Jules, Principal Security & Infrastructure Engineer
**Date:** August 12, 2026
**Classification:** Confidentially Restricted — Internal Engineering Only
**Target Architecture:** Polyglot Microservices Architecture (Rust Axum/Tokio API Gateway + 3 Rust Compute Engines + 7 Java Spring Boot Orchestration Services + Terraform/Kubernetes/Helm Infrastructure)

---

## 1. Executive Summary

This comprehensive security and infrastructure review provides an exhaustive, multi-dimensional assessment of the **USORA KYC Platform** codebase, deployment blueprints, access control matrices, and data isolation mechanisms. USORA is an enterprise-grade multi-tenant Know Your Customer (KYC) and compliance platform designed to perform automated identity verification, biometric matching, risk scoring, and sanction screening.

At this scale, the integrity of tenant isolation, cryptographic assurances, control plane security, network topology, and deployment infrastructure is paramount to achieving zero-trust and satisfying strict regulatory frameworks (including SOC 2 Type II, GDPR, EU AML5/AML6, and ISO 27001).

Our static and architectural analysis reveals critical vulnerabilities and operational gaps across the **application layer**, **inter-service control plane**, and **infrastructure-as-code (Terraform & Kubernetes)** layers. These range from total authentication outages on the gateway and forgeable-by-default JSON Web Tokens in downstream services, to broken regional string interpolations and naming collisions in Terraform modules.

This document synthesizes findings across all security and infrastructure domains, tracks remediation status relative to historical baseline audits, and establishes a prioritized, actionable remediation roadmap.

---

## 2. Infrastructure Security & Terraform Deep Dive

The deployment infrastructure utilizes Terraform to manage AWS VPC, EKS, RDS, ElastiCache, and MSK modules, along with Helm and Kustomize for Kubernetes deployment orchestration. A thorough review reveals critical flaws that break regional isolation, compromise IAM role attachments, and prevent multi-environment (dev/staging/prod) coexistence.

### 2.1 Broken Regional String Interpolation & Service Names
In `infrastructure/terraform/modules/vpc/main.tf`, regional endpoint definitions contain malformed double-dot string interpolations due to an unpopulated region variable.
- **Evidence (`infrastructure/terraform/modules/vpc/main.tf`):**
  - Line 278: `service_name = "com.amazonaws..s3"`
  - Line 293: `service_name = "com.amazonaws..dynamodb"`
  - Line 309: `service_name = "com.amazonaws..ecr.api"`
  - Line 323: `service_name = "com.amazonaws..ecr.dkr"`
  - Line 337: `service_name = "com.amazonaws..eks"`
- **Vulnerability / Impact:** Because the current AWS region is omitted (e.g. missing `${data.aws_region.current.name}`), Terraform execution fails during `plan` or `apply` because AWS API rejects invalid endpoint service names (`com.amazonaws..s3`). If bypassed manually, traffic destined for AWS services (S3, ECR, DynamoDB) routes over the public internet rather than private VPC Gateway/Interface Endpoints, violating Zero-Trust networking principles.
- **Remediation:** Parameterize endpoints with AWS region interpolation:
  ```hcl
  service_name = "com.amazonaws.${data.aws_region.current.name}.s3"
  ```

### 2.2 Broken IAM Policy ARN in RDS Module
In `infrastructure/terraform/modules/rds/main.tf`, the IAM policy attachment for Enhanced Monitoring contains an invalid ARN string format.
- **Evidence (`infrastructure/terraform/modules/rds/main.tf` line 249):**
  ```hcl
  policy_arn = "arn::iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
  ```
- **Vulnerability / Impact:** The partition segment of the ARN is missing (`arn::iam` instead of `arn:aws:iam` or `arn:${data.aws_partition.current.partition}:iam`). AWS IAM API rejects this invalid ARN, causing database monitoring provisioning to fail and leaving RDS instances unmonitored under high-load production scenarios.
- **Remediation:** Correct the ARN using the partition data source:
  ```hcl
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
  ```

### 2.3 Double-Dot Subdomain & Path Syntax Defects in MSK Module
In `infrastructure/terraform/modules/msk/main.tf`, syntactic errors compromise internal DNS and logging configurations.
- **Evidence (`infrastructure/terraform/modules/msk/main.tf` lines 270 and 278):**
  - Line 270: `domain_name = "msk..usora.internal"`
  - Line 278: `name = "/aws/msk//broker-logs"`
- **Impact:** Route 53 private hosted zones fail to resolve hostnames containing empty subdomain labels (`msk..usora.internal`). CloudWatch creates log groups with double slashes (`/aws/msk//broker-logs`), breaking standard log shipper regexes and telemetry aggregation.
- **Remediation:** Enforce `${var.environment}` interpolation in domain names and log group paths.

### 2.4 Resource Naming Collisions & Environment Overlaps
Across VPC, RDS, ElastiCache, and MSK Terraform modules, resource identifiers omit environment prefixes, defaulting instead to static suffixes.
- **Evidence:**
  - `vpc/main.tf`: `Name = "-vpc"`, `Name = "-igw"`, `Name = "-public-"`, `Name = "-private-"`
  - `rds/main.tf`: `identifier = "-db-primary"`, `name = "-db-subnet-group"`, `final_snapshot_identifier = "-db-final-"`
  - `elasticache/main.tf`: `replication_group_id = "-redis"`, `name = "-redis-subnet-group"`
  - `msk/main.tf`: `cluster_name = "-msk"`, `name = "-msk-sg"`, `name = "-msk-scram"`
- **Vulnerability / Impact:** Deploying dev, staging, or production environments within a shared AWS account results in direct resource naming collisions. Terraform plans will attempt to overwrite existing infrastructure, resulting in accidental destruction of databases and caches.
- **Remediation:** Prepend `${var.environment}` to all resource identifiers and tags.

### 2.5 Kubernetes Network Policy Egress Over-Permissiveness
In `infrastructure/k8s/base/network-policies.yml`, egress rules for database workloads allow traffic to `0.0.0.0/0` on database ports.
- **Evidence:** Egress targets for PostgreSQL (port 5432), Redis (port 6379), and Kafka (port 9092) allow `cidr: 0.0.0.0/0`.
- **Vulnerability / Impact:** If a microservice pod is compromised, an attacker can exfiltrate sensitive data over standard database ports directly to external IP addresses.
- **Remediation:** Restrict network policy egress blocks strictly to the VPC private subnet CIDR range (e.g. `10.2.0.0/16`).

---

## 3. Application Security & Access Control Review

### 3.1 Gateway Authentication Outage & Unenforced Claims
- **Vulnerability:** In `usora-api-gateway`, the `AuthLayer` middleware constructs `JwtValidator` via `JwtValidator::new(None, None)` without issuer or audience configurations. The `jwks` key map is initialized empty, and `update_jwks()` is defined but never called anywhere in the gateway process.
- **Evidence:** `rust-services/usora-api-gateway/src/middleware/auth.rs` and `src/auth/jwt.rs`.
- **Impact:** Every incoming request with an `Authorization: Bearer <token>` header fails key lookup (`MissingKey`), returning a `401 Unauthorized` response. The authenticated KYC API surface is 100% offline; only `/health` and `/metrics` routes are accessible. Furthermore, if key loading is added without setting `issuer` and `audience`, the validator will accept tokens signed by any trusted issuer or co-tenant.
- **Remediation:** Implement a startup and background refresh task in Axum to fetch JWKS from the Identity Service (`/oauth2/jwks`) and update the `JwtValidator` cache. Require `JWT_ISSUER` and `JWT_AUDIENCE` configurations and enforce validation on token claims.

### 3.2 Hardcoded Default HMAC Secret in Notification Service
- **Vulnerability:** In `usora-notification-service`, `JwtTokenProvider` uses HMAC-SHA signing backed by `security.jwt.secret`, which defaults to the hardcoded string `defaultSecretKeyMustBeOverriddenInProduction` in `application.yml`.
- **Evidence:** `spring-boot-services/usora-notification-service/src/main/resources/application.yml` and `JwtTokenProvider.java`.
- **Impact:** Any client aware of the default string can mint custom JWTs containing arbitrary `sub`, `tenantId`, and `roles`. Because `validateToken()` checks only the HMAC signature (omitting `iss`, `aud`, and `exp`), an attacker can bypass authentication on Notification REST (8085) and gRPC (9095) endpoints, impersonating any tenant or user.
- **Remediation:** Remove the fallback secret from `application.yml`. Implement a fail-fast startup check in `SecurityConfig.java` that terminates application initialization if `JWT_SECRET` is missing, empty, or set to the default value.

### 3.3 Downstream Tenant Header Spoofing & Audit Chain Falsification
- **Vulnerability:** While the API Gateway prioritizes verified JWT claims for tenant resolution, downstream Spring Boot services re-introduce header-trust vulnerabilities in `TenantInterceptor`.
- **Evidence:** In `usora-audit-service`, `TenantInterceptor.java` extracts `X-Tenant-ID` from the HTTP request header and unconditionally overrides the JWT tenant context:
  ```java
  String headerTenant = request.getHeader("X-Tenant-ID");
  if (headerTenant != null && !headerTenant.isEmpty()) {
      TenantContext.setCurrentTenant(headerTenant);
  }
  ```
- **Impact:** If an attacker reaches a Spring Boot service directly (bypassing the gateway via direct pod access, internal mesh routes, or unauthenticated internal load balancers), they can supply a forged `X-Tenant-ID` header. In the audit service, this allows an attacker to inject falsified compliance audit records with spoofed tenant and actor attributions, violating immutable ledger integrity and SOC 2 requirements.
- **Remediation:** Standardize all Spring Boot `TenantInterceptor` implementations to resolve tenant ID strictly from verified JWT claims. Prohibit HTTP header overrides unless authenticated as a verified global super-admin principal, and log all elevated overrides under a dedicated audit category.

---

## 4. gRPC Control Plane & Network Security Review

### 4.1 Unimplemented gRPC Services
- **Vulnerability:** The API Gateway defines gRPC clients for `identity`, `document`, `tenant`, `audit`, `compliance`, and `notification` services to handle real-time token validation and tenant resolution. However, Spring Boot backends configure `grpc.server.port` but contain **zero** actual `@GrpcService` or generated `*ImplBase` handlers.
- **Evidence:** Repository-wide inspection confirms no `@GrpcService` backend implementations exist in Spring Boot modules.
- **Impact:** Gateway gRPC invocations fail with `UNIMPLEMENTED` status codes. Control plane operations (such as tenant configuration lookups or rate-limit checks) fail, triggering cascading errors across the gateway.
- **Remediation:** Implement concrete `@GrpcService` classes extending generated Protobuf stubs in each Spring service, or transition control plane RPCs to dedicated, authenticated internal REST endpoints.

### 4.2 Plaintext gRPC Channels & Unauthenticated Inter-Service Communication
- **Vulnerability:** Gateway gRPC clients build channels using `Channel::from_shared()` with `connect_lazy()`, omitting `.tls_config()` and `.call_credentials()`.
- **Evidence:** `rust-services/usora-api-gateway/src/grpc/mod.rs` lines 21–25.
- **Impact:** All inter-service gRPC control plane traffic travels in plaintext without TLS encryption or mutual authentication (mTLS). In multi-tenant cloud environments, plaintext gRPC exposes PII, compliance evaluation decisions, and audit events to network sniffing and man-in-the-middle attacks.
- **Remediation:** Configure Tonic gRPC clients with explicit TLS trust anchors (`ClientTlsConfig`) and attach per-call authentication credentials or mTLS client certificates.

---

## 5. Software Composition & Cryptography Analysis

### 5.1 All-Zero Key Fallback in Compliance Encryption Utility
- **Vulnerability:** `EncryptionUtil` in `usora-compliance-service` defaults to an all-zero 256-bit AES key (`new byte[32]`) if the environment variable `COMPLIANCE_ENCRYPTION_KEY` is undefined.
- **Evidence:** `spring-boot-services/usora-compliance-service/src/main/java/com/usora/compliance/util/EncryptionUtil.java`.
- **Impact:** KYC evidence uploaded during compliance checks is encrypted at rest using a publicly known zero key, compromising the confidentiality of sensitive identity documents (e.g. passport images, driver's licenses) stored on disk or object storage.
- **Remediation:** Enforce a fail-fast startup check that halts application startup if `COMPLIANCE_ENCRYPTION_KEY` is missing or has insufficient entropy.

### 5.2 Forensic Detection Name Misrepresentation
- **Vulnerability:** Document processing module `usora-document-processor/src/validation/authenticity.rs` defines functions named `detect_uv_fluorescence` and `detect_ir_absorption` that execute basic RGB color variance and grayscale heuristics on visible-light images.
- **Impact:** Discrepancy between function naming and actual capability. Claiming physical UV/IR forensic verification to API consumers and auditors when executing visible-light heuristics creates compliance risks during regulatory audits.
- **Remediation:** Relabel methods to `heuristic_visible_uv_spectrum` and `heuristic_visible_ir_spectrum`, capping confidence scores at `0.3`–`0.4` and documenting visible-light limitations in output payloads.

---

## 6. Audit Finding Remediation Status Baseline

| Reference | Historical Finding | Current Status | Current Evidence / Location |
|---|---|---|---|
| **§3.1** | Gateway `X-Tenant-ID` header override | **FIXED at Gateway Edge** | `rust-services/usora-api-gateway/src/middleware/tenant.rs:74-124` (JWT claim prioritized; header allowed only with explicit override permission). |
| **§3.2** | AML screening not gating compliance decisions | **FIXED** | `usora-compliance-service/.../DomainService.java:109-155` (AML matches added to violations; fail-closed handling applied). |
| **§3.3** | Dual-auth signature unkeyed hash | **FIXED** | `DomainService.java:257` uses HMAC-SHA256 with injected secret and Merkle tree root. |
| **§3.4** | JWT cache returns expired claims | **FIXED** | `rust-services/usora-api-gateway/src/auth/jwt.rs:64-76` (Re-checks `claims.exp > now` on hit and evicts expired items). |
| **§3.5** | MRZ `<` filler character auto-pass | **FIXED** | `usora-document-processor/.../mrz.rs:24-32` (Strict MRZ checksum verification enforced). |
| **§3.6** | Misleading forensic check names | **MITIGATED (Residual P2)** | Confidence capped at `0.4` with heuristic labeling; method names remain visible-light indicators. |
| **§3.7** | Outbound REST client SSRF | **FIXED** | `usora-integration-service/.../RestClient.java` calls `EgressUrlGuard` to validate destination IP/domain safety. |
| **§3.8** | Dead client stubs return `true` | **FIXED** | `usora-tenant-service/.../GrpcClient.java` throws `UnsupportedOperationException`. |
| **New P0** | Gateway empty JWKS map | **OPEN** | `rust-services/usora-api-gateway/src/auth/jwt.rs` (`update_jwks` uncalled). |
| **New P0** | Default HMAC secret in notification | **OPEN** | `usora-notification-service/.../application.yml` (Hardcoded fallback secret). |
| **New P0** | Downstream Spring `TenantInterceptor` header override | **OPEN** | `usora-audit-service/.../TenantInterceptor.java` (`X-Tenant-ID` overrides JWT context). |
| **New P0** | Terraform malformed endpoint string | **OPEN** | `infrastructure/terraform/modules/vpc/main.tf` (`com.amazonaws..s3`). |

---

## 7. Prioritized Remediation Roadmap

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       REMEDIATION ROADMAP MATRIX                            │
├──────────┬──────────┬──────────────────────┬────────────────────────────────┤
│ Phase    │ Priority │ Security Domain      │ Target Component / Action      │
├──────────┼──────────┼──────────────────────┼────────────────────────────────┤
│ Phase 1  │ Critical │ Auth & Edge          │ API Gateway JWKS Background    │
│          │ (P0)     │                      │ Refresh & Token Validation     │
│ Phase 1  │ Critical │ Access Control       │ Downstream Spring Interceptor  │
│          │ (P0)     │                      │ Header-Trust Removal           │
│ Phase 1  │ Critical │ Infrastructure       │ Terraform Regional Endpoint &  │
│          │ (P0)     │                      │ Environment Naming Fixes       │
│ Phase 1  │ Critical │ Secrets Mgmt         │ Notification Service Default   │
│          │ (P0)     │                      │ Secret Removal & Fail-Fast     │
│ Phase 2  │ High     │ Control Plane        │ Spring gRPC @GrpcService Impls │
│          │ (P1)     │                      │ & Tonic TLS/mTLS Configs       │
│ Phase 2  │ High     │ Cryptography         │ Compliance Encryption Key      │
│          │ (P1)     │                      │ Fail-Fast & RDS Policy Fix     │
│ Phase 3  │ Medium   │ Network & Forensic   │ Strict CORS Origin Lists &     │
│          │ (P2)     │                      │ Visible-Light Forensic Labels  │
└──────────┴──────────┴──────────────────────┴────────────────────────────────┘
```

### Phase 1: Critical Security & Infrastructure Fixes (Immediate)
1. **API Gateway JWKS Refresh:**
   - Wire a background Tokio task in `usora-api-gateway/src/main.rs` that polls `/oauth2/jwks` from `usora-identity-service` at startup and periodically updates `JwtValidator::update_jwks`.
   - Configure explicit `JWT_ISSUER` and `JWT_AUDIENCE` checks in `auth/jwt.rs`.
2. **Remove Downstream Header Override:**
   - Refactor `TenantInterceptor.java` across all 7 Spring Boot services (`audit`, `core`, `identity`, `notification`, `compliance`, `integration`, `tenant`) to extract tenant context exclusively from validated JWT claims. Disallow raw `X-Tenant-ID` header overrides.
3. **Fix Terraform Modules:**
   - Update `infrastructure/terraform/modules/vpc/main.tf` to interpolate `${data.aws_region.current.name}` in all VPC endpoint service names.
   - Update `infrastructure/terraform/modules/rds/main.tf`, `elasticache/main.tf`, and `msk/main.tf` to prepend `${var.environment}` to all resource identifiers, subnet groups, and security groups.
4. **Harden Notification JWT Secret:**
   - Remove `defaultSecretKeyMustBeOverriddenInProduction` from `usora-notification-service/src/main/resources/application.yml`.
   - Add a startup validator in `SecurityConfig.java` that throws a `FatalBeanException` if `JWT_SECRET` is unset or matches development default values.

### Phase 2: Control Plane & Network Hardening
1. **Implement Spring gRPC Handlers:**
   - Implement `@GrpcService` handler classes extending generated Protobuf gRPC stubs in `usora-tenant-service`, `usora-identity-service`, `usora-audit-service`, `usora-compliance-service`, and `usora-notification-service`.
2. **Enable gRPC mTLS:**
   - Configure `rust-services/usora-api-gateway/src/grpc/mod.rs` to load client certificates (`ClientTlsConfig`) for inter-service gRPC connections.
3. **Fail-Fast Compliance Encryption Key:**
   - Enforce non-empty `COMPLIANCE_ENCRYPTION_KEY` verification at startup in `usora-compliance-service`.

### Phase 3: Fine-Grained Security & Documentation Cleanliness
1. **Restrict CORS Origins:**
   - Replace `CorsLayer::new().allow_origin(Any)` in Axum routes with explicit origin allowlists configured per environment.
2. **Clarify Forensic Heuristics:**
   - Update `usora-document-processor/src/validation/authenticity.rs` docstrings and output fields to clearly indicate visible-light spectrum heuristics rather than physical hardware-based UV/IR signal analysis.

---

## 8. Conclusion

While USORA features a highly performant and scalable polyglot architecture, critical flaws in the API gateway authentication lifecycle, downstream tenant context resolution, and Terraform infrastructure configurations compromise platform security and deployability. Executing the Phase 1 remediation items defined in this review will resolve total authentication outages, enforce zero-trust tenant boundaries, and ensure reliable multi-environment infrastructure provisioning.
