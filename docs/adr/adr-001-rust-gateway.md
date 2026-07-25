# ADR-001: Rust + Tokio for API Gateway Layer

## Status

Accepted

## Context

USORA's API Gateway is the ingress point for all client traffic — REST, gRPC, and WebSocket. It must handle 500,000+ requests/second per node with sub-5ms p99 latency for routing + auth, while enforcing tenant isolation, rate limiting, and protocol translation. The gateway sits in the critical path of every verification request; any latency or instability here directly impacts customer experience and revenue.

We evaluated several options for the gateway implementation:

1. **Kong (Open-Source / Enterprise)** — Mature, plugin-rich, but JVM-based with GC pauses that introduce unpredictable latency spikes. Limited custom tenant-aware logic without Lua plugins.
2. **Envoy Proxy** — High performance, good observability, but C++ codebase with memory safety concerns. Complex to extend with custom tenant logic.
3. **NGINX / OpenResty** — Proven, but Lua-based extensions are hard to maintain at scale. No native gRPC server support without complex proxying.
4. **AWS API Gateway** — Managed, but vendor lock-in, limited customization, and per-request pricing that becomes expensive at USORA's scale.
5. **Custom Rust Gateway** — Full control, zero-GC latency, memory safety, native gRPC support, but requires building from scratch.

## Decision

Build a custom API Gateway in **Rust with Tokio**.

## Consequences

### Positive

- **Predictable p99 latency**: No garbage collection pauses. Rust's ownership model eliminates data races without runtime overhead. Gateway routing + auth completes in <1ms p50, <5ms p99.
- **Memory safety**: Compile-time guarantees prevent entire classes of bugs (buffer overflows, use-after-free) that could lead to security vulnerabilities or crashes.
- **Native gRPC support**: `tonic` crate provides first-class gRPC server/client with HTTP/2 multiplexing, streaming, and strong typing — no proxy overhead for internal communication.
- **Custom tenant logic**: Deep integration with USORA's tenant isolation model — tenant-aware rate limiting, per-tenant routing, and protocol translation are native features, not plugins.
- **Small binary sizes**: ~10-20MB binaries with sub-second cold starts, ideal for serverless and containerized deployments.
- **Superior connection handling**: Tokio's work-stealing scheduler efficiently manages millions of concurrent connections.
- **Full TLS control**: Native `rustls` integration for TLS 1.3, mTLS, certificate rotation, and custom certificate pinning.

### Negative

- **Build-from-scratch effort**: Requires significant upfront engineering investment vs. off-the-shelf solutions.
- **Rust hiring pool**: Smaller talent pool than Java/Go; longer onboarding for new team members.
- **Ecosystem maturity**: Some middleware patterns (e.g., complex request transformation) require custom implementation rather than off-the-shelf plugins.
- **Operational expertise**: Team must develop Rust-specific debugging, profiling, and operational playbooks.

### Mitigations

- Gateway team staffed with 3 senior Rust engineers and 2 mid-level engineers with Rust training budget.
- Comprehensive test suite: >95% unit test coverage, property-based testing, chaos engineering.
- Operational runbooks cover Rust-specific debugging (tokio-console, perf, flamegraphs).
- Gateway is stateless and horizontally scaled — failure of a single pod is non-critical.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| Kong | GC pauses introduce unpredictable latency; limited custom tenant logic |
| Envoy | C++ memory safety concerns; complex custom extension model |
| NGINX / OpenResty | Lua maintenance burden; no native gRPC server |
| AWS API Gateway | Vendor lock-in; per-request cost prohibitive at scale; limited customization |

## Related Decisions

- ADR-002: Java 21 + Spring Boot for Orchestration Layer
- ADR-003: Rust + Tokio for Compute Layer
- ADR-008: gRPC for Inter-Service Communication

## Date

2026-01-15

## Author

Alice Chen, Platform Lead

## Reviewed By

Bob Martinez (Backend Lead), Charlie Park (ML Lead)
