# USORA KYC Platform — Consolidated Enterprise Security, Reliability & Infrastructure Review

**Author:** Jules, Principal Security & Infrastructure Engineer
**Date:** August 21, 2026
**Classification:** Confidentially Restricted — Internal Engineering Only
**Target Architecture:** Rust Axum/Tokio API Gateway + 3 Rust Compute Engines + 7 Java Spring Boot 3.4.0 Orchestration Services

---

## 1. Executive Summary

This document presents a comprehensive, multi-dimensional security, reliability, and infrastructure audit of the **USORA KYC Platform**. USORA is a high-performance, polyglot compliance and identity verification system designed for multi-tenant, highly regulated enterprise environments. To support SOC 2 Type II, GDPR, EU AML5/AML6, and ISO 27001 compliance standards, maintaining zero-trust architecture, strict tenant isolation, cryptographic integrity, robust network topologies, and hardened container packaging is essential.

Our automated scanning, manual codebase audits, and architectural deep-dives have evaluated both the **application code** (e.g., edge gateway authentication, JWT secret handling, tenant context isolation, cryptographic key management) and the **infrastructure-as-code / orchestration layers** (e.g., Terraform module endpoint interpolations, Kubernetes network egress policies, Helm chart definitions, and CI/CD workflow security).

This consolidated review establishes a single, authoritative, and actionable security posture evaluation and remediation roadmap for the platform.

---

## 2. Infrastructure & Orchestration Security (Terraform, Kubernetes & Helm)

The USORA deployment infrastructure utilizes Terraform for AWS cloud resource management (VPC, EKS, RDS, ElastiCache, MSK), Helm charts for microservice deployments, and Kubernetes network policies for cluster security.

### 2.1 Regional Endpoint Interpolations in Terraform VPC Module
- **Vulnerability / Finding:** In `infrastructure/terraform/modules/vpc/main.tf` (lines 239–303), regional AWS service names for private VPC Endpoints are missing the region interpolation placeholder (e.g., `service_name = "com.amazonaws..s3"`).
- **Impact:** Failure to properly interpolate the region variable causes Terraform provisioning failures or fallback to public internet routing for S3, ECR, and DynamoDB traffic, bypassing the private AWS backbone and violating zero-trust networking requirements.
- **Remediation:** Ensure proper data source regional interpolation across all endpoint definitions:
  ```hcl
  service_name = "com.amazonaws.${data.aws_region.current.name}.s3"
  ```

### 2.2 IAM Monitoring Policy ARN in RDS Module
- **Vulnerability / Finding:** In `infrastructure/terraform/modules/rds/main.tf` (line 286), the IAM policy ARN for Enhanced Monitoring uses an invalid syntax (`arn::iam::aws:policy/...`).
- **Impact:** Terraform apply fails during database cluster setup due to malformed ARN validation errors, blocking enhanced monitoring provisioning.
- **Remediation:** Correct the partition syntax using variable interpolation:
  ```hcl
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
  ```

### 2.3 Resource Naming & Multi-Environment Isolation
- **Vulnerability / Finding:** Static suffix identifiers in Terraform modules (e.g., `Name = "-vpc"`, `identifier = "-db-primary"`) omit environment prefix variables.
- **Impact:** Deploying dev, staging, or production stacks into the same AWS account causes resource collisions, configuration overwrites, or accidental resource destruction.
- **Remediation:** Prefix all Terraform resource tags and identifiers with `${var.environment}`.

### 2.4 Kubernetes Network Policies & Database Egress Isolation
- **Vulnerability / Finding:** `infrastructure/k8s/base/network-policies.yml` contains overly permissive `0.0.0.0/0` egress rules for database/cache ports (`5432`, `6379`).
- **Impact:** Compromised pod instances could establish unauthorized external egress connections on database ports to exfiltrate data.
- **Remediation:** Restrict egress rules to internal VPC/subnet CIDR ranges (e.g., `10.2.0.0/16`) and restrict inter-service ingress using explicit label-based pod selectors.

---

## 3. Application Security & Cryptography

