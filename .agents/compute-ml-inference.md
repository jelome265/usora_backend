# Agent: Compute ML Inference

## Metadata
- **Agent ID**: `usora-agent-compute-ml-inference`
- **Tier**: 3 — Compute & Verification
- **Owner**: ML Platform Engineering
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Compute ML Inference agent provides a unified, high-performance model serving infrastructure for all ML workloads across USORA. It handles model loading, A/B testing, canary deployments, feature preprocessing, batch inference, and real-time scoring with GPU/CPU auto-scaling and multi-tenant model isolation.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Runtime | Rust | 1.82+ |
| Async Runtime | Tokio | 1.40+ |
| gRPC | tonic | 0.12+ |
| Model Serving | NVIDIA Triton Inference Server | 2.50+ |
| ONNX Runtime | ONNX Runtime (GPU) | 1.19+ |
| GPU Framework | CUDA | 12.4+ |
| Feature Preprocessing | custom Rust + Arrow | latest |
| Model Registry | MLflow / custom registry | 2.17+ |
| Cache | Redis | 7.2+ |

## API Surface

### gRPC Services
```protobuf
service MLInferenceService {
  rpc Predict(PredictRequest) returns (PredictResponse);
  rpc BatchPredict(BatchPredictRequest) returns (BatchPredictResponse);
  rpc GetModelInfo(ModelInfoRequest) returns (ModelInfoResponse);
  rpc LoadModel(ModelLoadRequest) returns (ModelLoadResponse);
  rpc UnloadModel(ModelUnloadRequest) returns (ModelUnloadResponse);
  rpc GetModelMetrics(ModelMetricsRequest) returns (ModelMetricsResponse);
  rpc ExplainPrediction(ExplainRequest) returns (ExplainResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/ml/predict` | Single prediction |
| POST | `/api/v1/ml/predict/batch` | Batch prediction |
| GET | `/api/v1/ml/models/{modelId}` | Get model info |
| POST | `/api/v1/ml/models/{modelId}/load` | Load model into memory |
| POST | `/api/v1/ml/models/{modelId}/unload` | Unload model |
| GET | `/api/v1/ml/models/{modelId}/metrics` | Get model performance metrics |
| POST | `/api/v1/ml/explain` | Get prediction explanation |

## Tenant Isolation Strategy
- **Model namespace**: Per-tenant model paths: `/models/tenants/{tid}/{model_name}/{version}`
- **GPU isolation**: Per-tenant GPU memory quotas via NVIDIA MIG (Multi-Instance GPU)
- **Queue isolation**: Tenant-scoped inference queues with priority levels
- **Cache isolation**: Model warm-up cache per tenant
- **Metric isolation**: Per-tenant model performance metrics
- **A/B test isolation**: Per-tenant traffic splitting configurations

## Security Boundaries
- Models encrypted at rest (AES-256-GCM); decrypted in-memory only during load
- Model artifacts signed; signature verified before loading
- No model weights exposed via API; only prediction outputs
- Batch inference rate-limited per tenant to prevent model extraction
- Input sanitization: feature bounds checking, type validation, NaN/Inf rejection
- Model drift detection: automatic monitoring with statistical tests
- Adversarial input detection: gradient-based anomaly detection on inputs

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Inference events → structured log → Loki |
| Metrics | `ml_inference_total`, `ml_inference_duration_seconds`, `ml_model_load_duration_seconds`, `ml_gpu_utilization`, `ml_model_drift_score` |
| Traces | OpenTelemetry spans: request → preprocessing → model load → inference → postprocessing |
| Alerts | Inference latency > 100ms (p99), GPU memory > 90%, model error rate > 0.1%, drift detected |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Model load failure | Triton load error | Retry with backoff, fallback to previous version, alert |
| GPU OOM | CUDA out-of-memory | Queue requests, scale GPU nodes, unload cold models |
| Inference timeout | 500ms deadline | Return timeout, queue for batch retry, alert |
| Model version mismatch | Registry checksum mismatch | Reject load, alert, manual verification |
| Drift detected | Statistical test failure | Flag model for review, fallback to conservative version |
| Adversarial input | Anomaly score > threshold | Reject input, log security event, alert |

## Configuration
```yaml
ml_inference:
  triton:
    url: "triton.usora.svc.cluster.local:8001"
    model_repository: "/models"
    max_model_instances: 4
    gpu_memory_fraction: 0.9
  models:
    default_batch_size: 32
    max_batch_size: 256
    warmup_iterations: 10
    auto_unload_after_idle_minutes: 60
  gpu:
    enabled: true
    cuda_version: "12.4"
    mig_enabled: true
    mig_profiles: ["1g.10gb", "2g.20gb", "3g.40gb"]
  preprocessing:
    feature_scaling: "standard"
    missing_value_strategy: "median"
    outlier_detection: true
    outlier_threshold: 3.0
  security:
    model_signing: true
    input_validation: true
    adversarial_detection: true
    rate_limit_per_tenant: 10000  # requests per minute
  ab_testing:
    enabled: true
    default_traffic_split: 100  # 100% to default model
    canary_threshold: 0.05  # 5% traffic to canary
```

## Dependencies
- `platform-gateway` — Request routing
- `platform-identity` — Service authentication
- `platform-observability` — Metrics, traces, alerting
- `platform-secrets` — Model encryption keys
- `ai-model-ops` — Model registry, versioning, drift detection
- `ai-feature-store` — Feature preprocessing
- `data-redis` — Model cache, queue management
