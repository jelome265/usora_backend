# Proto Contract Drift Check (F-020)

## What was found

`shared/proto/compliance.proto` and `usora-compliance-service`'s own
locally-compiled copy
(`spring-boot-services/usora-compliance-service/src/main/proto/compliance.proto`)
had drifted into **two completely different, incompatible service
contracts** -- not a stylistic or field-ordering difference:

- `shared/proto/compliance.proto` (before this fix) described a
  `ScreenEntity`/`CheckSanctions`/`RunPEPCheck`/`CheckAML`-shaped AML
  screening service.
- The service's own real, implemented contract is
  `ValidateCompliance`/`GetRegulatoryRules`/`CheckJurisdictionCompliance`/
  `SubmitEvidence`-shaped.

These describe entirely different RPC surfaces with no message overlap.
`usora-compliance-service`'s real Java implementation
(`ComplianceGrpcService`) only ever matched its own local copy; the
`shared/proto/` version was pure dead documentation of a contract that no
longer exists anywhere.

The only reason this didn't already break something at runtime: the API
gateway's Rust client generated from the old `shared/proto/compliance.proto`
(`GrpcClients.compliance`, a real `ComplianceServiceClient`) is
constructed but **never actually called anywhere** in the gateway's code
-- confirmed by grepping every `.compliance.` call site in
`rust-services/usora-api-gateway/src/`. If any route handler had actually
invoked it, that call would have failed or behaved unpredictably against
whichever side it happened to match.

## What was fixed

`shared/proto/compliance.proto` now contains the real, currently-
implemented contract (copied from the service's own working copy), with
only the `package` statement changed (`usora.compliance` ->
`usora.compliance.v1`) so the gateway's existing
`tonic::include_proto!("usora.compliance.v1")` call in `lib.rs` keeps
working unchanged. `java_package`/`java_outer_classname` are unchanged
from the service's own copy, so this rename does not affect any
Java-generated package or class name `usora-compliance-service`'s code
imports.

A new CI job, `proto-contract-drift-check` (`.github/workflows/ci-cd.yml`),
now fails the build if:
- `usora-compliance-service`'s local proto diverges from
  `shared/proto/compliance.proto` in anything other than the package name
  and comments.
- `usora-notification-service`'s local proto diverges from
  `shared/proto/notification.proto` at all (these two are expected to be
  byte-identical, unlike compliance's intentional package-name exception).

## What was NOT done, and why

The more thorough structural fix -- making both services compile
**directly** from `shared/proto/` instead of maintaining a local copy at
all -- was not attempted here. `protoc-jar-maven-plugin`'s
`inputDirectories` compiles every `.proto` file found in the given
directory; pointing it at the entire `shared/proto/` tree would generate
Java stubs for every other service's contract too (identity, audit,
tenant, notification, document, risk-scoring), not just compliance's own.
Whether that's actually fine (unused generated code is harmless, just
extra build time) or whether some of those other proto files have
cross-dependencies or naming collisions when compiled together in the
same Maven module is not something this sandbox can verify without a
real Maven build. The CI drift check above is the safer, verifiable
alternative: it can't stop a NEW divergence from being written, but it
guarantees one can't reach `main` unnoticed, which is the actual
acceptance-criterion-relevant guarantee ("deleting generated sources and
rerunning one command recreates identical code" implies the two sources
must already agree -- this check enforces that they do, continuously).

See also `docs/F-012-grpc-service-matrix.md` ("Proto source divergence"),
which first documented this class of gap before this fix existed.
