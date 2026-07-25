# ADR-008: gRPC for Inter-Service Communication

## Status

Accepted

## Context

USORA's microservices communicate internally for request forwarding, task dispatch, and result collection. The inter-service protocol must support:

- Low latency for synchronous calls (orchestration → gateway responses)
- High throughput for streaming (real-time verification events)
- Strong typing to prevent integration bugs across polyglot services (Rust, Java)
- Binary efficiency to minimize network overhead
- Bidirectional streaming for real-time updates
- Easy evolution as the API grows

Options evaluated:

1. **gRPC** — HTTP/2, Protocol Buffers, binary serialization, streaming, strong typing, polyglot support.
2. **REST/JSON** — Ubiquitous, human-readable, but verbose, slower, no streaming, weak typing.
3. **GraphQL** — Flexible querying, but overkill for internal service communication; adds complexity.
4. **Message Queue only** — Async-only, no synchronous request/response pattern; would require additional protocol for sync calls.
5. **Thrift** — Similar to gRPC, but smaller ecosystem and less momentum.

## Decision

Use **gRPC with Protocol Buffers** for all inter-service communication, with REST/JSON reserved for external client-facing APIs.

## Consequences

### Positive

- **Binary efficiency**: Protocol Buffers serialization is 3-5x smaller than JSON and 10-20x faster to parse. Critical for high-throughput internal communication.
- **Strong typing**: Proto definitions generate type-safe clients and servers in Rust, Java, and TypeScript. Compile-time guarantees prevent integration bugs.
- **HTTP/2 multiplexing**: Single TCP connection handles multiple concurrent streams, reducing connection overhead and improving latency.
- **Bidirectional streaming**: `StreamVerificationEvents` RPC allows real-time event streaming from orchestration to gateway without polling.
- **Polyglot support**: Native code generation for Rust (`tonic`), Java (`protobuf-java`), Go, Python, and TypeScript. Teams can work in their preferred language.
- **Schema evolution**: Backward and forward compatibility via field numbers and optional fields. Services can be upgraded independently.
- **mTLS integration**: gRPC natively supports mutual TLS for service-to-service authentication, aligning with USORA's zero-trust model.
- **Tooling**: Excellent ecosystem — `grpcurl` for debugging, `buf` for linting and breaking change detection, `grpc-gateway` for REST fallback if needed.

### Negative

- **Debugging complexity**: Binary payloads are not human-readable without tools like `grpcurl` or protobuf decoding.
- **Load balancer compatibility**: HTTP/2 requires L7 load balancers that support gRPC (NGINX, Envoy, AWS ALB). L4 load balancers don't work well.
- **Browser support**: gRPC is not directly callable from browsers (requires gRPC-Web proxy). Not an issue for internal communication.
- **Proto versioning**: Breaking changes in proto definitions require coordinated deployment. Need strict versioning and compatibility checks.
- **Binary size**: Proto runtime libraries add ~1-2MB to binary sizes. Minor concern.

### Mitigations

- **Debugging**: All services expose a reflection endpoint (`grpc.reflection.v1alpha.ServerReflection`) for `grpcurl` discovery. Structured logging includes decoded proto fields.
- **Load balancing**: Kubernetes Ingress uses NGINX with gRPC support. Internal service mesh (Cilium) handles L7 routing.
- **Versioning**: Proto definitions versioned in Git with `buf breaking` checks in CI. Breaking changes require explicit approval and migration plan.
- **Documentation**: Proto files are self-documenting with comments. Generated OpenAPI specs from proto for external reference.

## Proto Organization

```
usora/
  gateway/v1/
    gateway.proto          # Public-facing API (also used internally)
  orchestration/v1/
    orchestration.proto    # Internal orchestration service
  compute/v1/
    document.proto         # Document analysis service
    biometric.proto        # Biometric analysis service
    risk.proto             # Risk scoring service
  audit/v1/
    audit.proto            # Audit logging service
```

## Service Communication Matrix

| Source | Target | Protocol | Purpose |
|---|---|---|---|
| Gateway (Rust) | Orchestration (Java) | gRPC + mTLS | Request forwarding with tenant context |
| Gateway (Rust) | Compute (Rust) | gRPC + mTLS | Direct compute bypass for simple operations |
| Orchestration (Java) | Compute (Rust) | gRPC + mTLS | Task dispatch and result collection |
| All Services | Kafka | Binary Protocol | Event-driven async communication |

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| REST/JSON | 3-5x larger payloads; slower parsing; no streaming; weak typing; higher latency at scale |
| GraphQL | Overkill for internal RPC; adds query complexity; no performance benefit for machine-to-machine |
| Message Queue only | No synchronous request/response; would require additional protocol (e.g., RPC over Kafka) |
| Thrift | Smaller ecosystem than gRPC; less tooling; less momentum in cloud-native space |

## Related Decisions

- ADR-001: Rust + Tokio for API Gateway Layer
- ADR-002: Java 21 + Spring Boot for Orchestration Layer
- ADR-003: Rust + Tokio for Compute Layer
- ADR-007: Kafka Topic Design for Multi-Tenant Event Bus

## Date

2026-03-20

## Author

Alice Chen, Platform Lead

## Reviewed By

Bob Martinez (Backend Lead), Charlie Park (ML Lead)
