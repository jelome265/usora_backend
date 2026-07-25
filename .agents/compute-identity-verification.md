# Agent: Compute Identity Verification

## Metadata
- **Agent ID**: `usora-agent-compute-identity-verification`
- **Tier**: 3 — Compute & Verification
- **Owner**: ML Engineering / Computer Vision
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Compute Identity Verification agent performs high-throughput, CPU-intensive identity verification tasks including document OCR, biometric face matching, liveness detection, and identity document authenticity validation. Built in Rust with Tokio for maximum throughput and minimal latency.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Runtime | Rust | 1.82+ |
| Async Runtime | Tokio | 1.40+ |
| gRPC | tonic | 0.12+ |
| OCR | Tesseract / AWS Textract / Google Vision | latest |
| Face Matching | dlib / OpenCV / AWS Rekognition | latest |
| Liveness Detection | custom ONNX models / iProov | latest |
| Document Forensics | custom Rust + OpenCV | — |
| Model Serving | ONNX Runtime / Triton | 1.19+ |
| Image Processing | image-rs / OpenCV Rust bindings | latest |

## API Surface

### gRPC Services
```protobuf
service IdentityVerificationService {
  rpc VerifyDocument(DocumentVerificationRequest) returns (DocumentVerificationResponse);
  rpc VerifyFace(FaceVerificationRequest) returns (FaceVerificationResponse);
  rpc VerifyLiveness(LivenessVerificationRequest) returns (LivenessVerificationResponse);
  rpc MatchBiometrics(BiometricMatchRequest) returns (BiometricMatchResponse);
  rpc ExtractDocumentData(DocumentExtractionRequest) returns (DocumentExtractionResponse);
  rpc VerifyDocumentAuthenticity(AuthenticityRequest) returns (AuthenticityResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/verify/document` | Verify identity document |
| POST | `/api/v1/verify/face` | Verify face against document photo |
| POST | `/api/v1/verify/liveness` | Perform liveness check |
| POST | `/api/v1/verify/biometric-match` | Match biometric templates |
| POST | `/api/v1/extract/document` | Extract structured data from document |
| POST | `/api/v1/verify/authenticity` | Check document authenticity |

## Tenant Isolation Strategy
- **Model isolation**: Per-tenant model configurations and thresholds stored in Redis
- **Queue isolation**: Tenant-scoped task queues in Redis Streams
- **Data isolation**: Document images processed in-memory only; results stored via gRPC to orchestrator
- **Resource quotas**: Per-tenant max concurrent verification jobs, max image size
- **Model versioning**: Per-tenant model version pinning for reproducibility
- **Biometric template isolation**: Templates encrypted with tenant-specific key; never stored in plaintext

## Security Boundaries
- All document images decrypted in-memory only; never written to disk
- Biometric templates hashed with tenant-specific salt + pepper
- Face matching scores thresholded per tenant configuration (default: 0.85 similarity)
- Liveness detection requires challenge-response (randomized head movement / blink)
- Document forensics checks: UV/IR light patterns, microprint, hologram detection
- Anti-spoofing: deepfake detection, screen replay detection, mask detection
- All verification results cryptographically signed with tenant key

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Verification events → structured log → Loki (no PII in logs) |
| Metrics | `identity_verification_total`, `identity_verification_duration_seconds`, `identity_face_match_score`, `identity_liveness_pass_rate` |
| Traces | OpenTelemetry spans per verification step (OCR → face extraction → matching → liveness) |
| Alerts | Verification failure rate > 5%, processing latency > 3s (p99), model inference error rate > 1% |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Model inference timeout | 5s deadline | Return timeout error, queue for retry, alert |
| OCR poor quality | Confidence < 0.7 | Flag for manual review, retry with preprocessing |
| Face match failure | Score < threshold | Secondary check with different model, flag for review |
| Liveness spoof detected | Anti-spoof score < threshold | Immediate rejection, security event logged |
| Document tampering detected | Forensics anomaly | Rejection + fraud alert, evidence preserved |
| GPU/CPU overload | Queue depth > 100 | Horizontal pod autoscaling, backpressure to caller |

## Configuration
```yaml
identity_verification:
  models:
    face_embedding:
      model_path: "/models/face_embedding_v3.onnx"
      input_size: [112, 112]
      embedding_dim: 512
    liveness:
      model_path: "/models/liveness_v2.onnx"
      challenge_types: ["blink", "turn_left", "turn_right", "smile"]
    document_forensics:
      model_path: "/models/document_forensics_v1.onnx"
      checks: ["uv_pattern", "ir_pattern", "microprint", "hologram", "font_analysis"]
  thresholds:
    face_match_min_score: 0.85
    liveness_min_score: 0.90
    document_authenticity_min_score: 0.80
    ocr_min_confidence: 0.70
  processing:
    max_image_size_mb: 10
    supported_formats: ["jpg", "jpeg", "png", "pdf", "tiff"]
    max_concurrent_per_tenant: 50
    timeout_seconds: 30
  security:
    biometric_template_encryption: "aes-256-gcm"
    result_signing: true
    anti_spoofing_enabled: true
    deepfake_detection: true
```

## Dependencies
- `platform-gateway` — Request routing, rate limiting
- `platform-identity` — Service authentication
- `platform-observability` — Metrics, traces, logs
- `platform-secrets` — Model encryption keys, signing keys
- `compute-ml-inference` — Model serving infrastructure
- `data-redis` — Task queues, tenant configuration cache
