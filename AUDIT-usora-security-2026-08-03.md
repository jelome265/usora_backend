# USORA KYC Platform — Security Audit (2026-08-03)

**Repo:** `usora_backend` (Rust API gateway + 7 Spring Boot orchestration services + 3 Rust compute services)
**Methodology:** static source review only — no build/run/test execution. Every finding below is grounded in a file:line read in this pass.
**Reference baseline:** `docs/architecture-security-review-2026-07-31.md` (§3.1–§3.9). **Important: that review is stale relative to the current code on several points** (§3.1, §3.2, §3.3, §3.4, §3.5, §3.7, §3.8 have since been fixed post-review). Status vs. that review is mapped explicitly below; the **new, currently-open** gaps are what this pass adds.

---

## 1. Executive summary

Three concrete risk pictures dominate:

1. **The gateway's authentication layer is dead code.** `AuthLayer` builds a `JwtValidator` with an empty JWKS map and `update_jwks()` is never called anywhere in the gateway → `jwks.get(kid)` returns `MissingKey` → **every valid Bearer token is rejected with 401**. Only `/health` and `/metrics` pass. On top of that, the validator is built with `JwtValidator::new(None, None)` (no issuer, no audience), so *even if* keys were loaded, issuer/audience would not be enforced. **Severity: P0 — total auth outage today, latent issuer/audience bypass if naively "fixed".**

2. **A forgeable-by-default JWT backdoor in the notification service.** `notification`'s `JwtTokenProvider` is HMAC-SHA keyed by `security.jwt.secret`, and `application.yml` defaults that secret to the committed constant `defaultSecretKeyMustBeOverriddenInProduction`. `validateToken()` checks only the signature (no `iss`/`aud`/`exp`, no issuer/audience configured). Knowing the public default key, an attacker mints a valid JWT for any `sub`/`tenantId`/`roles` → **authentication bypass on notification-service (REST:8085, gRPC:9095)**. This directly contradicts the prior review's §4 ("no hardcoded secrets found"). **Severity: P0.**

3. **The §3.1 tenant-isolation fix was applied only at the gateway edge and then re-introduced downstream.** The gateway `resolve_tenant()` now trusts the JWT first; but six Spring `TenantInterceptor`s read the client-supplied `X-Tenant-ID` header over the JWT — the audit-service one overrides the JWT-derived tenant with the raw header, which also falsifies audit-trail attribution. **Severity: P0 (latent; re-activates if any Spring service is reachable outside the gateway).**

Additionally, this pass found the gateway↔Spring **gRPC control plane is declared but not implemented**: Spring services bind `grpc.server.port` but contain **zero** `@GrpcService` / `*ImplBase` implementations (verified repo-wide), so the gateway's gRPC-backed handlers call services that return `UNIMPLEMENTED`. Inter-service gRPC also runs plaintext/`connect_lazy` on `https://orchestrator` & `https://compute` with no TLS config and no call credentials (no mTLS, despite the architecture diagram).

---

## 2. Status of prior review findings (§3.1–§3.9)

