# Agent: Compute Document Analysis

## Metadata
- **Agent ID**: `usora-agent-compute-document-analysis`
- **Tier**: 3 — Compute & Verification
- **Owner**: ML Engineering / Document Intelligence
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Compute Document Analysis agent performs deep forensic analysis on identity documents to detect forgery, tampering, and manipulation. It extracts structured data, validates cross-references, checks security features, and provides confidence scores for document authenticity using computer vision and ML models.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Runtime | Rust | 1.82+ |
| Async Runtime | Tokio | 1.40+ |
| gRPC | tonic | 0.12+ |
| Image Processing | OpenCV Rust / image-rs | latest |
| Forensics ML | ONNX Runtime | 1.19+ |
| OCR | Tesseract / AWS Textract | latest |
| Document Templates | SQLite + custom matcher | — |
| Hash Verification | SHA-256 / Blake3 | — |
| Metadata Analysis | exiftool / custom EXIF parser | latest |

## API Surface

### gRPC Services
```protobuf
service DocumentAnalysisService {
  rpc AnalyzeDocument(DocumentAnalysisRequest) returns (DocumentAnalysisResponse);
  rpc DetectForgery(ForgeryDetectionRequest) returns (ForgeryDetectionResponse);
  rpc ExtractMetadata(MetadataExtractionRequest) returns (MetadataExtractionResponse);
  rpc ValidateCrossReference(CrossReferenceRequest) returns (CrossReferenceResponse);
  rpc CheckSecurityFeatures(SecurityFeaturesRequest) returns (SecurityFeaturesResponse);
  rpc GetDocumentTemplate(TemplateRequest) returns (TemplateResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/documents/analyze` | Full document analysis |
| POST | `/api/v1/documents/forgery-check` | Forgery detection only |
| POST | `/api/v1/documents/metadata` | Extract image metadata |
| POST | `/api/v1/documents/cross-reference` | Validate data consistency |
| POST | `/api/v1/documents/security-features` | Check security features |
| GET | `/api/v1/documents/templates/{country}/{type}` | Get document template |

## Tenant Isolation Strategy
- **Template isolation**: Per-tenant document template overrides in SQLite
- **Model isolation**: Per-tenant forgery detection thresholds
- **Queue isolation**: Tenant-scoped analysis queues
- **Result isolation**: Analysis results encrypted per tenant key
- **Rule isolation**: Per-tenant custom validation rules
- **Whitelist isolation**: Per-tenant approved document types and issuers

## Security Boundaries
- Document images processed entirely in-memory; no disk persistence
- EXIF metadata stripped before analysis to prevent metadata-based attacks
- Template database cryptographically signed; tamper detection on load
- Cross-reference validation includes checksums of government registries
- Forgery detection uses ensemble of 5+ models; consensus required
- Security feature checks: UV fluorescence, IR absorption, hologram verification
- All analysis results signed with tenant-specific HMAC

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Analysis events → structured log → Loki (no image data) |
| Metrics | `document_analysis_total`, `document_forgery_detected_total`, `document_analysis_duration_seconds`, `document_template_match_rate` |
| Traces | OpenTelemetry spans: preprocessing → forensics → OCR → cross-reference → scoring |
| Alerts | Forgery detection rate > 10%, analysis latency > 5s (p99), template database corruption |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Template mismatch | Low match score | Flag for manual review, use closest template |
| Forgery model timeout | 3s deadline | Skip model, use remaining ensemble, flag uncertainty |
| Metadata extraction failure | Parser error | Skip metadata check, continue with visual analysis |
| Cross-reference unavailable | Registry API timeout | Use cached registry snapshot (max 24h), flag staleness |
| Security feature check failure | Hardware unavailable | Skip hardware checks, rely on ML forensics, flag |
| Image corruption | Decode error | Reject immediately, request resubmission |

## Configuration
```yaml
document_analysis:
  forensics:
    models:
      - path: "/models/forgery_detection_v4.onnx"
        weight: 0.25
      - path: "/models/manipulation_detection_v2.onnx"
        weight: 0.25
      - path: "/models/print_analysis_v1.onnx"
        weight: 0.20
      - path: "/models/font_analysis_v1.onnx"
        weight: 0.15
      - path: "/models/texture_analysis_v2.onnx"
        weight: 0.15
    consensus_threshold: 0.70
  security_features:
    uv_check: true
    ir_check: true
    hologram_check: true
    microprint_check: true
    watermark_check: true
  cross_reference:
    sources:
      - name: "mrz_database"
        url: "${VAULT:mrz_db_url}"
        timeout: 2000
      - name: "issuer_registry"
        url: "${VAULT:issuer_registry_url}"
        timeout: 3000
    cache_ttl: 86400
  templates:
    database_path: "/data/document_templates.db"
    auto_update: true
    update_interval: "24h"
  processing:
    max_image_size_mb: 20
    supported_formats: ["jpg", "jpeg", "png", "pdf", "tiff", "bmp"]
    timeout_seconds: 15
    max_concurrent_per_tenant: 30
```

## Dependencies
- `platform-gateway` — Request routing
- `platform-identity` — Service authentication
- `platform-observability` — Metrics, traces, logs
- `platform-secrets` — Model keys, registry credentials
- `compute-identity-verification` — Face extraction from documents
- `compute-ml-inference` — Model serving
- `data-redis` — Template cache, result cache
