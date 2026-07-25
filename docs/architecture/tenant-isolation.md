# USORA Multi-Tenant Isolation Architecture

> **Version:** 1.0.0  
> **Last Updated:** 2026-07-25  
> **Classification:** Internal — Confidential  
> **Owner:** USORA Architecture Team  
> **Status:** Approved

---

## Table of Contents

1. [Overview](#1-overview)
2. [Design Principles](#2-design-principles)
3. [Layer 1 — Network Isolation](#3-layer-1--network-isolation)
4. [Layer 2 — Application Isolation](#4-layer-2--application-isolation)
5. [Layer 3 — Data Isolation](#5-layer-3--data-isolation)
6. [Tenant Lifecycle](#6-tenant-lifecycle)
7. [Isolation Verification](#7-isolation-verification)
8. [Threat Model](#8-threat-model)
9. [Appendix: References](#9-appendix-references)

---

## 1. Overview

USORA is a multi-tenant SaaS KYC platform serving thousands of tenants across regulated industries including banking, fintech, insurance, gaming, and healthcare. Each tenant processes sensitive Personally Identifiable Information (PII), biometric data, and government-issued identity documents. Isolation between tenants is not merely a business requirement — it is a regulatory mandate under GDPR, AML5/6, eIDAS, SOC 2, ISO 27001, and KSA/KFS.

This document describes the complete multi-tenant isolation architecture, enforced at three distinct layers:

| Layer | Scope | Enforcement Mechanism |
|-------|-------|----------------------|
| **Layer 1: Network** | Infrastructure | VPCs, Kubernetes namespaces, NetworkPolicies, Istio AuthorizationPolicies, mTLS |
| **Layer 2: Application** | Runtime | JWT claims, TenantContext, RBAC, rate limiting, circuit breakers |
| **Layer 3: Data** | Storage | Schema-per-tenant, RLS, key namespacing, topic isolation, prefix-based IAM |

Isolation is enforced at every layer simultaneously, providing defense in depth. A failure at any single layer does not result in cross-tenant data exposure.

### 1.1 Isolation Guarantees

| Guarantee | Description | Regulatory Basis |
|-----------|-------------|------------------|
| **Data Segregation** | Tenant A can never read, write, or modify Tenant B's data | GDPR Art. 28, SOC 2 CC6.1 |
| **Resource Fairness** | No single tenant can consume resources that degrade other tenants' service | SOC 2 CC7.2 |
| **Performance Isolation** | A traffic spike for one tenant does not cause latency for others | SLA commitments |
| **Audit Separation** | Audit trails are scoped per tenant and tamper-proof | GDPR Art. 5(2), AML Art. 40 |
| **Cryptographic Isolation** | Encryption keys are unique per tenant; compromise of one does not affect others | SOC 2 CC6.7 |

### 1.2 Tenant Model

```
┌───────────────────────────────────────────────────────────┐
│                      USORA PLATFORM                         │
│                                                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  │
│  │ Tenant A │  │ Tenant B │  │ Tenant C │  │ Tenant D │  │
│  │ (Fintech)│  │  (Bank)  │  │ (Insure) │  │  (Gaming)│  │
│  │  200K    │  │   5M     │  │  500K    │  │   100K   │  │
│  │ users/mo │  │ users/mo │  │ users/mo │  │ users/mo │  │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘  │
│       │              │              │              │        │
│       ▼              ▼              ▼              ▼        │
│  ┌────────────────────────────────────────────────────┐    │
│  │              ISOLATION BOUNDARIES                    │    │
│  │  • Separate PostgreSQL schemas                      │    │
│  │  • Separate K8s namespaces                          │    │
│  │  • Separate Vault secret paths                      │    │
│  │  • Separate S3 prefixes                             │    │
│  │  • Separate ES indices                              │    │
│  │  • Separate ClickHouse tables                       │    │
│  └────────────────────────────────────────────────────┘    │
└───────────────────────────────────────────────────────────┘
```

---

## 2. Design Principles

### 2.1 Defense in Depth

No single isolation mechanism is trusted in isolation. Every access path is protected by at least three independent control layers:
- Network policy denies traffic by default
- Application-layer authorization validates every request
- Data-layer access controls enforce per-tenant boundaries at the storage engine level

If a tenant's API credentials are compromised, the attacker still faces network isolation (cannot reach other tenants' pods), application authorization (JWT validation rejects cross-tenant access), and data isolation (database roles prevent cross-schema queries).

### 2.2 Least Privilege

Every component, service, and user receives the minimum permissions required to perform its function:
- Database roles are scoped to a single tenant schema
- Kubernetes ServiceAccounts are namespaced and bound to minimal RBAC roles
- IAM policies for S3 are scoped to specific prefixes
- Kafka ACLs restrict topic access per consumer group
- Vault policies enforce path-level isolation

### 2.3 Data Sovereignty

Tenant data never leaves its jurisdictional boundary without explicit consent and contractual agreement:
- Data residency is enforced at the storage layer (region-scoped S3 buckets, regional PostgreSQL clusters)
- Tenant metadata includes `data_region` field that determines which region(s) may process its data
- Cross-region data movement requires explicit compliance approval and audit trail
- Backup and disaster recovery data remains in the same region as primary data

### 2.4 Compliance-by-Design

Regulatory requirements are encoded into the isolation architecture rather than bolted on afterward:
- Schema-per-tenant directly satisfies GDPR data segregation requirements
- Per-tenant encryption keys satisfy SOC 2 cryptographic isolation requirements
- Immutable audit trails with hash-chain integrity satisfy AML record-keeping requirements
- Tenant lifecycle hooks enforce data retention and deletion policies mandated by GDPR

### 2.5 Operational Simplicity

Isolation mechanisms must not create unsustainable operational burden:
- Tenant provisioning is fully automated (no manual database administration)
- Monitoring and alerting are tenant-aware (metrics tagged with `tenant_id`)
- Incident response procedures account for tenant isolation (per-tenant circuit breakers, canary deployments per tenant)
- Runbooks exist for tenant lifecycle events (provisioning, offboarding, suspension)

---

## 3. Layer 1 — Network Isolation

### 3.1 VPC Architecture

All USORA infrastructure runs within isolated Virtual Private Clouds (VPCs). Each region has its own VPC with no peering between regions. Cross-region traffic passes through encrypted VPN tunnels or Kafka MirrorMaker 2 connections.

```
┌─────────────────────────────────────────────────────────────┐
│                      AWS CLOUD                                │
│                                                               │
│  ┌──────────────────────────┐  ┌──────────────────────────┐  │
│  │     Region: us-east-1    │  │     Region: eu-west-1    │  │
│  │                          │  │                          │  │
│  │  ┌─────────────────┐     │  │  ┌─────────────────┐     │  │
│  │  │    VPC: usora    │     │  │  │    VPC: usora    │     │  │
│  │  │  10.0.0.0/16     │     │  │  │  10.1.0.0/16     │     │  │
│  │  │                  │     │  │  │                  │     │  │
│  │  │  Public subnets  │     │  │  │  Public subnets  │     │  │
│  │  │  • NLB / ALB     │     │  │  │  • NLB / ALB     │     │  │
│  │  │  • NAT gateways  │     │  │  │  • NAT gateways  │     │  │
│  │  │                  │     │  │  │                  │     │  │
│  │  │  Private subnets │     │  │  │  Private subnets │     │  │
│  │  │  • EKS worker    │     │  │  │  • EKS worker    │     │  │
│  │  │    nodes         │     │  │  │    nodes         │     │  │
│  │  │  • RDS / Aurora  │     │  │  │  • RDS / Aurora  │     │  │
│  │  │  • ElastiCache   │     │  │  │  • ElastiCache   │     │  │
│  │  │  • MSK           │     │  │  │  • MSK           │     │  │
│  │  │                  │     │  │  │                  │     │  │
│  │  │  Isolated subs   │     │  │  │  Isolated subs   │     │  │
│  │  │  • VPC endpoint  │     │  │  │  • VPC endpoint  │     │  │
│  │  │    only (S3,     │     │  │  │    only (S3,     │     │  │
│  │  │    DynamoDB)     │     │  │  │    DynamoDB)     │     │  │
│  │  └─────────────────┘     │  │  └─────────────────┘     │  │
│  └──────────────────────────┘  └──────────────────────────┘  │
│                                                               │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │  VPN / DX Connection  (encrypted, dedicated)             │ │
│  └──────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────┘
```

- All application workloads run in **private subnets** only — no public IP addresses
- Outbound internet access is through NAT gateways (for updates, external APIs)
- Access to AWS services (S3, DynamoDB, SQS) is through VPC Endpoints (Gateway and Interface types)
- Security Groups enforce instance-level ingress/egress rules with least-privilege

### 3.2 Kubernetes Namespaces

Each tenant receives a dedicated Kubernetes namespace:

```
┌───────────────────────────────────────────────────────────────┐
│                   EKS CLUSTER (usora-prod)                      │
│                                                                 │
│  ┌─────────────────────┐                                        │
│  │  usora-system        │  System components (cert-manager,    │
│  │                      │  ingress-nginx, external-dns, etc.) │
│  └─────────────────────┘                                        │
│                                                                 │
│  ┌─────────────────────┐                                        │
│  │  usora-gateway       │  Gateway service (shared)             │
│  │  (shared)            │  - Tenant resolution happens here     │
│  └─────────────────────┘                                        │
│                                                                 │
│  ┌─────────────────────┐  ┌─────────────────────┐               │
│  │  usora-orchestration│  │  usora-compute       │               │
│  │  (shared)           │  │  (shared)            │               │
│  └─────────────────────┘  └─────────────────────┘               │
│                                                                 │
│  ┌─────────────────────┐  ┌─────────────────────┐               │
│  │  usora-data          │  │  usora-security      │               │
│  │  (shared)            │  │  (shared)            │               │
│  └─────────────────────┘  └─────────────────────┘               │
│                                                                 │
│  ┌─────────────────────┐  ┌─────────────────────┐               │
│  │  tenant-acme         │  │  tenant-globalbank  │               │
│  │  (tenant namespace)  │  │  (tenant namespace) │               │
│  │                      │  │                      │              │
│  │  • tenant-specific   │  │  • tenant-specific  │               │
│  │    sidecars          │  │    sidecars          │               │
│  │  • tenant webhook    │  │  • tenant webhook    │               │
│  │    receiver          │  │    receiver          │               │
│  │  • per-tenant app    │  │  • per-tenant app    │               │
│  │    instances         │  │    instances         │               │
│  └─────────────────────┘  └─────────────────────┘               │
└─────────────────────────────────────────────────────────────────┘
```

**Namespace naming convention:** `tenant-{tenant_id}` where `tenant_id` is a lowercase, URL-safe, unique identifier assigned at provisioning time (e.g., `tenant-acme`, `tenant-globalbank`).

### 3.3 Kubernetes NetworkPolicies

NetworkPolicies enforce micro-segmentation at the pod level using Cilium (eBPF-based) for high-performance enforcement.

**Default-deny policy applied to all namespaces:**

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-all
spec:
  podSelector: {}
  policyTypes:
  - Ingress
  - Egress
```

**Tenant namespace ingress rules:**

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-from-gateway
  namespace: tenant-acme
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/component: webhook
  ingress:
  - from:
    - namespaceSelector:
        matchLabels:
          kubernetes.io/metadata.name: usora-gateway
    - podSelector:
        matchLabels:
          app.kubernetes.io/name: gateway
    ports:
    - protocol: TCP
      port: 8443
```

**Cross-namespace traffic is strictly forbidden except for explicitly allowed paths:**

| Source Namespace | Dest Namespace | Protocol | Port | Purpose |
|-----------------|---------------|----------|------|---------|
| `usora-gateway` | `usora-orchestration` | TCP | 9090 | gRPC orchestration calls |
| `usora-gateway` | `usora-compute` | TCP | 9091 | Health/metrics bypass |
| `usora-orchestration` | `usora-compute` | TCP | 9090 | gRPC compute calls |
| `usora-orchestration` | `usora-data` | TCP | 5432 | PostgreSQL |
| `usora-orchestration` | `usora-data` | TCP | 6379 | Redis |
| `usora-orchestration` | `usora-data` | TCP | 9092 | Kafka |
| `usora-gateway` | `usora-data` | TCP | 6379 | Redis (session, rate-limit) |
| `usora-compute` | `usora-data` | TCP | 9092 | Kafka (result publishing) |
| `usora-orchestration` | `usora-security` | TCP | 8200 | Vault |
| All namespaces | `usora-system` | TCP | 53 | DNS (coreDNS) |

### 3.4 Istio AuthorizationPolicies

Beyond Kubernetes NetworkPolicies, Istio AuthorizationPolicies enforce identity-based access control at Layer 7:

```yaml
apiVersion: security.istio.io/v1beta1
kind: AuthorizationPolicy
metadata:
  name: deny-cross-namespace
  namespace: usora-orchestration
spec:
  action: DENY
  rules:
  - from:
    - source:
        notNamespaces: ["usora-gateway", "usora-orchestration"]
```

**Principal Authorization (SPIFFE-based):**

```yaml
apiVersion: security.istio.io/v1beta1
kind: AuthorizationPolicy
metadata:
  name: gateway-to-orchestration
  namespace: usora-orchestration
spec:
  selector:
    matchLabels:
      app: orchestration
  action: ALLOW
  rules:
  - from:
    - source:
        principals: ["cluster.local/ns/usora-gateway/sa/gateway"]
    to:
    - operation:
        methods: ["POST"]
        paths: ["/usora.orchestration.v1.OrchestrationService/*"]
```

### 3.5 mTLS with SPIFFE Identities

All service-to-service communication uses mutual TLS with X.509 certificates issued by SPIFFE/SPIRE:

| Property | Value |
|----------|-------|
| Identity format | `spiffe://usora.io/{namespace}/{service-account}` |
| Certificate rotation | Every 24 hours (auto-renewed by SPIRE agent) |
| Cipher suites | `TLS_AES_256_GCM_SHA384` only (TLS 1.3) |
| Validation | SPIFFE ID extracted from X.509 SAN, verified against authorization policy |
| Enforcement | Istio sidecar proxy terminates mTLS; applications communicate over plain localhost |

**SPIFFE identities in use:**

| Service | SPIFFE ID |
|---------|-----------|
| Gateway | `spiffe://usora.io/usora-gateway/sa/gateway` |
| Orchestration | `spiffe://usora.io/usora-orchestration/sa/orchestration` |
| Compute Document | `spiffe://usora.io/usora-compute/sa/compute-document` |
| Compute Biometric | `spiffe://usora.io/usora-compute/sa/compute-biometric` |
| Compute Risk | `spiffe://usora.io/usora-compute/sa/compute-risk` |
| Compute Fraud | `spiffe://usora.io/usora-compute/sa/compute-fraud` |

---

## 4. Layer 2 — Application Isolation

### 4.1 JWT Claims and Tenant Validation

Every API request carries a JSON Web Token (JWT) with the following tenant-related claims:

```json
{
  "iss": "usora.auth.v1",
  "sub": "user_abc123",
  "tid": "acme123",
  "roles": ["kyc:submit", "kyc:read"],
  "permissions": ["verification:create", "verification:read"],
  "client_id": "client_xyz789",
  "ip_allowlist": ["203.0.113.0/24"],
  "iat": 1721884800,
  "exp": 1721888400
}
```

**Tenant ID validation is performed at every layer:**

1. **Gateway:** Extracts `tid` claim from JWT; verifies it matches the tenant resolved from subdomain or `X-Tenant-ID` header
2. **Orchestration:** Receives tenant context via gRPC metadata; validates it matches the request payload
3. **Compute:** Receives tenant context in task message; validates before processing
4. **Data layer:** Tenant context is used to select the correct schema, key prefix, or index

**Validation rules enforced at the Gateway:**

| Rule | Description | Enforcement |
|------|-------------|-------------|
| Tenant consistency | `tid` in JWT must match `X-Tenant-ID` header | Request rejected with 401 if mismatch |
| Tenant active status | Tenant must be in `ACTIVE` state | Request rejected with 403 if suspended/offboarded |
| IP allowlist | Request source IP must be in tenant's allowlist | Request rejected with 403 if not allowed |
| Rate limit check | Tenant must not exceed rate limit | Request queued/rejected with 429 if exceeded |
| Feature flag | Tenant must be enabled for requested feature | Request rejected with 404 if not enabled |

### 4.2 TenantContext Propagation

A `TenantContext` object is propagated through the entire request lifecycle.

**Java (Orchestration) — ThreadLocal with Virtual Threads:**

```java
public class TenantContext {
    private static final InheritableThreadLocal<String> currentTenant =
        new InheritableThreadLocal<>();

    public static void set(String tenantId) {
        currentTenant.set(tenantId);
    }

    public static String get() {
        return currentTenant.get();
    }

    public static void clear() {
        currentTenant.remove();
    }
}
```

Virtual Threads in Java 21 automatically inherit `ThreadLocal` values, ensuring tenant context is preserved across async boundaries without explicit propagation.

**Rust (Gateway) — Tower Layer:**

```rust
#[derive(Clone)]
pub struct TenantContextLayer {
    state: Arc<TenantContextState>,
}

impl<S> Layer<S> for TenantContextLayer {
    type Service = TenantContextService<S>;

    fn layer(&self, service: S) -> Self::Service {
        TenantContextService {
            inner: service,
            state: self.state.clone(),
        }
    }
}
```

**Propagation across service boundaries:**

| Boundary | Propagation Mechanism |
|----------|----------------------|
| HTTP → Gateway | `X-Tenant-ID` header |
| Gateway → Orchestration | gRPC metadata (`x-tenant-id`) |
| Orchestration → Compute | Kafka message key (`{tid}:{entity}`) |
| Orchestration → Database | `SET app.tenant_id = '...'` session variable |
| Any → Audit | `tenant_id` field in audit log record |

### 4.3 RBAC Roles and Permissions

All roles are scoped to a tenant. A user with `tenant:admin` in Tenant A has no access to Tenant B.

**Built-in role hierarchy:**

```
super_admin (platform-wide, restricted to USORA staff)
  └── tenant_admin (per-tenant)
       ├── case_manager
       │    ├── reviewer
       │    │    └── viewer
       └── compliance_officer
            └── auditor (read-only)
```

**Permission definitions:**

| Permission | Action | Scope |
|-----------|--------|-------|
| `kyc:submit` | Submit a new KYC verification | Per-tenant |
| `kyc:read` | Read verification results and details | Per-tenant |
| `kyc:write` | Update verification data | Per-tenant |
| `case:manage` | Escalate, assign, and resolve cases | Per-tenant |
| `case:read` | View case details | Per-tenant |
| `document:read` | View uploaded documents | Per-tenant |
| `document:delete` | Delete documents (GDPR) | Per-tenant |
| `tenant:admin` | Manage tenant configuration, users, webhooks | Per-tenant |
| `webhook:manage` | Create, update, delete webhook endpoints | Per-tenant |
| `analytics:read` | View tenant analytics and reports | Per-tenant |
| `audit:read` | View tenant audit log | Per-tenant |
| `compliance:manage` | Manage compliance rules and thresholds | Per-tenant |

**OPA/Rego policy example:**

```rego
package usora.authz

default allow = false

allow {
    input.tenant_id == input.jwt.tid
    input.action == input.jwt.permissions[_]
    input.ip_address == input.tenant.ip_allowlist[_]
    input.tenant.status == "ACTIVE"
}
```

### 4.4 Rate Limiting

Rate limiting is enforced per `(tenant, endpoint, client)` tuple using a Redis-backed sliding window token bucket algorithm.

**Rate limit configuration (per tenant):**

| Endpoint Group | Default Limit | Burst | Window |
|--------------|--------------|-------|--------|
| `kyc:submit` | 100 req/s | 150 | 1 second |
| `kyc:read` | 500 req/s | 750 | 1 second |
| `case:manage` | 50 req/s | 75 | 1 second |
| `webhook:deliver` | 1000 req/s | 1500 | 1 second |
| `analytics:query` | 20 req/s | 30 | 1 second |
| `admin:config` | 10 req/s | 15 | 1 second |

**Rate limit key format:** `tenant:{tid}:rate-limit:{client_id}:{endpoint_group}`

**Enforcement at the Gateway:**

```rust
pub async fn rate_limit_middleware(
    req: Request<Body>,
    next: Next,
) -> Result<Response, StatusCode> {
    let tenant = req.extensions().get::<TenantContext>().unwrap();
    let client = req.extensions().get::<ClientContext>().unwrap();
    let endpoint = req.extensions().get::<EndpointGroup>().unwrap();

    let key = format!("tenant:{}:rate-limit:{}:{}", tenant.id, client.id, endpoint);
    let allowed = redis_token_bucket_check(&key, endpoint.limit(), endpoint.window()).await;

    if !allowed {
        return Err(StatusCode::TOO_MANY_REQUESTS);
    }

    Ok(next.run(req).await)
}
```

### 4.5 Circuit Breakers per Tenant

Circuit breakers prevent a noisy neighbor from degrading service for other tenants. Each upstream service has per-tenant circuit breaker state.

**Circuit breaker configuration:**

| Parameter | Value | Description |
|-----------|-------|-------------|
| `sliding_window_size` | 100 | Number of requests in the sliding window |
| `failure_rate_threshold` | 50% | Percentage of failures to open the circuit |
| `wait_duration_open` | 30s | Time before transitioning to half-open |
| `permitted_calls_in_half_open` | 10 | Requests allowed when half-open |
| `record_exceptions_as_failures` | true | Count exceptions as failures |

**Enforcement:**

```
Gateway → Orchestration: circuit breaker per (tenant_id, method)
Orchestration → Compute: circuit breaker per (tenant_id, worker_type)
Orchestration → Database: connection pool per tenant schema
```

**Alerts:**
- `usora_gateway_circuit_breaker_state{tid="acme123",status="open"} 1` — alerts on open circuit
- `usora_gateway_circuit_breaker_state{tid="acme123",status="half_open"} 1` — monitors recovery
- Aggregated metric: `count(usora_gateway_circuit_breaker_state{status="open"}) by (tid)` — identifies noisy tenants

### 4.6 Request Validation

Every request undergoes tenant ID consistency validation:

**Validation at Gateway:**

1. Extract `X-Tenant-ID` header from request
2. Resolve tenant from JWT `tid` claim
3. Verify both values match (or JWT `tid` is in tenant's allowed list for subdomain-based routing)
4. Verify tenant status is `ACTIVE`
5. Verify request body contains matching `tenant_id` if applicable
6. If validation fails: log security event, increment `usora_gateway_tenant_mismatch_total`, return 401

**Validation at Orchestration (gRPC interceptor):**

```java
@Override
public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

    String tenantId = headers.get(Metadata.Key.of("x-tenant-id", Metadata.ASCII_STRING_MARSHALLER));
    String expectedTenant = TenantContext.get();

    if (tenantId == null || !tenantId.equals(expectedTenant)) {
        call.close(Status.PERMISSION_DENIED
            .withDescription("Tenant ID mismatch"), new Metadata());
        return new ServerCall.Listener<>() {};
    }

    return next.startCall(call, headers);
}
```

---

## 5. Layer 3 — Data Isolation

This is the most critical layer. Data isolation must survive application bugs, misconfigurations, and zero-day exploits in the application layer.

### 5.1 PostgreSQL: Schema-per-Tenant + Row-Level Security

#### 5.1.1 Architecture

Each tenant receives a dedicated PostgreSQL schema within the shared database cluster:

```
┌──────────────────────────────────────────────────────────────┐
│                    PostgreSQL 16 Cluster                        │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │  public schema                                           │ │
│  │  • tenants (master tenant registry)                     │ │
│  │  • global_config (platform-wide settings)               │ │
│  │  • audit_log (cross-tenant audit — limited access)      │ │
│  │  • migration_tracker (Flyway schema version tracking)   │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │  tenant_acme123                                         │ │
│  │  • verifications                                        │ │
│  │  • verification_documents                               │ │
│  │  • cases                                                │ │
│  │  • case_notes                                           │ │
│  │  • webhook_endpoints                                    │ │
│  │  • webhook_deliveries                                   │ │
│  │  • api_keys                                             │ │
│  │  • users                                                │ │
│  │  • user_sessions                                        │ │
│  │  • compliance_rules                                     │ │
│  │  • risk_profiles                                        │ │
│  │  • audit_log (per-tenant)                               │ │
│  │  • flyway_schema_history                                │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │  tenant_globalbank001                                   │ │
│  │  • (identical table structure to tenant_acme123)        │ │
│  └──────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

#### 5.1.2 Schema Creation

Tenant schemas are created dynamically during provisioning:

```sql
CREATE SCHEMA IF NOT EXISTS tenant_acme123;
GRANT USAGE ON SCHEMA tenant_acme123 TO usora_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA tenant_acme123
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO usora_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA tenant_acme123
    GRANT USAGE ON SEQUENCES TO usora_app;

-- Create a dedicated database role for the tenant
CREATE ROLE tenant_acme123_app WITH LOGIN PASSWORD '...' INHERIT;
GRANT USAGE ON SCHEMA tenant_acme123 TO tenant_acme123_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA tenant_acme123 TO tenant_acme123_app;
```

**Flyway migrations** are executed per schema:

```bash
# Each tenant schema gets the same set of migrations applied
flyway -schemas=tenant_acme123 -locations=filesystem:./migrations migrate
flyway -schemas=tenant_globalbank001 -locations=filesystem:./migrations migrate
```

#### 5.1.3 Row-Level Security (RLS)

RLS provides a defense-in-depth layer. Even if a query somehow omits the schema qualifier, RLS policies prevent accessing other tenants' rows:

```sql
-- Enable RLS on all tables
ALTER TABLE tenant_acme123.verifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_acme123.verifications FORCE ROW LEVEL SECURITY;

-- Create RLS policy using session variable
CREATE POLICY tenant_isolation ON tenant_acme123.verifications
    USING (tenant_id = current_setting('app.tenant_id')::text);

-- Application sets session variable on connection
SET app.tenant_id = 'acme123';
```

**Connection pool integration (HikariCP):**

```java
@Bean
public DataSource dataSource() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:postgresql://postgres:5432/usora");
    config.setUsername("usora_app");
    config.setPassword("...");
    config.setMaximumPoolSize(20);  // Per-tenant pool
    config.setConnectionInitSql("SET app.tenant_id = '" + tenantId + "'");
    config.setSchema("tenant_" + tenantId);
    return new HikariDataSource(config);
}
```

#### 5.1.4 Connection Pooling

| Pool Type | Configuration | Purpose |
|-----------|--------------|---------|
| Per-tenant HikariCP pool | Max 20 connections, min 2 | Primary application queries |
| Global PgBouncer pool | Transaction-level pooling, 200 connections | Shared monitoring, admin queries |
| Flyway migration pool | Max 1 connection | Schema migrations (sequential) |

**Connection pool isolation guarantees:**
- Each tenant pool is independent — a leak in Tenant A's pool does not affect Tenant B
- Pool metrics tagged with `tenant_id`: `hikaricp_connections_active{tid="acme123"}`
- Alert when pool utilization >80%: `hikaricp_connections_active / hikaricp_connections_max > 0.8`

#### 5.1.5 Migration Strategy

- All tenants share the same migration scripts (version-controlled in `db/migrations/`)
- Flyway tracks each schema independently in its own `flyway_schema_history` table
- New tenants are provisioned with the latest baseline
- Existing tenants are migrated during maintenance windows (or online via `flyway.migrate()`)
- `UNDO` migrations are explicitly forbidden; rollbacks use forward-only compensating migrations

### 5.2 Redis: Key Namespacing and ACL

#### 5.2.1 Key Naming Convention

All Redis keys follow the format `tenant:{tid}:{type}:{key}`:

| Key Pattern | Example | Purpose |
|------------|---------|---------|
| `tenant:{tid}:session:{session_id}` | `tenant:acme123:session:sess_abc` | User session data |
| `tenant:{tid}:rate-limit:{endpoint}:{client_id}` | `tenant:acme123:rate-limit:kyc:submit:client_xyz` | Rate limit counters |
| `tenant:{tid}:cache:{endpoint}:{hash}` | `tenant:acme123:cache:verification:read:sha256...` | Response cache |
| `tenant:{tid}:feature-flags` | `tenant:acme123:feature-flags` | Tenant configuration |
| `tenant:{tid}:jwt-blacklist:{jti}` | `tenant:acme123:jwt-blacklist:jti_abc` | Token revocation |
| `tenant:{tid}:locks:{resource}` | `tenant:acme123:locks:verification:v_123` | Distributed locks |
| `tenant:{tid}:queue:{priority}` | `tenant:acme123:queue:express` | Task queues |
| `tenant:{tid}:idempotency:{key}` | `tenant:acme123:idempotency:req_abc` | Idempotency keys |

#### 5.2.2 Logical Database Separation

Redis databases are used to separate data types:

| DB | Data Type | Persistence | TTL Policy |
|----|-----------|-------------|------------|
| DB 0 | Session data | RDB + AOF | Session TTL (max 24h) |
| DB 1 | Rate limit counters | None | Sliding window auto-expire |
| DB 2 | Response cache | RDB | Per-endpoint TTL (5-300s) |
| DB 3 | Distributed locks | None | Lease timeout (max 30s) |
| DB 4 | Task queues | AOF | Until consumed |
| DB 5 | Feature flags | RDB | No expiry |
| DB 6-15 | Reserved for future use | — | — |

#### 5.2.3 Redis ACL (Future Enhancement)

For high-security tenants, Redis ACL users are provisioned per tenant:

```
user tenant_acme123 on ~tenant:acme123:* +@all -@dangerous
user tenant_globalbank001 on ~tenant:globalbank001:* +@all -@dangerous
```

This ensures that even if application code has a bug that scans keys, the Redis ACL restricts access to the tenant's key space.

### 5.3 Kafka: Topic Isolation and Message Routing

#### 5.3.1 Topic Design

As defined in ADR-007, topics are organized by event type (not by tenant):

| Topic | Partitions | Replication | Retention | Message Key Format |
|-------|-----------|-------------|-----------|-------------------|
| `verification.commands` | 24 | 3 | 7 days | `{tid}:verification:{id}` |
| `verification.events` | 24 | 3 | 30 days | `{tid}:verification:{id}` |
| `verification.results` | 24 | 3 | 30 days | `{tid}:task:{id}` |
| `document.tasks` | 48 | 3 | 1 day | `{tid}:document:{id}` |
| `biometric.tasks` | 48 | 3 | 1 day | `{tid}:biometric:{id}` |
| `risk.tasks` | 48 | 3 | 1 day | `{tid}:risk:{id}` |
| `fraud.tasks` | 48 | 3 | 1 day | `{tid}:fraud:{id}` |
| `audit.logs` | 12 | 3 | 7 years | `{tid}:audit:{seq}` |
| `webhook.delivery` | 24 | 3 | 1 day | `{tid}:webhook:{id}` |
| `compliance.alerts` | 12 | 3 | 1 year | `{tid}:alert:{id}` |
| `tenant.events` | 12 | 3 | 7 days | `{tid}:event:{type}` |

#### 5.3.2 Message Key Convention

```
{tid}:{entity_type}:{entity_id}

Examples:
acme123:verification:v_7f8a9b2c
acme123:document:d_3e4f5a6b
acme123:biometric:b_1c2d3e4f
acme123:risk:r_9a8b7c6d
globalbank001:verification:v_5e6f7a8b
```

Benefits of this key format:
- **Partition affinity**: All messages for the same tenant go to the same partition (ordered processing)
- **Consumer filtering**: Consumers can filter by `tid` prefix without scanning all partitions
- **Routing**: The key carries semantic information for routing and debugging

#### 5.3.3 Consumer-Side Tenant Filtering

```
┌──────────────────────────────────────────────────────────────┐
│                    Kafka Consumer                              │
│                                                               │
│  1. Poll messages from topic partition                       │
│  2. Extract tenant_id from message key: `{tid}:{type}:{id}`  │
│  3. Verify tenant_id matches assigned tenant list             │
│  4. Drop messages for non-assigned tenants (log warning)     │
│  5. Process message within tenant context                    │
│  6. Commit offset                                            │
└──────────────────────────────────────────────────────────────┘
```

**Consumer group isolation:**

| Consumer Group | Topics Consumed | Tenant Assignment |
|---------------|----------------|-------------------|
| `compute-document-workers` | `document.tasks` | All tenants (shared pool) |
| `compute-biometric-workers` | `biometric.tasks` | All tenants (shared pool) |
| `compute-risk-workers` | `risk.tasks` | All tenants (shared pool) |
| `tenant-acme-webhook` | `webhook.delivery` | Single tenant `acme123` |
| `compliance-monitor` | `compliance.alerts` | All tenants (read-only) |

#### 5.3.4 Kafka ACLs

```bash
# Restrict topic access per consumer group
kafka-acls.sh --bootstrap-server kafka:9092 \
  --add \
  --allow-principal User:compute-document \
  --operation Read --topic document.tasks \
  --group compute-document-workers

kafka-acls.sh --bootstrap-server kafka:9092 \
  --add \
  --allow-principal User:compute-biometric \
  --operation Read --topic biometric.tasks \
  --group compute-biometric-workers

# Audit log topic is append-only for producers, read-only for compliance
kafka-acls.sh --bootstrap-server kafka:9092 \
  --add \
  --allow-principal User:gateway \
  --operation Write --topic audit.logs

kafka-acls.sh --bootstrap-server kafka:9092 \
  --add \
  --allow-principal User:compliance-service \
  --operation Read --topic audit.logs
```

#### 5.3.5 `audit.logs` Topic (7-Year Retention)

The `audit.logs` topic is the most critical Kafka topic for compliance:

| Property | Value | Rationale |
|----------|-------|-----------|
| Retention | 7 years | AML/GLB regulatory requirement |
| Replication | 3 | Multi-AZ durability |
| Min ISR | 2 | Ensure data survives single-broker failure |
| Compression | ZSTD | Reduce storage costs (60-70% compression ratio) |
| Cleanup policy | `compact,delete` | Compact by key, delete after 7 years |
| Message format | Avro (with schema registry) | Schema evolution, validation |
| Partition count | 12 | Sufficient for sustained write throughput |

### 5.4 S3/MinIO: Prefix-Based Isolation

#### 5.4.1 Object Storage Layout

```
s3://usora-data/
├── tenant_acme123/
│   ├── verifications/
│   │   ├── v_7f8a9b2c/
│   │   │   ├── document_front.jpg
│   │   │   ├── document_back.jpg
│   │   │   ├── selfie.jpg
│   │   │   └── metadata.json
│   │   └── v_3e4f5a6b/
│   │       └── ...
│   ├── reports/
│   │   ├── monthly_compliance_2026-07.pdf
│   │   └── audit_export_2026-Q2.csv
│   └── config/
│       ├── logo.png
│       └── email_template.html
├── tenant_globalbank001/
│   ├── verifications/
│   └── ...
└── shared/
    ├── ml_models/
    │   ├── document-ocr-v3.onnx
    │   └── face-match-v2.onnx
    └── templates/
        └── verification_report.html
```

#### 5.4.2 IAM Policies

**Per-tenant policy (applied via Vault dynamic IAM credentials):**

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:PutObject",
        "s3:DeleteObject"
      ],
      "Resource": "arn:aws:s3:::usora-data/tenant_acme123/*",
      "Condition": {
        "StringEquals": {
          "s3:x-amz-server-side-encryption": "aws:kms"
        }
      }
    },
    {
      "Effect": "Allow",
      "Action": [
        "s3:ListBucket"
      ],
      "Resource": "arn:aws:s3:::usora-data",
      "Condition": {
        "StringLike": {
          "s3:prefix": "tenant_acme123/*"
        }
      }
    }
  ]
}
```

**Bucket policy (denies cross-prefix access):**

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Deny",
      "Principal": "*",
      "Action": "s3:*",
      "Resource": "arn:aws:s3:::usora-data/*",
      "Condition": {
        "StringNotLike": {
          "s3:prefix": "${aws:userid}/*"
        }
      }
    }
  ]
}
```

#### 5.4.3 Lifecycle Policies

```
s3://usora-data/tenant_acme123/
├── Temporary uploads/ ───── 24h → DELETE
├── Verification docs/ ───── 90d → GLACIER, 7yr → DELETE
├── Reports/ ─────────────── 1yr → GLACIER, 7yr → DELETE
└── Audit exports/ ────────── 7yr → DELETE
```

#### 5.4.4 Encryption

- **Server-side encryption**: SSE-KMS with per-tenant KMS keys
- **KMS key policy**: Only the tenant's IAM role can use the key
- **Bucket key**: S3 Bucket Key enabled to reduce KMS API calls (cost optimization)
- **Object lock**: WORM (Write Once Read Many) enabled for audit documents

### 5.5 Elasticsearch: Index-per-Tenant

#### 5.5.1 Index Naming Convention

```
cases-{tid}         → cases-acme123, cases-globalbank001
audit-{tid}         → audit-acme123, audit-globalbank001
documents-{tid}     → documents-acme123, documents-globalbank001
verifications-{tid} → verifications-acme123, verifications-globalbank001
```

#### 5.5.2 Document Routing

Each document includes `tenant_id` in the `_routing` field to ensure shard-level isolation:

```json
{
  "_index": "cases-acme123",
  "_id": "case_abc123",
  "_routing": "acme123",
  "_source": {
    "tenant_id": "acme123",
    "case_id": "case_abc123",
    "status": "PENDING_REVIEW",
    "verification_id": "v_7f8a9b2c",
    "created_at": "2026-07-25T10:30:00Z"
  }
}
```

#### 5.5.3 RBAC

```json
PUT _security/role/cases_acme123_read
{
  "indices": [
    {
      "names": ["cases-acme123"],
      "privileges": ["read", "view_index_metadata"],
      "field_security": {
        "grant": ["*"]
      }
    }
  ]
}

PUT _security/user/tenant_acme123_app
{
  "password": "...",
  "roles": ["cases_acme123_read", "audit_acme123_read"]
}
```

### 5.6 ClickHouse: Tenant-Isolated Tables

#### 5.6.1 Table Schema

```sql
CREATE TABLE tenant_acme123.analytics_events (
    event_id UUID,
    tenant_id String,
    event_type LowCardinality(String),
    verification_id String,
    status LowCardinality(String),
    duration_ms UInt32,
    country FixedString(2),
    document_type String,
    risk_score Float32,
    created_at DateTime,
    ingested_at DateTime DEFAULT now()
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(created_at)
ORDER BY (tenant_id, created_at, event_id)
TTL created_at + INTERVAL 2 YEAR DELETE
SETTINGS index_granularity = 8192;
```

#### 5.6.2 Per-Tenant Resource Limits

| Resource | Default | High-Security Tenant | Enforcement |
|----------|---------|---------------------|-------------|
| Max table size | 500 GB | 5 TB | `max_table_size` in MergeTree settings |
| Max partition count | 100 | 500 | `max_partitions_per_insert_block` |
| Query CPU quota | 4 cores | 16 cores | ClickHouse `quotas` |
| Query memory limit | 4 GB | 32 GB | `max_memory_usage_for_user` |
| Concurrent queries | 5 | 20 | `max_concurrent_queries_for_user` |

### 5.7 HashiCorp Vault: Per-Tenant Secrets

#### 5.7.1 Secret Engine Layout

```
secret/
├── tenant/
│   ├── acme123/
│   │   ├── database          (dynamic PostgreSQL credentials)
│   │   ├── redis             (Redis password)
│   │   ├── kafka             (Kafka API key/secret)
│   │   ├── s3                (S3 access key/secret for tenant prefix)
│   │   ├── kms               (per-tenant KMS key ARN)
│   │   ├── api_keys/         (tenant application API keys)
│   │   ├── webhook_secrets/  (HMAC signing secrets)
│   │   └── encryption_keys/  (per-tenant encryption keys)
│   └── globalbank001/
│       └── ...
├── platform/
│   ├── database_master       (admin DB credentials — limited access)
│   ├── kafka_admin           (Kafka admin credentials)
│   └── monitoring            (Grafana, Prometheus API keys)
└── ...
```

#### 5.7.2 Vault ACL Policy

```hcl
path "secret/tenant/acme123/*" {
  capabilities = ["create", "read", "update", "delete", "list"]
}

path "secret/tenant/acme123/database" {
  capabilities = ["read"]
}

path "transit/keys/tenant-acme123-*" {
  capabilities = ["create", "read", "update", "delete"]
}

path "transit/encrypt/tenant-acme123-*" {
  capabilities = ["create", "update"]
}

path "transit/decrypt/tenant-acme123-*" {
  capabilities = ["create", "update"]
}
```

#### 5.7.3 Dynamic Database Credentials

```bash
# Configure DB secret engine
vault write database/config/tenant-acme123 \
    plugin_name=postgresql-database-plugin \
    allowed_roles="tenant-acme123-app" \
    connection_url="postgresql://{{username}}:{{password}}@postgres:5432/usora?sslmode=verify-full" \
    username="vault_admin" \
    password="..."

# Create role
vault write database/roles/tenant-acme123-app \
    db_name=tenant-acme123 \
    creation_statements="CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; GRANT USAGE ON SCHEMA tenant_acme123 TO \"{{name}}\"; GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA tenant_acme123 TO \"{{name}}\";" \
    default_ttl="1h" \
    max_ttl="24h"
```

#### 5.7.4 Per-Tenant Transit Keys

```bash
# Create encryption key for tenant
vault write -f transit/keys/tenant-acme123-encrypt \
    type=aes256-gcm96 \
    exportable=false \
    deletion_allowed=false \
    auto_rotate_period=8760h  # 1 year
```

---

## 6. Tenant Lifecycle

### 6.1 Provisioning

```
┌────────────┐    ┌───────────────┐    ┌────────────────┐    ┌──────────────┐
│  Admin     │    │  Provisioning │    │  Orchestration │    │   Tenant     │
│  Approval  │───▶│   Service     │───▶│   (Camunda)    │───▶│   Ready      │
└────────────┘    └───────────────┘    └────────────────┘    └──────────────┘
                         │                      │
                         ▼                      ▼
               ┌──────────────────┐   ┌──────────────────┐
               │  Phase 1: DB     │   │  Phase 2: Infra  │
               └──────────────────┘   └──────────────────┘
                         │                      │
                         ▼                      ▼
               ┌──────────────────┐   ┌──────────────────┐
               │ CREATE SCHEMA    │   │ Create K8s       │
               │ tenant_{tid}     │   │ namespace        │
               ├──────────────────┤   ├──────────────────┤
               │ Run Flyway       │   │ Apply            │
               │ migrations       │   │ NetworkPolicies  │
               ├──────────────────┤   ├──────────────────┤
               │ Create DB role   │   │ Deploy tenant    │
               │ with SCHEMA      │   │ webhook receiver │
               │ permissions      │   │                  │
               ├──────────────────┤   ├──────────────────┤
               │ Record in        │   │ Configure        │
               │ public.tenants   │   │ monitoring       │
               └──────────────────┘   └──────────────────┘
                         │                      │
                         └──────┬───────────────┘
                                ▼
               ┌──────────────────────────────────────┐
               │  Phase 3: Security                     │
               ├──────────────────────────────────────┤
               │ Generate Vault secret engine path     │
               │ Create transit encryption key         │
               │ Generate API keys (primary + backup)  │
               │ Create webhook signing secret         │
               │ Generate mTLS certificate             │
               └──────────────────────────────────────┘
                                │
                                ▼
               ┌──────────────────────────────────────┐
               │  Phase 4: Data Pipeline                │
               ├──────────────────────────────────────┤
               │ Configure S3 prefix with lifecycle    │
               │ Create Elasticsearch index template   │
               │ Create ClickHouse table               │
               │ Configure Kafka ACLs                  │
               └──────────────────────────────────────┘
                                │
                                ▼
               ┌──────────────────────────────────────┐
               │  Phase 5: Activation                   │
               ├──────────────────────────────────────┤
               │ Provision monitoring dashboards       │
               │ Configure rate limits                 │
               │ Set feature flags                     │
               │ Run integration smoke tests           │
               │ Set tenant status to ACTIVE           │
               └──────────────────────────────────────┘
```

**Provisioning workflow (automated via Camunda BPMN):**

1. **Admin Approval**: Tenant contract signed → admin creates tenant record in `public.tenants`
2. **Parallel Phase 1 + 2**: Database schema creation and infrastructure provisioning run in parallel
3. **Phase 3**: Security credentials generated after schema and namespace are confirmed ready
4. **Phase 4**: Data pipeline configurations applied after security is in place
5. **Phase 5**: Activation smoke tests run to verify end-to-end functionality
6. **Complete**: Tenant status set to `ACTIVE`; onboarding notification sent

**Total provisioning time:** < 5 minutes (fully automated)

### 6.2 Offboarding (GDPR Data Deletion)

Offboarding is a strictly ordered, audited process that ensures complete data deletion while maintaining audit trails:

```
┌─────────────────────────────────────────────────────────────────┐
│                    GDPR OFFBOARDING WORKFLOW                      │
│                                                                   │
│  Step 1: INITIATE                                                 │
│  ├── Receive GDPR deletion request                                │
│  ├── Verify request authenticity (legal review)                  │
│  ├── Create offboarding ticket with reference ID                 │
│  └── Lock tenant: status → PENDING_DELETION, block new requests  │
│                                                                   │
│  Step 2: EXPORT (if requested by tenant)                          │
│  ├── Export all tenant data to S3: s3://usora-exports/{tid}/     │
│  ├── Generate data inventory report                              │
│  └── Notify tenant of export completion                          │
│                                                                   │
│  Step 3: DELETE PostgreSQL SCHEMA                                 │
│  ├── DROP SCHEMA tenant_{tid} CASCADE;                           │
│  ├── REVOKE all roles and permissions                            │
│  └── Remove tenant from public.tenants registry                  │
│                                                                   │
│  Step 4: DELETE S3 PREFIX                                         │
│  ├── aws s3 rm s3://usora-data/{tid}/ --recursive                │
│  ├── aws s3 rm s3://usora-exports/{tid}/ --recursive (if any)   │
│  └── Verify deletion: aws s3 ls s3://usora-data/{tid}/ → empty  │
│                                                                   │
│  Step 5: PURGE REDIS KEYS                                         │
│  ├── redis-cli --scan --pattern "tenant:{tid}:*" | xargs redis-cli del
│  └── Verify: redis-cli keys "tenant:{tid}:*" → (empty array)    │
│                                                                   │
│  Step 6: DELETE VAULT SECRETS                                     │
│  ├── vault delete secret/tenant/{tid}                            │
│  ├── vault delete transit/keys/tenant-{tid}-*                    │
│  └── Remove ACL policy                                            │
│                                                                   │
│  Step 7: REMOVE K8s NAMESPACE                                     │
│  ├── kubectl delete namespace tenant-{tid}                       │
│  └── Verify deletion: kubectl get namespace tenant-{tid} → Not   │
│                                                                   │
│  Step 8: CLEAN KAFKA                                              │
│  ├── (Note: Kafka audit logs are RETAINED for 7 years)           │
│  ├── Stop per-tenant webhook consumer group                      │
│  └── Purge webhook.delivery topic for tenant (if needed)         │
│                                                                   │
│  Step 9: CLEAN ELASTICSEARCH                                      │
│  ├── DELETE /cases-{tid}                                          │
│  ├── DELETE /audit-{tid} (unless under legal hold)               │
│  └── DELETE /documents-{tid}                                     │
│                                                                   │
│  Step 10: CLEAN CLICKHOUSE                                        │
│  ├── DROP TABLE tenant_{tid}.analytics_events                    │
│  └── DROP DATABASE tenant_{tid}                                  │
│                                                                   │
│  Step 11: ARCHIVE AUDIT LOGS                                      │
│  ├── Kafka audit.logs RETAINED (7-year regulatory requirement)   │
│  ├── Mark tenant as DELETED in audit log metadata                 │
│  └── Hash-chain integrity verified                                │
│                                                                   │
│  Step 12: COMPLETION                                              │
│  ├── Generate GDPR compliance report                             │
│  ├── Send report to tenant (encrypted email)                     │
│  ├── Store completion proof in immutable audit log               │
│  └── Set tenant status → DELETED                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Key guarantees:**
- No tenant data remains in any storage system after offboarding (except immutable audit logs)
- The entire process is recorded in the immutable audit trail
- GDPR completion report is generated and stored for 7 years
- Offboarding time target: < 1 hour for standard tenants

### 6.3 Suspension

Suspension is a reversible state that immediately stops all tenant activity while preserving data:

| Phase | Action | Duration |
|-------|--------|----------|
| **1. Suspend** | Set tenant status → `SUSPENDED` | Immediate |
| **2. Block Ingress** | Update rate limit rules: `allow: false` for all endpoints | < 30s |
| **3. Revoke Sessions** | Blacklist all active JWTs in Redis `tenant:{tid}:jwt-blacklist:*` | < 5s |
| **4. Scale Compute** | Scale tenant workers to zero replicas | < 60s |
| **5. Notify** | Send suspension notification to tenant admin email | < 5min |
| **6. Retain Data** | All data remains intact | Indefinite |
| **7. Start Timer** | Begin `data_retention` timer (configurable: 30/60/90 days) | — |

**Resumption workflow:**

1. Admin resolves suspension reason
2. Update tenant status → `ACTIVE`
3. Re-enable rate limiting rules (normal limits restored)
4. Scale compute workers back to configured replica count
5. Verify end-to-end functionality via health check
6. Send resumption notification

**Auto-offboarding after grace period:**
- If grace period expires without resumption, automatic offboarding workflow is triggered
- Tenant admin receives warnings at D-30, D-14, D-7, D-1 before auto-offboard

---

## 7. Isolation Verification

### 7.1 Automated Compliance Checks

| Check | Frequency | Tool | What It Validates |
|-------|-----------|------|-------------------|
| Cross-schema query test | Hourly | Custom Go service | Attempts cross-schema queries; verifies they are rejected |
| RLS bypass test | Hourly | Custom Go service | Attempts to query without session variable; verifies empty result |
| Network policy audit | Every 15 min | Falco + OPA | Validates no unexpected network flows between namespaces |
| mTLS enforcement check | Hourly | SPIFFE helper | Attempts non-mTLS connection; verifies rejection |
| S3 cross-prefix access | Daily | AWS IAM Access Analyzer | Validates bucket policy prevents cross-prefix access |
| Redis key namespace check | Daily | Lua script | Scans Redis for keys without tenant prefix; alerts on violations |
| Vault ACL audit | Daily | vault audit log | Reviews all access attempts for unauthorized path access |
| Kafka ACL verification | Daily | kafka-acls.sh | Validates ACLs are correct and no unauthorized access |
| GDPR data deletion verification | On offboarding | Automated script | Confirms all storage systems have deleted tenant data |
| Encryption key isolation | Monthly | Manual review | Verifies per-tenant KMS keys are isolated |

### 7.2 Penetration Testing

| Test Type | Frequency | Scope |
|-----------|-----------|-------|
| Cross-tenant data access | Quarterly | Attempt to access Tenant B's data from Tenant A's context |
| Tenant privilege escalation | Quarterly | Attempt to escalate from viewer to admin within a tenant |
| Schema injection | Quarterly | Attempt SQL injection targeting cross-schema access |
| JWT tampering | Quarterly | Attempt to modify `tid` claim in JWT |
| API key brute force | Quarterly | Attempt to guess another tenant's API key |
| SSRF to access tenant data | Quarterly | Attempt to use SSRF to access other tenants' S3 data |

### 7.3 Audit Log Review

| Review Type | Frequency | Performed By |
|-------------|-----------|-------------|
| All cross-tenant access attempts | Real-time | Automated alerting |
| Tenant provisioning audit | Weekly | Security Team |
| Tenant offboarding verification | Per event | Compliance Team |
| Suspension/resumption audit | Per event | Security Team |
| Failed authentication review | Daily | SRE Team |
| Rate limit violation review | Weekly | SRE Team |

### 7.4 Quarterly Isolation Review

Every quarter, the Security Team conducts a comprehensive isolation review:

1. **Review all isolation mechanisms** for each layer (network, application, data)
2. **Verify** that no new bypass vectors have been introduced
3. **Test** the tenant provisioning/offboarding automation
4. **Validate** that monitoring correctly detects isolation violations
5. **Review** all isolation-related incidents from the past quarter
6. **Update** threat model for any new attack vectors
7. **Document** findings and action items

---

## 8. Threat Model

### 8.1 STRIDE Analysis

| Threat | Description | Mitigation | Layer |
|--------|-------------|------------|-------|
| **S**poofing | Attacker impersonates another tenant | mTLS, JWT validation, API key authentication | 1, 2 |
| **T**ampering | Attacker modifies data belonging to another tenant | Schema-per-tenant, RLS, S3 prefix IAM, audit logging | 2, 3 |
| **R**epudiation | Attacker denies performing actions | Immutable audit trail with hash-chain, blockchain anchoring | 3 |
| **I**nformation Disclosure | Attacker reads another tenant's data | Network policies, per-tenant encryption, RLS, IAM | 1, 2, 3 |
| **D**enial of Service | Attacker consumes resources to degrade service for other tenants | Per-tenant rate limiting, circuit breakers, resource quotas | 2 |
| **E**levation of Privilege | Attacker escalates from lower to higher privilege within or across tenants | RBAC, OPA/Rego policies, Vault ACL | 2, 3 |

### 8.2 Attack Trees

**Cross-Tenant Data Access Attack Tree:**

```
Goal: Read Tenant B's data from Tenant A's context
├── 1.0 Bypass Network Isolation
│   ├── 1.1 Exploit Kubernetes privilege escalation (patch NetworkPolicy)
│   │   └── Mitigation: OPA Gatekeeper, RBAC, Audit logging
│   ├── 1.2 Exploit service mesh misconfiguration
│   │   └── Mitigation: Istio AuthorizationPolicy audit, mTLS enforcement
│   └── 1.3 Exploit shared VPC or subnet
│       └── Mitigation: Security Groups, Network ACLs
├── 2.0 Bypass Application Isolation
│   ├── 2.1 Forge JWT with different `tid` claim
│   │   ├── 2.1.1 Steal signing key from Vault
│   │   │   └── Mitigation: Vault ACL, audit logging, key rotation
│   │   └── 2.1.2 Exploit JWT validation logic
│   │       └── Mitigation: OPA/Rego policy verification, penetration testing
│   └── 2.2 Exploit TenantContext propagation bug
│       └── Mitigation: ThreadLocal verification, integration tests
├── 3.0 Bypass Data Isolation
│   ├── 3.1 SQL injection to access cross-schema
│   │   └── Mitigation: Prepared statements, RLS, per-tenant DB roles
│   ├── 3.2 Guess S3 object keys in another tenant's prefix
│   │   └── Mitigation: IAM prefix policy, pre-signed URL enforcement
│   └── 3.3 Exploit Redis key space scanning
│       └── Mitigation: Key prefix pattern, Redis ACL (future)
└── 4.0 Physical / Cloud Infrastructure Access
    ├── 4.1 AWS console access
    │   └── Mitigation: IAM roles, MFA, SSO, audit logging
    └── 4.2 Physical access to data center
        └── Mitigation: AWS physical security, SOC 2 Type II
```

### 8.3 Data Leakage Scenarios

| Scenario | Impact | Likelihood | Detection | Prevention |
|----------|--------|------------|-----------|------------|
| Application log includes tenant PII | Low | Medium | Log scanner (Falco) | Structured logging with PII redaction |
| Error response includes tenant data | Medium | Low | WAF response inspection | Error response sanitization |
| Database backup exposed to wrong tenant | Critical | Low | Access audit | Per-tenant backup encryption keys |
| Cache key collision | Medium | Low | Key prefix audit | Strict key namespacing convention |
| Shared temp directory in compute | Medium | Medium | File system monitoring | Per-tenant temp directories |

### 8.4 Privilege Escalation Scenarios

| Scenario | Path | Mitigation |
|----------|------|------------|
| Viewer → Admin within same tenant | Exploit RBAC policy bug | OPA/Rego policy validation, ABAC context checks |
| Tenant A admin → Tenant B admin | Forge JWT or manipulate TenantContext | mTLS + JWT dual validation, audit logging |
| User → Super admin | Exploit admin API without proper auth | API Gateway authentication, MFA for admin actions |
| API key → Full platform access | Limited API key scopes | API key permissions bound to specific endpoints |

---

## 9. Appendix: References

### 9.1 Related Documents

| Document | Location | Description |
|----------|----------|-------------|
| System Overview | `docs/architecture/system-overview.md` | Overall USORA architecture |
| ADR-004: Schema-per-Tenant | `docs/adr/adr-004-schema-per-tenant.md` | Decision record for PostgreSQL isolation strategy |
| ADR-005: Redis Namespacing | `docs/adr/adr-005-redis-namespacing.md` | Decision record for Redis key isolation |
| ADR-007: Kafka Topics | `docs/adr/adr-007-kafka-topics.md` | Decision record for Kafka topic design |
| Compliance Mapping | `docs/compliance-mapping.md` | Regulatory compliance framework |
| Security Architecture | `docs/architecture/system-overview.md#7-security-architecture` | Security controls and encryption |

### 9.2 Relevant Standards

| Standard | Requirement | USORA Compliance |
|----------|-------------|-----------------|
| GDPR Art. 28 | Data processor obligations | Schema-per-tenant, per-tenant encryption, data deletion API |
| SOC 2 CC6.1 | Logical and physical access controls | Network policies, mTLS, IAM, Vault |
| SOC 2 CC7.2 | Monitoring of system components | Tenant-aware monitoring, isolation violation alerts |
| ISO 27001 A.9.1 | Access control policy | RBAC, OPA/Rego, JWT scope enforcement |
| PCI-DSS 7.1 | Restrict access to cardholder data | Per-tenant isolation, encryption, audit |
| KSA Art. 17 | Data processing record | Immutable audit log with hash-chain |

### 9.3 Revision History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0.0 | 2026-07-25 | USORA Architecture Team | Initial release |

---

*USORA — Trust at Scale. Isolation by Design.*