| § | Prior finding | Status now | Evidence (current source) |
|---|---|---|---|
| 3.1 | Tenant isolation bypass via `X-Tenant-ID` header at gateway | **FIXED** | `middleware/tenant.rs:74-124` — JWT claim first; header honored only with explicit `tenant:cross_tenant_override` permission; regression tests `tenant.rs:159-197` |
| 3.2 | AML screening not gating compliance decision | **FIXED** | `compliance/.../DomainService.java:109-155` — matches added to `violations`; fail-closed on error `:142-154`; `decision :170` includes violations |
| 3.3 | Dual-auth "signature" is an unkeyed hash | **FIXED** | `DomainService.java:257` uses `HashingUtil.hmacSha256(contentToSign, ruleSigningSecret)` + merkle root; secret injected `:39` |
| 3.4 | JWT cache returns claims without re-checking `exp` | **FIXED** | `auth/jwt.rs:64-76` — re-checks `claims.exp > now` on hit, evicts expired |
| 3.5 | MRZ `<` auto-pass | **FIXED** | `document-processor/src/extraction/mrz.rs:24-32` — no `<` special-case; regression test `mrz.rs:395-415` |
| 3.6 | "Forensic" checks are RGB heuristics | **MITIGATED (residual P2)** | `validation/authenticity.rs` relabeled `*_heuristic`, confidence capped `0.4`, explanatory details — but method names (`detect_uv_fluorescence`/`detect_ir_absorption`) remain misleading and not gated on capture metadata |
| 3.7 | Outbound REST client SSRF | **FIXED** | `integration/client/RestClient.java:45,66,85` calls `EgressUrlGuard.assertSafeDestination`; `EgressUrlGuard.java` re-resolves at call time, blocks loopback/link-local/site-local/RFC1918/169.254.169.254/CGNAT/IPv6-ULA |
| 3.8 | Dead stub `checkIdentity`/`checkCompliance` return `true` | **FIXED** | `tenant/client/GrpcClient.java:35-47` throws `UnsupportedOperationException` (no longer `return true`), marked TODO, unused |
| 3.9 | Docs assert certifications the code doesn't support | **OPEN** | `main.md` §4.2–4.3 / `docs/compliance-mapping.md` still state SOC 2 Type II "Certified", GDPR/CCPA/... "Compliant", "blockchain-anchored" audit, 99.99% SLA |

> The fixes for §3.1, §3.4, §3.5, §3.8 include inline "SECURITY REGRESSION TEST" citations to the prior review — i.e. the prior review's recommendations were acted on. The gaps below are **new** against that baseline.

---

## 3. Findings (open, ranked)

### P0 — CRITICAL

#### 3.1 The gateway authentication layer does not load any JWKS → all tokens rejected (and issuer/audience unenforced)
- **Files:** `rust-services/usora-api-gateway/src/middleware/auth.rs:22,29` (`AuthLayer::new`/`with_bypass_paths` → `JwtValidator::new(None, None)`); `auth/jwt.rs:43-47` (constructor leaves `jwks = ArcSwap::new(empty)`); `auth/jwt.rs:82-84` (`jwks.get(&kid).ok_or(MissingKey)`); `gateway_service.rs:106` (the internal `validate_token` RPC builds *yet another* `JwtValidator::new(None, None)` per call); `lib.rs` / `routes/mod.rs` (nothing ever calls `update_jwks`).
- **Evidence:** `update_jwks` is **defined** (`jwt.rs:110`) but **never called** in the gateway (repo-wide grep returns only the definition and the `MissingKey` error string). The HTTP `AuthLayer` and the gRPC `validate_token` RPC both construct validators with empty key sets and no issuer/audience.
- **Impact:** Every `Authorization: Bearer ...` request fails signature lookup (`MissingKey`) and is returned `401 Unauthorized` (auth.rs:104 / 124). The authenticated KYC surface is **100% offline** today — only `/health` and `/metrics` (auth.rs:21-22) are reachable. If operators "fix" this by only wiring JWKS without restoring `issuer`/`audience`, tokens from any co-signed issuer/audience are accepted.
- **Fix:** Load the identity service JWKS (`https://<identity>/oauth2/jwks`) at startup and on rotation into the validator; construct the gateway validator with `JwtValidator::new(Some(issuer), Some(audience))` and assert `validation.validate_exp`, issuer and audience checks are non-default; add a startup self-check that fails fast if no keys are loaded. Add a test using a real RS256 key pair + JWKS.

