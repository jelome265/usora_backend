# USORA Backend — Architecture & Security Review

**Repository:** `jelome265/usora_backend` (branch `main`)
**Review date:** 2026-07-31
**Scope:** Full clone, static review — no build/run/test execution performed in this pass (see §6, Verification Gaps)
**Reviewer note:** This appears to be a full rewrite of a KYC platform I've reviewed before under a different name/stack (TypeScript/Fastify). This is a different codebase (Rust + Java/Spring Boot) and was reviewed fresh, with no findings carried over from prior context.

---

## 1. Executive Summary

The repository is a large, well-organized multi-service KYC platform skeleton: a Rust API gateway, three Rust compute services (document processing, face matching, risk scoring), and seven Spring Boot orchestration services, backed by Postgres/Redis/Kafka and fronted by ~8,000 lines of planning documentation (`main.md`, `agent.md`, `design.md`, `product.md`, `compliance-mapping.md`, ADRs).

**The documentation claims a maturity level the code does not support.** `main.md` states SOC 2 Type II is "Certified," GDPR/CCPA/LGPD/PDPA/FATF/AML5-6/BSA/PSD2/MiFID II are all "Compliant," audit logs are "blockchain-anchored," and the platform runs a 99.99%-SLA multi-region active-active deployment. Against ~40,000 lines of actual service code, several of the load-bearing security and compliance controls are either bypassable, non-functional, or cosmetic. Treat the compliance-mapping document as an aspiration/checklist, not as evidence — no auditor should be pointed at this repo in its current state as proof of any of those certifications.

**Highest-severity finding:** the API gateway trusts a client-supplied `X-Tenant-ID` header over the tenant claim in the verified JWT. This is a complete tenant-isolation bypass — any authenticated caller can act as any tenant simply by setting a header. In a multi-tenant KYC/compliance system this is about as bad as a single bug gets: cross-tenant data exposure, cross-tenant case tampering, and compliance-audit falsification all become possible from the edge, before any downstream schema-per-tenant control even gets a chance to apply.

Below, findings are organized `[P0]` → `[P2]` per your severity convention. File:line references are given so each can be independently verified.

---

## 2. Architecture Overview (as implemented, not as documented)

```
Client → usora-api-gateway (Rust/Axum)
              │  JWT (JWKS/RS256) or mTLS or OAuth
              │  tenant resolution: X-Tenant-ID header → JWT tenant claim → URL path segment
              ▼
      Spring Boot orchestration layer (7 services)
      ┌───────────────┬────────────────┬──────────────────┬─────────────────┐
      │ tenant-service │ identity-svc   │ compliance-svc    │ core-service    │
      │ audit-service  │ integration-svc│ notification-svc  │                 │
      └───────────────┴────────────────┴──────────────────┴─────────────────┘
              │ gRPC (generated stubs; some call sites are hand-stubbed, see §3.3)
              ▼
      Rust compute layer: document-processor, face-matching-engine, risk-scoring-engine
              │
              ▼
      PostgreSQL (schema-per-tenant, per ADR-004) · Redis · Kafka · S3/MinIO
```

The service boundary choices (Rust for the latency-critical gateway and compute paths, Java/Spring for orchestration and BPMN via Camunda) are reasonable and well-justified in the ADRs. The problem in this review is not the shape of the architecture — it's that several of the controls the architecture *depends on* for correctness (tenant isolation, AML gating, document authenticity, cryptographic validation) are not actually doing what their names say.

Every Spring Boot service follows an identical generated-looking scaffold: `DomainService.java`, `GrpcClient.java`, `ApiController`, `ServiceUnitTest.java`, repeated near-verbatim across all seven services. That's not inherently a problem, but it explains why the same class of stub/bypass shows up in multiple services independently — they came from the same template, so a defect in the template is a defect in every service that inherited it.

---

## 3. Findings

### [P0] 3.1 — Tenant isolation bypass via client-controlled header

