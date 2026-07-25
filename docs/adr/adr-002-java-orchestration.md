# ADR-002: Java 21 + Spring Boot for Orchestration Layer

## Status

Accepted

## Context

The Orchestration Layer manages the entire verification lifecycle — from session creation through document analysis, biometric matching, risk scoring, and final decision. It executes BPMN workflows, coordinates multi-step flows, maintains saga state for distributed transactions, enforces compliance rules, and handles human-in-the-loop review queues. This layer is the brain of USORA's KYC engine.

We evaluated several options:

1. **Java 21 + Spring Boot** — Mature ecosystem, excellent BPMN support (Camunda), Virtual Threads, enterprise integration patterns.
2. **Go** — Fast, simple, good concurrency, but immature BPMN ecosystem and limited enterprise patterns.
3. **Rust** — Memory safe, fast, but limited BPMN/workflow engine ecosystem; would require building workflow engine from scratch.
4. **Node.js / TypeScript** — Good for I/O-bound tasks, but single-threaded event loop struggles with complex stateful workflows; GC concerns at scale.
5. **Kotlin + Spring Boot** — Similar to Java with modern syntax, but smaller ecosystem for enterprise libraries.

## Decision

Build the Orchestration Layer in **Java 21 LTS + Spring Boot 4.1**.

## Consequences

### Positive

- **Mature BPMN ecosystem**: Camunda 7.21 provides battle-tested BPMN 2.0 workflow execution with external task workers, history management, and tenant-isolated process definitions.
- **Virtual Threads (Project Loom)**: Handle millions of concurrent I/O-bound operations (database queries, API calls, Kafka publishing) with minimal overhead — no thread pool tuning nightmares.
- **Enterprise integration patterns**: Spring Integration, Spring Cloud Stream, and Spring Batch provide proven patterns for event-driven communication, batch processing, and message routing.
- **Comprehensive monitoring**: Spring Boot Actuator + Micrometer provide out-of-the-box metrics, health checks, and distributed tracing integration.
- **Battle-tested in financial services**: Strong regulatory compliance tooling, audit frameworks, and security libraries (Spring Security 6.3 with OAuth2/OIDC).
- **Rich data access**: Spring Data JPA 3.3 with Hibernate 6.5 provides mature ORM, connection pooling, and transaction management.
- **Sealed classes and pattern matching**: Java 21 features improve domain modeling (e.g., `sealed interface VerificationResult permits Approved, Rejected, Escalated, Pending`) and code clarity.
- **8-year LTS support**: Aligns with enterprise procurement cycles and reduces upgrade risk.

### Negative

- **JVM warm-up**: Cold start latency (~2-3 seconds) requires careful JVM tuning and warm-up procedures.
- **Memory footprint**: 4-8GB per pod is larger than Rust/Go equivalents, increasing infrastructure costs.
- **GC pauses**: While G1/ZGC are excellent, pauses still exist at extreme scale; requires tuning and monitoring.
- **Verbosity**: More boilerplate than Kotlin/Go; requires discipline to maintain clean code.

### Mitigations

- JVM tuned with `-XX:+UseContainerSupport`, `-XX:MaxRAMPercentage=75.0`, and G1GC with aggressive heap sizing.
- Warm-up scripts run on pod startup to pre-compile hot paths.
- Memory limits set with Kubernetes resource constraints; HPA scales before memory pressure.
- Code style enforced with Spotless, Checkstyle, and SonarQube quality gates.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| Go | Immature BPMN ecosystem; no equivalent to Camunda; limited enterprise patterns |
| Rust | Would require building workflow engine from scratch; ecosystem not mature for this use case |
| Node.js | Single-threaded event loop unsuitable for complex stateful workflows; GC concerns |
| Kotlin + Spring Boot | Smaller ecosystem for enterprise libraries; team more experienced with Java |

## Related Decisions

- ADR-001: Rust + Tokio for API Gateway Layer
- ADR-003: Rust + Tokio for Compute Layer
- ADR-006: Camunda for BPMN Workflow Engine

## Date

2026-01-15

## Author

Bob Martinez, Backend Lead

## Reviewed By

Alice Chen (Platform Lead), Diana Ross (Data Lead)
