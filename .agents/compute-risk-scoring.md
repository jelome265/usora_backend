# Agent: Compute Risk Scoring

## Metadata
- **Agent ID**: `usora-agent-compute-risk-scoring`
- **Tier**: 3 — Compute & Verification
- **Owner**: Data Science / ML Engineering
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Compute Risk Scoring agent performs real-time and batch risk assessment on KYC applicants using ML models, behavioral analytics, fraud signals, and watchlist screening. It generates composite risk scores that drive case routing, manual review triggers, and compliance decisions with explainable outputs.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Runtime | Rust | 1.82+ |
| Async Runtime | Tokio | 1.40+ |
| gRPC | tonic | 0.12+ |
| ML Inference | ONNX Runtime | 1.19+ |
| Feature Store | Feast / Tecton SDK | 0.40+ |
| Graph Analytics | Neo4j / custom graph engine | 5.x |
| Rules Engine | custom Rust | — |
| Cache | Redis | 7.2+ |

## API Surface

### gRPC Services
```protobuf
service RiskScoringService {
  rpc ScoreApplicant(ApplicantScoringRequest) returns (ApplicantScoringResponse);
  rpc ScoreTransaction(TransactionScoringRequest) returns (TransactionScoringResponse);
  rpc GetRiskFactors(RiskFactorsRequest) returns (RiskFactorsResponse);
  rpc UpdateRiskModel(ModelUpdateRequest) returns (ModelUpdateResponse);
  rpc ExplainRiskScore(ExplainabilityRequest) returns (ExplainabilityResponse);
  rpc BatchScoreApplicants(BatchScoringRequest) returns (BatchScoringResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/risk/score` | Score single applicant |
| POST | `/api/v1/risk/score/batch` | Batch score applicants |
| POST | `/api/v1/risk/transaction` | Score transaction |
| GET | `/api/v1/risk/factors/{scoreId}` | Get risk factors for a score |
| GET | `/api/v1/risk/explain/{scoreId}` | Get explainable breakdown |
| PUT | `/api/v1/risk/models/{modelId}` | Update risk model configuration |

## Tenant Isolation Strategy
- **Model isolation**: Per-tenant model weights and configurations in feature store
- **Feature isolation**: Feature vectors prefixed with `tenant:{tid}:` in feature store
- **Score isolation**: Risk scores stored in tenant-scoped PostgreSQL schema
- **Threshold isolation**: Per-tenant risk thresholds (low/medium/high/critical)
- **Rule isolation**: Per-tenant custom rules in Redis
- **Graph isolation**: Per-tenant graph databases for relationship analysis

## Security Boundaries
- All ML models encrypted at rest; decrypted in-memory only during inference
- Feature data PII anonymized before model input (k-anonymity: k=5)
- Risk scores never exposed to applicants; only to authorized reviewers
- Model explainability outputs filtered to prevent model inversion attacks
- Batch scoring rate-limited per tenant to prevent model extraction
- All scoring decisions logged with full feature vector (encrypted) for audit
- Model drift detection: automatic alert if feature distribution shifts > 2 std dev

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Scoring events → structured log → Loki (anonymized features only) |
| Metrics | `risk_score_computed_total`, `risk_scoring_duration_seconds`, `risk_model_drift_score`, `risk_score_distribution` |
| Traces | OpenTelemetry spans: feature retrieval → model inference → rule evaluation → score aggregation |
| Alerts | Scoring latency > 500ms (p99), model error rate > 0.5%, drift detected, score distribution anomaly |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Model inference timeout | 2s deadline | Fallback to rule-based score, alert, queue for re-scoring |
| Feature store unavailable | Connection timeout | Use cached feature snapshot (max age: 1h), alert |
| Model drift detected | Distribution shift > threshold | Flag model for review, fallback to conservative rules, alert |
| Graph database timeout | Query timeout | Skip relationship analysis, score with available features |
| Batch scoring overload | Queue depth > 1000 | Backpressure, scale workers, alert |
| Explainability generation failure | Timeout / error | Return score without explanation, log, alert |

## Configuration
```yaml
risk_scoring:
  models:
    applicant_risk:
      model_path: "/models/applicant_risk_v5.onnx"
      version: "5.2.1"
      input_features: 256
      output_classes: 4  # low, medium, high, critical
    transaction_risk:
      model_path: "/models/transaction_risk_v3.onnx"
      version: "3.1.0"
  thresholds:
    low: 0.0
    medium: 0.3
    high: 0.7
    critical: 0.9
  features:
    store: "feast"
    ttl_seconds: 3600
    real_time_features:
      - "device_fingerprint"
      - "ip_reputation"
      - "behavioral_velocity"
    batch_features:
      - "historical_fraud_rate"
      - "geographic_risk"
      - "watchlist_hits"
  explainability:
    enabled: true
    method: "shap"
    max_features: 10
    min_feature_importance: 0.01
  performance:
    max_latency_ms: 500
    batch_size: 100
    max_concurrent_per_tenant: 100
```

## Dependencies
- `platform-gateway` — Request routing
- `platform-identity` — Service authentication
- `platform-observability` — Metrics, traces, alerting
- `platform-secrets` — Model encryption keys
- `ai-feature-store` — Real-time and batch feature retrieval
- `ai-model-ops` — Model versioning, deployment, drift detection
- `data-redis` — Cached scores, rule configurations
- `data-postgresql` — Score persistence, audit trail
