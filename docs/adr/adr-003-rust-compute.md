# ADR-003: Rust + Tokio for Compute Layer

## Status

Accepted

## Context

The Compute Layer executes CPU-intensive tasks: OCR, document forensics, biometric matching, ML model inference, and fraud detection. These workloads are latency-sensitive (document analysis <2s, biometric matching <500ms, risk scoring <50ms) and throughput-intensive (1M documents/hour, 1M+ biometric templates). They run as asynchronous worker pools consuming tasks from Kafka and returning results via Kafka.

Key requirements:
- Sustained CPU-intensive workloads without GC pauses that could introduce latency spikes
- Safe parallelism for processing document images and biometric templates
- Small binary sizes for fast auto-scaling
- FFI-friendly integration with C/C++ libraries (OpenCV, ONNX Runtime, TensorFlow C API)
- Predictable performance at p99

Options evaluated:

1. **Rust + Tokio** — Zero-cost abstractions, memory safety, no GC, fearless concurrency, FFI-friendly.
2. **Python** — Rich ML ecosystem (PyTorch, TensorFlow, scikit-learn), but GIL limits parallelism; GC pauses; deployment complexity.
3. **C++** — Maximum performance, but memory safety risks, complex build systems, slower development velocity.
4. **Go** — Good concurrency, but GC pauses; limited ML ecosystem; CGO overhead for C++ library integration.
5. **Java** — Good for orchestration, but GC pauses unacceptable for sustained CPU-intensive inference; JNI overhead.

## Decision

Build the Compute Layer in **Rust with Tokio**.

## Consequences

### Positive

- **Zero GC pauses**: No garbage collection means predictable latency at all percentiles — critical for real-time fraud detection with strict SLAs.
- **Memory safety**: Ownership and borrowing model eliminates data races and memory leaks without runtime overhead — safe parallel processing of document images and biometric templates.
- **Fearless concurrency**: `rayon` for data parallelism across CPU cores; `tokio` for async I/O; `crossbeam` for lock-free data structures in high-contention scenarios.
- **Small binary sizes**: ~15-30MB binaries with sub-second cold starts — ideal for auto-scaling compute workers during traffic spikes.
- **FFI-friendly**: Seamless integration with OpenCV (`opencv-rust`), ONNX Runtime (`onnxruntime`), and FAISS (`faiss-rs`) for biometric search.
- **First-class backpressure**: Tokio's built-in backpressure handling prevents cascading failures during traffic spikes.
- **Per-tenant resource quotas**: Enforced at the worker level — CPU and memory limits per tenant prevent noisy-neighbor problems.
- **Model inference performance**: ONNX models run via `tract-onnx` (pure Rust, no C++ dependency) or `onnxruntime` for maximum performance.

### Negative

- **ML ecosystem gap**: Smaller ML ecosystem than Python; requires more custom implementation for some model types.
- **Development velocity**: Slower than Python for prototyping; stricter compiler catches errors early but requires more upfront design.
- **Hiring**: Smaller Rust talent pool than Python/Java; requires investment in training.
- **Debugging**: Less mature debugging tooling than Java (no equivalent to Java Flight Recorder); requires investment in observability.

### Mitigations

- Model training remains in Python (PyTorch/TensorFlow) with export to ONNX for Rust inference — best of both worlds.
- Compute team staffed with 4 senior Rust engineers and 2 Python ML engineers who own the ONNX export pipeline.
- Comprehensive benchmarking suite: criterion.rs for micro-benchmarks, load testing for end-to-end validation.
- Observability: OpenTelemetry tracing, Prometheus metrics, structured logging with `tracing` crate.
- Model versioning and A/B testing framework ensures safe deployment of new models.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| Python | GIL limits parallelism; GC pauses introduce unpredictable latency; deployment complexity with conda/venv |
| C++ | Memory safety risks; complex build systems; slower development velocity; harder to hire for |
| Go | GC pauses; limited ML ecosystem; CGO overhead for C++ library integration |
| Java | GC pauses unacceptable for sustained CPU inference; JNI overhead for native libraries |

## Related Decisions

- ADR-001: Rust + Tokio for API Gateway Layer
- ADR-002: Java 21 + Spring Boot for Orchestration Layer
- ADR-009: ONNX for Model Inference Format
- ADR-010: FAISS for Biometric Template Search

## Date

2026-01-15

## Author

Charlie Park, ML Lead

## Reviewed By

Alice Chen (Platform Lead), Bob Martinez (Backend Lead)