**File:** `rust-services/usora-api-gateway/src/middleware/tenant.rs`, `resolve_tenant()` (lines ~68–91)

```rust
fn resolve_tenant(req: &Request) -> Option<String> {
    if let Some(header_val) = req.headers().get("X-Tenant-ID")...  {
        if !header_val.is_empty() {
            return Some(header_val.to_string());   // <-- returns immediately
        }
    }
    if let Some(user) = req.extensions().get::<AuthenticatedUser>() {
        if let Some(ref tid) = user.tenant_id {
            return Some(tid.clone());               // <-- verified JWT claim, never reached if header present
        }
    }
    ...
}
```

The function checks the **client-supplied `X-Tenant-ID` request header before** the tenant ID embedded in the verified JWT. Since this header is fully attacker-controlled, any authenticated user (or a caller with any valid token at all, if roles/permissions aren't separately re-checked downstream) can set `X-Tenant-ID: <any-tenant>` and every downstream service that trusts `TenantContext` — including schema-per-tenant routing, compliance validation, audit trail attribution, and notification delivery — will operate as if the request belongs to that tenant.

This directly contradicts the platform's own ADR-004 (schema-per-tenant isolation) and ADR-005 (Redis namespace-per-tenant): both controls are downstream of a gate that doesn't hold.

**Fix:** Never trust `X-Tenant-ID` from the request unless the caller is a specifically-privileged internal/admin principal explicitly permitted to act cross-tenant (and even then, log it to the immutable audit trail as a privileged action). Default order must be: JWT tenant claim → (optionally) admin-override header validated against an explicit permission — never the reverse. This is a one-line reordering with a large blast radius; needs a security-team sign-off per your own §10 before merge, and a regression test asserting header-spoofing is rejected.

---

### [P0] 3.2 — AML/sanctions screening results don't gate the compliance decision

**File:** `spring-boot-services/usora-compliance-service/src/main/java/com/usora/compliance/service/DomainService.java`, `validateCompliance()` (lines ~85–164)

The method runs three independent checks — Drools rules, AML/watchlist screening (via gRPC), and jurisdiction checks — but the final decision is computed from only two of them:

```java
var totalViolations = violations.size();   // populated by Drools rules + jurisdiction checks only
var totalWarnings = warnings.size();
var decision = totalViolations > 0 ? "REJECTED" : totalWarnings > 0 ? "FLAGGED" : "APPROVED";
```

`amlResults` is collected, serialized into the stored JSON, and returned to the caller — but it is **never inspected when computing `decision`**. Drools rules run in step 1, before AML screening even happens in step 2, so they cannot have seen the screening output either. Practically: if `screenIndividual()` returns a sanctions-list or PEP hit, `validateCompliance()` can still return `APPROVED` as long as no Drools rule or jurisdiction check independently objects.

Compounding this, AML screening failures are swallowed silently:

```java
} catch (Exception e) {
    log.warn("AML screening failed for {}: {}", listType, e.getMessage());
    // amlResult is simply not added — no violation, no warning, no record that screening didn't happen
}
```

A downstream outage or timeout in the AML/watchlist service degrades to "screening silently skipped, case proceeds as if clean" rather than "screening failed, flag for review." That's a fail-open compliance control, which is the opposite of what a KYC/AML gate should do under fault conditions.

**Fix:** AML hits above the tenant's configured risk threshold must map into `violations` (or a hard `REJECTED`/`FLAGGED`), not just get appended to a display list. Screening errors must be recorded as a warning/violation (fail-closed or fail-flagged, per your compliance policy), never silently dropped.

---

### [P1] 3.3 — Dual-authorization "signature" is an unkeyed hash, not a signature

**File:** same `DomainService.java`, `updateRegulatoryRules()` (lines ~186–210)

```java
var contentToSign = request.drlContent() + "::" + newVersion + "::" + tenantId;
var signatureHash = HashingUtil.sha256(contentToSign);
rule.setSignatureHash(signatureHash);
```

This is a plain SHA-256 digest of public/known inputs (rule content, version, tenant ID) with no secret key, HMAC, or asymmetric signature involved. Anyone who can write to the rule store directly (a DB-level actor, a bug elsewhere, or a future code path that skips `updateRegulatoryRules`) can recompute an identical "signature" for tampered content — it proves nothing about who approved the rule or that dual authorization actually occurred. If this hash is later used as audit evidence for "signed compliance rule changes" (which the naming strongly implies), that evidence is fabricable by definition.

**Fix:** Use an HMAC keyed with a secret held only by the compliance service (or better, an asymmetric signature backed by Vault/HSM, consistent with your `main.md` §4.2 stated posture), and verify it — don't just store a hash of public data and call it signed.

---

### [P1] 3.4 — JWT cache returns claims without re-checking expiry

**File:** `rust-services/usora-api-gateway/src/auth/jwt.rs`, `validate_token()` (lines ~44–50)

```rust
{
    let mut cache = self.cache.lock().await;
    if let Some(claims) = cache.get(token) {
        return Ok(claims.clone());   // no check against claims.exp / current time
    }
}
```

The LRU cache has no TTL — entries are evicted only by capacity (1,000 entries), not by time. Once a token has been validated once, its claims are served straight from cache on every subsequent call with that exact token string, including after the token's `exp` has passed, until it's evicted for capacity reasons. Under real traffic patterns (a service account or a small number of tenants making frequent calls with a small pool of tokens), this can keep an expired token "valid" indefinitely from the gateway's point of view.

**Fix:** Either don't cache full validation, or store `(claims, insertion_time)` and check `claims.exp` against wall-clock time on every cache hit before returning — cache should only ever save you the cryptographic verification, never the expiry check.

---

### [P1] 3.5 — MRZ checksum validation accepts a filler character as an automatic pass

**File:** `rust-services/usora-document-processor/src/extraction/mrz.rs`, `validate_checksum()` (lines ~22–29)

```rust
fn validate_checksum(data: &str, check_digit: char) -> bool {
    let check = check_digit;
    if check == '<' {
        return true;     // <-- unconditional pass
    }
    ...
}
```

Per ICAO 9303, a check-digit position should contain a computed digit (0–9); `<` in that position is not a valid checksum value, it's the MRZ filler character. Treating it as an automatic pass means a forged or corrupted MRZ line with `<` where the check digit belongs will report `checksums_valid: true` for that field, undermining the entire purpose of the checksum — which is specifically to catch tampering/corruption in one of the three fields it's meant to protect (document number, DOB, expiry, or the composite final check).

The good news: the core weighted-checksum algorithm itself (weights `[7,3,1]` repeating, mod-10, correct `character_value` mapping for digits/letters/filler) is implemented correctly this time — this is a narrow bypass in the edge-case handling, not a rewrite of the whole check-digit algorithm.

**Fix:** Treat `<` in a check-digit position as invalid/unparseable (fail the check, or fall back to flagging the document for manual review), never as an automatic pass. Add a test fixture with `<` deliberately placed in each check-digit position of TD1/TD2/TD3 formats to confirm this is caught.

---

### [P1] 3.6 — "Forensic" authenticity checks are RGB heuristics, not the physical signals they're named for

**File:** `rust-services/usora-document-processor/src/validation/authenticity.rs`

`detect_hologram_pattern`, `detect_uv_fluorescence`, and `detect_ir_absorption` all operate on ordinary RGB/grayscale pixel statistics (local variance, a fixed `r>200,g<100,b<100` color threshold, luminance histogram dark-ratio) from what appears to be a standard visible-light image capture. Genuine UV fluorescence and IR absorption/transmission checks — the actual anti-forgery features printed into most modern ID documents — require a UV-illuminated or IR-illuminated capture; they cannot be reliably inferred from a normal photo, no matter how the pixel statistics are massaged. As written, this produces a confidence score and a boolean that will read as legitimate forensic signal to anyone consuming the API, when it's actually closer to noise correlated with lighting conditions and camera sensor characteristics.

This isn't a "bug" in the sense of a wrong comparison operator — it's a functional gap dressed up as a feature. Since this feeds into document trust/risk decisions, it's a compliance and fraud-detection risk: a forged document photographed under favorable lighting can score well on "hologram detected" / "UV fluorescence detected" purely by chance, giving false assurance to a reviewer or an automated approval path.

**Fix:** Either (a) explicitly gate these checks behind confirmed UV/IR capture metadata from the client/capture SDK (i.e., only run — and only report — these checks when the input actually came from a UV/IR-capable capture flow), or (b) rename/reclassify these as "visible-light heuristic checks" with clearly lower confidence weighting, and be explicit in the API/docs that they are not equivalent to true UV/IR forensic verification. Don't let the function names imply a capability the input data can't support.

---

### [P2] 3.7 — Outbound webhook/REST client has no SSRF protections

**Files:** `spring-boot-services/usora-integration-service/src/main/java/com/usora/integration/client/RestClient.java`, and the outbound path from `DomainService.publishWebhookEventAsync(...)` using tenant-supplied `webhookUrlTemplate` (seen referenced in `usora-notification-service`'s `GrpcClient`/`TenantEntity`).

`RestClient.post/get/put` take a raw `String url` from the caller and hit it directly via `WebClient` with no allowlist, no check against RFC1918/loopback/link-local ranges (e.g. `169.254.169.254` cloud metadata, `127.0.0.1`, `10.0.0.0/8`), and no re-resolution/pinning to prevent DNS-rebinding between validation and request time. Since tenant-configured webhook URLs are exactly the kind of "internal service calling a URL a customer gave us" pattern that SSRF targets, this needs an explicit egress guard before it reaches production, not just circuit-breaker/retry wrapping (which is otherwise nicely done here).

**Fix:** Validate and re-resolve the destination host at request time (not just at webhook-config-save time) against a denylist of internal/metadata ranges, and consider routing all tenant-destined webhook calls through an isolated egress proxy with no route to internal infrastructure.

---

### [P2] 3.8 — Dead-but-dangerous stub: tenant-service `GrpcClient.checkIdentity`/`checkCompliance` always return `true`

**File:** `spring-boot-services/usora-tenant-service/src/main/java/com/usora/tenant/client/GrpcClient.java` (lines ~25–58)

```java
public boolean checkIdentity(String userId, String tenantId) {
    try {
        // In production: use generated protobuf stubs
        // ... (commented-out real implementation)
        log.debug("Checking identity for user: {} in tenant: {}", userId, tenantId);
        return true;
    } ...
}
```

Same pattern for `checkCompliance`. **Confirmed not currently called anywhere** in the codebase (verified via repo-wide grep) — so this is not an active vulnerability today. Flagging it because it's exactly the shape of landmine that gets wired into a real authorization check later by someone who reasonably assumes a method named `checkIdentity` actually checks identity. Per your Definition of Done (§6), this shouldn't exist unimplemented without a `TODO(owner, ticket, date)` — right now it looks complete and safe to call.

**Fix:** Either implement it against the real gRPC stub now, or make the stub-ness impossible to miss — throw `UnsupportedOperationException`, or at minimum add a `TODO` with an owner/ticket, so it fails loudly instead of quietly authorizing everything if someone wires it in under deadline pressure.

---

### [P2] 3.9 — Documentation asserts compliance/maturity claims the code doesn't support

**File:** `main.md` §4.2–4.3, `docs/compliance-mapping.md`

Claims include: SOC 2 Type II "Certified," GDPR/CCPA/LGPD/PDPA/FATF/AML5-6/BSA/PSD2/MiFID II all "Compliant," "blockchain-anchored" quarterly audit log anchoring, 99.99% uptime SLA, and a "40+ AI engineering agent" org structure. Given the findings above (tenant isolation bypass, AML screening not gating decisions, unkeyed "signatures"), none of these claims currently hold up, and presenting this document to an auditor, investor, or customer as evidence of current compliance posture would be actively misleading — independent of intent.

**Fix:** Reframe `main.md`/`compliance-mapping.md` as a target-state design document ("Planned" / "Target," not "Certified" / "Compliant") until each control is actually implemented and independently verified. This is a documentation-integrity fix, not a code fix, but it's the kind of thing that turns into a real legal/compliance problem if it ships to a real auditor by mistake.

---

## 4. What's Actually Solid

To keep this balanced — several things are done well and shouldn't get lost under the findings above:

- **JWT validation core mechanics** (JWKS lookup by `kid`, RS256-only, issuer/audience validation, 30s leeway) are implemented correctly; the only defect is the cache-vs-expiry interaction in §3.4, not the validation logic itself.
- **MRZ checksum algorithm** (weights, mod-10, character mapping) is a correct ICAO 9303 implementation — a real improvement over a stub/always-true implementation; the `<` edge case in §3.5 is a narrow, fixable gap, not a fundamental rewrite.
- **No hardcoded secrets, API keys, or `.env` files** were found committed anywhere in the repo — a repo-wide grep for common secret patterns came back clean.
- **Resilience patterns** (circuit breakers, retries, time limiters, idempotency keys with Redis-backed locking on webhook ingest) are consistently and correctly applied in the integration service's inbound path — this is genuinely good defensive engineering, which makes the outbound SSRF gap in §3.7 more of an outlier than the norm.
- **Service boundaries** (Rust for latency-critical gateway/compute, Java/Camunda for orchestration/BPMN) are well-reasoned in the ADRs and match the actual code structure — this isn't a case of docs describing an architecture the code doesn't have.

---

## 5. Priority Order for Remediation

1. `[P0]` §3.1 tenant header spoofing — this alone invalidates every other tenant-isolation control in the system. Fix and add a regression test before anything else ships.
2. `[P0]` §3.2 AML screening not gating decisions — this is the core purpose of a KYC/compliance service; right now it's decorative for sanctions/PEP hits.
3. `[P1]` §3.3, §3.4, §3.5 — each is a narrow, well-scoped fix (keyed signature, cache-expiry check, `<` handling) but each is a real bypass in its own right.
4. `[P1]` §3.6 — needs a product decision (gate on capture metadata vs. relabel as heuristic), not just a code fix.
5. `[P2]` §3.7, §3.8 — close before any tenant-facing webhook or cross-service authorization wiring goes live.
6. `[P2]` §3.9 — documentation correction, low effort, but do it before this repo is shown to anyone external.

---

## 6. Verification Gaps (be aware of what this review did *not* do)

Per your own Prime Directive #5 ("every claim is falsifiable"), I want to be explicit about the boundary of this review:

- **No code was compiled or executed.** This was a static read-through; I did not run `cargo build`, `mvn test`, or any integration/e2e suite, so I cannot confirm these findings against actual runtime behavior beyond what the source demonstrates.
- **Drools rule files** (`aml-compliance.drl`) and the Camunda BPMN definitions were not reviewed in this pass — the compliance decision logic inside the rules themselves (as opposed to how their output is aggregated, which *was* reviewed) is unverified.
- **Terraform/K8s/Helm infrastructure code** was not reviewed for IAM/network-policy correctness in this pass.
- **The Rust face-matching and risk-scoring engines** were only spot-checked via the stub/mock grep sweep, not read in full.

If you want, I can go deeper on any of these next — the Drools AML rules and the risk-scoring engine are the two I'd prioritize, since they're adjacent to the P0/P1 findings above.
