# gRPC Service Matrix (F-012)

Built by direct repository inspection while implementing F-012's remediation
item 1 ("Build a service matrix: each proto service, server implementation,
client, authorization interceptor, TLS policy, timeout, retry policy, and
owner"). This is the actual, verified state as of this commit -- not an
aspirational target.

| Proto service | Canonical proto | Server implementation | Gateway client | Notes |
|---|---|---|---|---|
| `ComplianceService` | `shared/proto/compliance.proto` | `usora-compliance-service` (`ComplianceGrpcService`, Java, `@GrpcService`) | Yes (`GrpcClients.compliance`) | Working. Uses its own local copy at `spring-boot-services/usora-compliance-service/src/main/proto/compliance.proto`, not the shared file directly (see "Proto source divergence" below). |
| `NotificationService` | `shared/proto/notification.proto` | **Fixed in this PR**: `usora-notification-service` (`NotificationGrpcService`, Java, `@GrpcService`) | Yes (`GrpcClients.notification`) | Previously **no server existed at all** -- confirmed by an empty `grpc/` directory and no `@GrpcService`/`ServiceImplBase` anywhere in the module. Every gateway call to this service over gRPC would have failed with `UNIMPLEMENTED`. |
| `TenantService` | `shared/proto/tenant.proto` | **None** | Yes (`GrpcClients.tenant`) | Confirmed no server implementation anywhere in `usora-tenant-service`. `usora-tenant-service`'s own `DomainService` has the right business methods (`onboardTenant`, `offboardTenant`, `getTenant`, `listTenants`, `updateConfig`, `suspendTenant`, `resumeTenant`) to back a thin gRPC facade, matching the pattern used for `ComplianceGrpcService`/`NotificationGrpcService` -- not implemented in this PR; tracked as the next fast-follow. |
| `AuditService` | `shared/proto/audit.proto` | **None** | Yes (`GrpcClients.audit`) | Confirmed no server implementation anywhere in `usora-audit-service`. Not implemented in this PR. |
| `IdentityService` | `shared/proto/identity.proto` | **None** | Yes (`GrpcClients.identity`) | Confirmed no server implementation anywhere in `usora-identity-service` (which exposes OAuth2/OIDC over HTTP, not this gRPC contract). Needs an owner decision on whether this proto contract is still the intended integration path for identity, or should be retired in favor of the OAuth2 HTTP API the gateway already also talks to -- not a code question, out of scope here. |
| `DocumentAnalysisService` | `shared/proto/document.proto` | `usora-document-processor` (Rust, `grpc/mod.rs`) | Yes (`GrpcClients.document`) | Working. |
| `RiskScoringService` | `shared/proto/risk_scoring.proto` | `usora-risk-scoring-engine` (Rust, `grpc/mod.rs`) | Not directly listed in `GrpcClients` at the time of this audit -- verify against current `rust-services/usora-api-gateway/src/grpc/mod.rs` if adding a caller. | Server exists. |
| (biometric, no shared canonical proto) | `rust-services/usora-face-matching-engine/proto/biometric.proto` | `usora-face-matching-engine` (Rust, `grpc/mod.rs`) | Not verified against `GrpcClients` in this pass. | Server exists; proto is service-local only, not under `shared/proto/`. |

## Proto source divergence (separate, related gap)

`protoc-jar-maven-plugin` (the codegen tool both `usora-compliance-service`
and, as of this PR, `usora-notification-service` use) is configured with
`inputDirectories` pointing at each module's own `src/main/proto` (or
`src/main/protobuf`) directory -- **not** `shared/proto/`. Both
`usora-compliance-service` and `usora-notification-service` therefore
compile from a locally-copied proto file, not the canonical shared one.
Nothing currently guarantees these stay in sync if `shared/proto/*.proto`
changes -- this is remediation item 2's actual, concrete manifestation
("Generate stubs from a single canonical proto source or explicitly
version per service"). Not solved here; would need either a build-time
copy step from `shared/proto/` into each module before `protoc-jar-maven-plugin`
runs, or a shared parent/BOM module that owns proto compilation once for
every consumer. Flagging so it's a deliberate choice, not an accident, the
next time these files drift.

## Separate bug found and fixed alongside this matrix

`usora-notification-service`'s own gRPC **client** code
(`GrpcClient.java`) already referenced generated classes
`TenantServiceProto`/`TenantServiceGrpc` from
`src/main/protobuf/tenant_service.proto`, but this module's `pom.xml` had
**no protobuf/gRPC codegen plugin configured at all** (no
`protoc-jar-maven-plugin`, no `protobuf-maven-plugin`) and no checked-in
generated sources. Those classes could not have existed anywhere in this
module -- this service could not have compiled as checked in, independent
of and prior to any of this session's other changes. Fixed as part of
wiring up `NotificationGrpcService`'s own codegen needs, since both the
existing client and the new server need the same plugin.

## What's NOT done (explicit follow-up)

- `TenantService`, `AuditService` server implementations (RPC surface: 6
  and 5 methods respectively) -- next fast-follow PRs, using
  `NotificationGrpcService`/`ComplianceGrpcService` as the reference
  pattern.
- `IdentityService` -- needs an owner decision on whether the proto
  contract is still wanted at all, not just an implementation.
- Authorization interceptors, per-RPC timeout/retry policy, and contract
  tests (remediation items 3, 4) for any of the servers, including the
  two that now exist -- this PR closes the "does a server exist at all"
  gap for `NotificationService`, not the full remediation plan.
- Verifying `RiskScoringService`/biometric clients against the gateway's
  actual current `GrpcClients` struct -- noted as unverified above rather
  than guessed at.