### 3.1 Gateway Authentication & Token Verification
- **Vulnerability / Finding:** The Rust Axum API Gateway (`usora-api-gateway`) provides edge token extraction and LRU caching for JWT validation.
- **Impact:** If JWKS key synchronization is interrupted or if key rotation occurs without updating gateway memory, legitimate incoming requests receive `401 Unauthorized` responses.
- **Remediation:** Ensure continuous background JWKS polling from `usora-identity-service` and enforce strict expiration check (`claims.exp > now`) on all cached token validation decisions.

### 3.2 Notification Service JWT Secret Configuration
- **Vulnerability / Finding:** `usora-notification-service` contains a default secret fallback (`defaultSecretKeyMustBeOverriddenInProduction`) in `application.yml`.
- **Impact:** If deployed to production without supplying `JWT_SECRET`, any actor with knowledge of the default string can forge valid JWT signatures and access notification endpoints.
- **Remediation:** Implement a fail-fast application startup check in `SecurityConfig.java` to abort process initialization if `JWT_SECRET` is unset or matches the default development fallback.

### 3.3 Downstream Tenant Header Isolation
- **Vulnerability / Finding:** `TenantInterceptor.java` across Spring Boot microservices extracts the `X-Tenant-ID` HTTP header to set `TenantContext`.
- **Impact:** Directly trusting the `X-Tenant-ID` header allows potential tenant context spoofing if internal microservices accept requests bypassing the edge API gateway.
- **Remediation:** Standardize all `TenantInterceptor` implementations to derive tenant context strictly from verified JWT claims first, allowing HTTP header overrides only for explicitly trusted super-admin service accounts.

### 3.4 Encryption Key Entropy in Compliance Service
- **Vulnerability / Finding:** `EncryptionUtil.java` in `usora-compliance-service` falls back to a zeroed-out byte array (`new byte[32]`) when `COMPLIANCE_ENCRYPTION_KEY` is not present in the environment.
- **Impact:** Sensitive compliance and KYC data encrypted at rest would use a static, known zero-key in fallback scenarios.
- **Remediation:** Throw an explicit `IllegalStateException` on service startup if `COMPLIANCE_ENCRYPTION_KEY` is missing, blank, or shorter than 32 bytes.

---

## 4. gRPC Control Plane & Network Security

### 4.1 Internal gRPC Communication
- **Vulnerability / Finding:** Microservices define gRPC proto specifications (`tenant_service.proto`, `audit_service.proto`, `compliance_service.proto`) for internal control plane RPCs.
- **Impact:** Missing gRPC service implementations or plaintext channel transport exposes internal RPC traffic to potential interception or communication errors.
- **Remediation:** Ensure all gRPC endpoints implement generated proto handlers and enforce TLS/mTLS encryption for inter-service gRPC communication channels.

---

## 5. Compute Layer Reliability (Rust Engines)

### 5.1 FFI Safety in Native Processing Engines
- **Vulnerability / Finding:** `usora-document-processor` and `usora-face-matching-engine` rely on C/C++ FFI bindings (Leptonica, Tesseract, OpenCV, FAISS).
- **Impact:** A segmentation fault or uncaught native panic within C/C++ FFI code can terminate the host Rust OS process.
- **Remediation:** Isolate native FFI parsing into separate worker subprocesses or wrapped safe execution sandboxes with input pre-validation (image bounds, mime types) prior to native library processing.

### 5.2 Dynamic Rule Execution in Risk Scoring Engine
- **Vulnerability / Finding:** `usora-risk-scoring-engine` evaluates dynamic risk scripts using the `Rhai` embedded scripting engine.
- **Impact:** Unrestricted script execution could lead to resource exhaustion (CPU/Memory) via deep recursion or non-terminating loops.
- **Remediation:** Configure `Rhai` with strict memory limits, execution statement limits, and disabled file/system access.

---

## 6. Software Packaging & CI/CD Pipelines