#### 3.2 Forgeable notification JWT via committed default HMAC secret
- **Files:** `notification/src/main/resources/application.yml:128-130` (`secret: ${JWT_SECRET:defaultSecretKeyMustBeOverriddenInProduction}`); `notification/.../security/JwtTokenProvider.java:23` (`@Value("...${...:defaultSecretKeyMustBeOverriddenInProduction}")`), `:28,30` (HMAC `Keys.hmacShaKeyFor(...)`, padded to 32 bytes), `:34-41` (`validateToken` only `parseSignedClaims` — signature only, no `iss`/`aud`/`exp`), `:51` (`tenantId = claims.get("tenantId")` → forgeable tenant), `:54-56` (sets `TenantContext` from a header-claim-bearing token).
- **Evidence:** Hard, symmetric HMAC-SHA, default key is a public constant in source. `getAuthentication` reads `tenantId` and `roles` straight off the (attacker-forged) token.
- **Impact:** Anyone can mint a token signed with the known default secret, set any `sub`/`tenantId`/`roles`, and authenticate to notification-service as any tenant/user → SMS/voice delivery abuse, tenant impersonation, and (via the downstream header-trust, see 3.3) downstream privilege. Architectural note: identity issues RS256 (tenant per-tenant key pairs, `identity/security/JwtTokenProvider.java`) while notification validates HMAC — the two models are inconsistent, so in any deployment exactly one path is broken.
- **Fix:** Remove the default; require `JWT_SECRET` (non-empty) and fail fast at startup (mirror the identity service's `OAUTH_API_CLIENT_SECRET` check, `identity/.../config/SecurityConfig.java:~108-120`). Prefer accepting RS256 tokens from identity only (delegated to the gateway; notification shouldn't be a parallel auth realm). Never read `tenantId` from a token the caller could have forged — derive tenant from the gateway-injected context.

#### 3.3 §3.1 re-introduced downstream in the Spring `TenantInterceptor`s (audit-trail attribution falsifiable)
- **Files:**
  - `audit/.../security/TenantInterceptor.java:18-36` — sets tenant from JWT `:22-25`, **then unconditionally overrides** with `request.getHeader("X-Tenant-ID")` `:33-36`.
  - `core/.../security/TenantInterceptor.java:41-53` — `extractTenantId` reads `X-Tenant-Id` header **first** `:42`, JWT only as fallback.
  - Grep also confirms `identity/.../security/TenantInterceptor.java:17`, `notification/.../security/TenantInterceptor.java:28`, `compliance/.../security/TenantInterceptor.java:12`, `integration/.../security/TenantInterceptor.java:20,29` all read the `X-Tenant-ID`/`X-User-Id` header (integration also reads `X-User-Id`).
- **Impact:** The gateway fix is only as strong as the edge. If any Spring service (REST or gRPC) is reachable outside the gateway — e.g. a direct pod address, an internal load balancer, the gRPC ports (identity `50051`, tenant/core/compliance `9090`, notification/integration `9095`, audit `9092`), or a future mesh sidecast — the exact §3.1 bypass returns. The **audit-service** variant is the worst: because `TenantContext` feeds the audit `actor`/`tenantId`, a forged header **falsifies the immutable audit trail's attribution** (see also §4.1 audit hash chain scope).
- **Fix:** Standardize every `TenantInterceptor` on JWT-first resolution identical to the gateway's `middleware/tenant.rs`; never let a request header override the verified principal. For audit specifically, capture the *authenticated* principal (not the header) as `actor`. Add regression tests in each service mirroring `tenant.rs:159-197`.

#### 3.4 gRPC control plane declared but unimplemented → gateway callers hit UNIMPLEMENTED
- **Files:** `handlers/{tenant,kyc,identity}_handler.rs` (all call `state.grpc_clients.<svc>...`); `grpc/mod.rs:8-16` (typed gRPC clients for identity/document/tenant/audit/compliance/notification); `main.rs:81-99` (gateway hosts its *own* `GatewayService` gRPC server on 9090); `gateway_service.rs:32-53` (`resolve_tenant` calls `state.grpc_clients.tenant.get_tenant_config(...)`).
- **Evidence:** repo-wide grep for `@GrpcService` and `*ImplBase`/`bindService` across `spring-boot-services/` (excluding tests/clients) returns **no results**. Spring services configure `grpc.server.port` (identity `50051`, tenant/core/compliance `9090`, notification/integration `9095`, audit `9092`) and ship `config/GrpcConfig.java` server interceptors + `client/GrpcClient.java` clients, but implement **no gRPC service**. So the gateway's gRPC calls to `IdentityService`/`TenantService`/`AuditService`/`ComplianceService`/`NotificationService` hit ports with no registered service → `UNIMPLEMENTED` → every KYC/tenant/identity handler errors. The internal `GatewayService` RPCs (main.rs:84-98) call Spring services that don't exist.
- **Impact:** The platform's tenant-resolution, rate-limiting, and token-validation control plane — the whole reason the gateway is a gRPC server — is **non-functional against the Spring backends**. This is an availability/completeness defect that also defeats the §3.1 fix in practice (the gateway can't actually resolve a tenant config). Note: the prior review assumed the stubs were wired ("gRPC (generated stubs; some call sites are hand-stubbed)"); the server impls were never present.
- **Fix:** Implement the gRPC server services (`@GrpcService` extending the generated `*Grpc.*ImplBase`) in each Spring service, or remove the half-wired gRPC surface. **Build/run verification required** (this pass is static).

### P1 — HIGH

#### 3.5 Inter-service gRPC is plaintext-to-https-configured, unauthenticated
- **Files:** `grpc/mod.rs:21-25` (`Channel::from_shared(config.upstream.orchestrator_url).connect_lazy()` / `compute_url`, default `https://orchestrator:9090` / `https://compute:9090` from `config/mod.rs:77-78`); `main.rs:95-99` (`tonic::transport::Server::builder()` with no `.tls_config()` → internal gRPC server on `0.0.0.0:9090` is plaintext).
- **Evidence:** No `.tls_config()` on the outbound channels and no `.call_credentials()`/auth interceptor. tonic will error or fall back on an `https://` URI without a configured TLS connector; either way there is no authenticated, confidentiality-protected path between gateway and the orchestration/compute upstreams. Default hostnames `orchestrator`/`compute` do not resolve in `docker-compose.yml` (only postgres/redis/zk/kafka/minio/elasticsearch are present).
- **Impact:** No mTLS between gateway and Spring/Rust backends, directly contradicting the architecture diagram's "mTLS" claim. Confidentiality/integrity of inter-service traffic relies on network isolation alone.
- **Fix:** Configure `Channel::from_shared(...).tls_config(...)` with the upstream CA and attach a per-call call credential (bearer token / mTLS client cert). Wire the upstream hostnames/ports via env (the `UPSTREAM_*` vars already exist) and document them.

#### 3.6 TLS 1.2 default + no client auth on the gateway HTTPS edge
- **Files:** `config/mod.rs:12-18` (`TlsConfig::default { min_version: "TLSv1.2" }`), `:183-188` (`tls_min_version` → `&rustls::version::TLS12` by default), `:199-204` (`builder_with_protocol_versions(&[self.tls_min_version()]).with_no_client_auth()`).
- **Impact:** Gateway HTTPS accepts TLS 1.2 (1.3 not enforced) and performs **no client-certificate authentication**, despite the review diagram's "mTLS" and `auth/mtls.rs` existing as a module.
- **Fix:** Default `min_version` to `TLSv1.3` (fail strict unless explicitly downgraded); enable mutual TLS (`with_client_cert_verifier`/`with_client_auth`) and route it through `auth/mtls.rs`.