### 6.1 Microservice Helm Chart Deployment Standardization
- **Vulnerability / Finding:** Enterprise deployment standardization across all 11 services requires individual Helm chart configurations under `infrastructure/helm/`.
- **Impact:** Unconfigured or mismatched Helm charts result in deployment failures in Kubernetes environments.
- **Remediation:** Helm charts for all 11 services (`usora-api-gateway`, `usora-document-processor`, `usora-face-matching-engine`, `usora-risk-scoring-engine`, `usora-identity-service`, `usora-core-service`, `usora-tenant-service`, `usora-compliance-service`, `usora-audit-service`, `usora-notification-service`, `usora-integration-service`) are fully implemented with explicit resource limits, liveness/readiness probes, and secret bindings.

### 6.2 CI/CD Security Workflow & Secret Protection
- **Vulnerability / Finding:** GitHub Actions workflows (`.github/workflows/ci-cd.yml`, `security-scan.yml`, `build-and-test.yml`) perform automated builds, vulnerability scanning, and Slack notifications.
- **Impact:** External PR runs or unconfigured environments without Slack webhook secrets can fail CI pipeline runs.
- **Remediation:** Guard all notification and deployment workflow steps with `if: secrets.SECURITY_SLACK_WEBHOOK_URL != ''` to guarantee reliable CI pipeline execution.

---

## 7. Status of Prior Audit Findings

| Prior Finding | Original Severity | Current Status | Verification Context / Evidence |
|---|---|---|---|
| **SSRF in Outbound REST Client** | High | **RESOLVED** | `integration/.../RestClient.java` enforces `EgressUrlGuard.assertSafeDestination()`, blocking RFC1918, loopback, and cloud metadata IPs. |
| **MRZ Checksum Parsing Bug** | High | **RESOLVED** | `document-processor/.../mrz.rs` implements weighted ICAO 9303 checksum validation and handles `<` filler edge-cases. |
| **JWT Cache Expiry Bypass** | Critical | **RESOLVED** | `api-gateway/.../jwt.rs` validates `claims.exp > now` on LRU cache hits, evicting expired tokens immediately. |
| **AML Screening Failure Handling** | Critical | **RESOLVED** | `compliance/.../DomainService.java` fails-closed on gRPC or downstream service errors during AML checks. |
| **Helm Charts for All Services** | High | **RESOLVED** | Helm charts created for all 11 Rust and Java microservices under `infrastructure/helm/`. |

---

## 8. Remediation Roadmap & Prioritization Backlog

| Phase | Priority | Domain | Component | Recommended Remediation Action |
|---|---|---|---|---|
| **Phase 1** | **P0 - Critical** | Identity & Access | Gateway / Identity | Continuous JWKS synchronization & strict RS256 token validation. |
| **Phase 1** | **P0 - Critical** | Secrets & Auth | `notification-service` | Fail-fast startup if default JWT secret key is used in production. |
| **Phase 1** | **P0 - Critical** | Infrastructure | Terraform VPC / RDS | Fix region interpolation in VPC endpoints and IAM ARN partition in RDS. |
| **Phase 1** | **P0 - Critical** | Multi-Tenancy | Spring Microservices | Enforce JWT claim tenant derivation over untrusted `X-Tenant-ID` headers. |
| **Phase 2** | **P1 - High** | Cryptography | `compliance-service` | Enforce explicit key length validation for AES-256 compliance storage. |
| **Phase 2** | **P1 - High** | Control Plane | Gateway & Spring | Enforce TLS/mTLS encryption for inter-service gRPC communication. |
| **Phase 2** | **P1 - High** | CI/CD | GitHub Workflows | Guard Slack notification steps against missing secret failures. |
| **Phase 3** | **P2 - Medium** | Compute Safety | Compute Engines | Isolate native C/C++ FFI calls into worker pools with image input sanitization. |

---

## 9. Conclusion

The USORA KYC Platform features a modern, enterprise-grade polyglot microservice architecture. The platform's security posture has been significantly improved through automated egress controls, MRZ validation hardening, JWT expiration caching fixes, and complete Helm chart packaging for all 11 services.

Addressing the remaining Phase 1 items—specifically enforcing JWT claim-based tenant isolation, eliminating default secret fallbacks, and correcting Terraform regional interpolations—will ensure full compliance with SOC 2 Type II, ISO 27001, and GDPR zero-trust requirements.

*Report compiled by: Jules, Principal Security & Infrastructure Engineer.*