#### 3.7 Identity user administration has no tenant-scoping
- **Files:** `identity/.../controller/v1/ApiController.java:54-57` (`POST /api/v1/users` → `createUser`); `:61-65` (`PUT /api/v1/users/{id}/roles` → `updateUserRoles`); `config/SecurityConfig.java:81` (`requestMatchers("/api/v1/users/**").hasAuthority("SCOPE_admin")`); `service/DomainService.java:376-399` (`createUser` uses `request.getTenantId()` from the **request body**, no check it equals the caller's tenant), `:402-421` (`updateUserRoles` loads user by id, no tenant check).
- **Impact:** User-management endpoints are gated only by a global `SCOPE_admin` authority and a request-supplied `tenantId`. If `SCOPE_admin` is granted per-tenant (scoped) rather than globally, this is a cross-tenant user creation/role-assignment IDOR. **Status of admin scoping is not determinable from source alone** — treated as Medium until the role-mapping at token-issuance is confirmed global. (Positive: the prior hardcoded `{noop}` admin client secret was fixed — `SecurityConfig.java:~108-120` now requires `OAUTH_API_CLIENT_SECRET` env and fails fast.)
- **Fix:** Regardless of admin scope, `createUser` must reject a `tenantId` that does not match the caller's `tid` claim (or require an explicit cross-tenant permission + audit log). `updateUserRoles` must verify the target user's tenant equals the caller's.

### P2 — MEDIUM / LOW

#### 3.8 Permissive CORS on the authenticated API surface
- **Files:** `routes/mod.rs` (`create_router`): `CorsLayer::new().allow_origin(Any).allow_methods(Any).allow_headers(Any)` (an equally permissive custom `cors::CorsLayer` exists at `middleware/cors.rs:44-89` but is **not** in the `create_router` stack — dead code).
- **Impact:** Any origin can issue cross-origin requests to `/api/v1/**`; preflight exposes `Authorization` and `X-Tenant-ID`, and `cors.rs:85` exposes `X-Tenant-Id` in `Access-Control-ExposeHeaders`. Because auth is HMAC/RS256 bearer, a malicious site can drive requests with the victim's token (no credentials/cookies are sent cross-origin without `allow_credentials`, which is not set — so blast radius is limited to header-injected traffic, not cookie theft). Moot today (auth is dead — 3.1) but directly exploitable for cross-origin token abuse once 3.1 is restored.
- **Fix:** Replace `Any` with an explicit origin allowlist; do not `allow_headers(Any)` — enumerate `Authorization, Content-Type`.

#### 3.9 Compliance `EncryptionUtil` falls back to an all-zero AES key when env unset
- **Files:** `compliance/util/EncryptionUtil.java:15,19-24` (`SECRET_KEY_ENV = "COMPLIANCE_ENCRYPTION_KEY"`; `System.getenv` → `new byte[32]` "default only for dev"); used at `DomainService.java:386` (`EncryptionUtil.encrypt(request.content())`) for evidence records.
- **Impact:** If `COMPLIANCE_ENCRYPTION_KEY` is unset in any environment, KYC evidence at rest is encrypted under a known all-zero key — confidentiality is nil. (AES-GCM mechanics are correct; the defect is the insecure default.)
- **Fix:** Fail fast at startup if the env var is absent; consider per-tenant keys (ADR-004 is schema-per-tenant; evidence keys should match).

#### 3.10 Identity OIDC metadata advertises weak/deprecated flows and a localhost issuer
- **Files:** `identity/.../service/DomainService.java:423-441` (`getOpenIdConfiguration`) — `issuer: http://localhost:8081` (`:425-431`), `grant_types: [...,"password"]` (ROPC, `:435`), `code_challenge_methods_supported: [S256, plain]` (`:438`).
- **Impact:** Hardcoded `http://localhost` issuer leaks internal topology and breaks token `iss` validation for non-localhost deployments (ties to 3.1: the gateway doesn't check `iss` either). Advertising `plain` PKCE invites interception-defeating flows; `password` (ROPC) is deprecated.
- **Fix:** Parameterize issuer from config; advertise `S256` only; remove `password` grant unless operationally required.

#### 3.11 Audit hash chain excludes actor/tenant/description and attributes `actor` to tenant
- **Files:** `compliance/.../DomainService.java:556-569` (`verifyHashChain`: `contentToHash = previousHash + caseId + action + timestamp`), `:571-597` (`writeAuditEntry` sets `actor = tenantId` `:579`).
- **Impact:** The chain does not include `actor`/`tenantId`/`description`/`eventType`/`detailsJson`, so an actor who can write audit rows (or a tenant-header-spoofed caller, per 3.3) can alter actor/description without breaking `verifyHashChain`. `actor` is the tenant, not the individual principal — weak forensic attribution.
- **Fix:** Hash the full immutable record (`previousHash + tenantId + caseId + actor + action + timestamp + eventType + detailsJson`); set `actor` to the authenticated `sub`, not the tenant id.

#### 3.12 §3.6 residual: misleading forensic-check naming
- As noted in §2, `document-processor/src/validation/authenticity.rs` still exposes `detect_uv_fluorescence`/`detect_ir_absorption` despite being RGB-variance heuristics with confidence capped at 0.4. Low risk now (capped/labeled), but the names remain a misrepresentation hazard for API consumers.

---

## 4. Priority order for remediation

1. **[P0] 3.1** — Restore real auth at the gateway: load JWKS, enforce issuer/audience/expiry. Without this nothing else is reachable/authentic.
2. **[P0] 3.2** — Remove the committed default HMAC secret from notification; align on RS256 delegation from identity, or at minimum fail-fast without `JWT_SECRET`.
3. **[P0] 3.3** — Remove `X-Tenant-ID`/`X-User-Id` header trust from **all** Spring `TenantInterceptor`s; mirror `middleware/tenant.rs`. Make audit `actor` the authenticated `sub`.
4. **[P0] 3.4** — Implement the Spring gRPC service impls (or prune the dead gRPC surface); add TLS + call credentials to gateway↔upstream gRPC.
5. **[P1] 3.5 / 3.6** — TLS 1.3 default + mutual TLS on the gateway edge.
6. **[P1] 3.7** — Tenant-scope identity user-admin (body-supplied `tenantId` must be validated against caller).
7. **[P2] 3.8 / 3.9 / 3.10 / 3.11 / 3.12** — Strict CORS allowlist; encryption key fail-fast; OIDC metadata cleanup (issuer/PKCE/ROPC); full-field audit hash chain; rename forensic heuristics.

---

## 5. Verification gaps (this pass was static — call these out)

- **No build/run.** `cargo build` / `mvn test` were not executed. The "Spring gRPC servers bound but no `@GrpcService`" conclusion and the "gateway rejects all tokens" conclusion are based on static grep/read; runtime behavior (e.g., a gRPC impl registered via a generated class the grep excluded) should be confirmed by building and hitting `/api/v1/...` with a real token and a real identity-issued JWT.
- **No live SSRF / CORS / interop testing** against `EgressUrlGuard` or the gateway TLS config.
- **The gateway↔Spring gRPC topology** (who listens on `9090`/`50051` in a real deploy, what `orchestrator`/`compute` resolve to) is only partially visible from `application.yml` defaults — confirm against the deployment/Helm manifests before treating 3.4/3.5 as production-ready.
- **Identity admin-scope scoping** (3.7) requires reading token-issuance/role-mapping to close.

## 6. Files referenced

Gateway: `rust-services/usora-api-gateway/src/{lib.rs,main.rs,gateway_service.rs,grpc/mod.rs,config/mod.rs,auth/{mod,jwt,oauth}.rs,middleware/{auth,tenant,cors,rate_limit}.rs,routes/mod.rs,handlers/{tenant,kyc,identity}_handler.rs}`
Notification: `.../notification/{Application.yml,security/JwtTokenProvider,TenantInterceptor,TenantContext,config/JwtAuthenticationFilter}`
Audit/Core/Identity/Compliance/Integration: each `.../service/{Application.yml,security/TenantInterceptor,TenantContext,config/GrpcConfig,client/GrpcClient}`
Identity: `.../identity/{config/SecurityConfig,service/DomainService}`
Compliance: `.../compliance/{service/DomainService,util/EncryptionUtil,util/HashingUtil}`
Integration: `.../integration/{client/RestClient,util/EgressUrlGuard}`
Document processor: `rust-services/usora-document-processor/src/{extraction/mrz,validation/authenticity}.rs`
Prior review: `docs/architecture-security-review-2026-07-31.md`; overclaim docs: `main.md`, `docs/compliance-mapping.md`.
