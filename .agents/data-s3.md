# Agent: Data S3

## Metadata
- **Agent ID**: `usora-agent-data-s3`
- **Tier**: 4 — Data & Persistence
- **Owner**: Data Engineering / SRE
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Data S3 agent manages encrypted object storage for all USORA tenant data including identity documents, audit archives, compliance reports, and ML model artifacts. It enforces per-tenant storage isolation, lifecycle policies, cross-region replication, and GDPR-compliant deletion with cryptographic erasure.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Object Storage | Amazon S3 | — |
| Encryption | AWS KMS (SSE-KMS) | — |
| Access Control | S3 Bucket Policies + IAM | — |
| Transfer | AWS Transfer Family / S3 Transfer Acceleration | — |
| Inventory | S3 Inventory + Athena | — |
| Monitoring | S3 CloudWatch Metrics + EventBridge | — |

## API Surface

### gRPC Services
```protobuf
service S3Service {
  rpc UploadObject(UploadRequest) returns (UploadResponse);
  rpc DownloadObject(DownloadRequest) returns (DownloadResponse);
  rpc DeleteObject(DeleteRequest) returns (DeleteResponse);
  rpc ListObjects(ListRequest) returns (ListResponse);
  rpc GetObjectMetadata(MetadataRequest) returns (MetadataResponse);
  rpc GeneratePresignedUrl(PresignedUrlRequest) returns (PresignedUrlResponse);
  rpc SetLifecyclePolicy(LifecyclePolicyRequest) returns (LifecyclePolicyResponse);
  rpc ReplicateObject(ReplicateRequest) returns (ReplicateResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/storage/upload` | Upload object |
| GET | `/api/v1/storage/download/{key}` | Download object |
| DELETE | `/api/v1/storage/{key}` | Delete object |
| GET | `/api/v1/storage/list` | List objects |
| GET | `/api/v1/storage/{key}/metadata` | Get object metadata |
| POST | `/api/v1/storage/presigned-url` | Generate presigned URL |
| PUT | `/api/v1/storage/lifecycle` | Set lifecycle policy |

## Tenant Isolation Strategy
- **Prefix isolation**: Per-tenant S3 prefix: `tenants/{tid}/`
- **Bucket policy isolation**: IAM policies restrict access to tenant prefix only
- **KMS key isolation**: Per-tenant KMS keys for SSE-KMS encryption
- **Lifecycle isolation**: Per-tenant lifecycle rules for retention and deletion
- **Replication isolation**: Per-tenant replication configuration
- **Inventory isolation**: Per-tenant inventory reports
- **Access log isolation**: Per-tenant access log prefixes

## Security Boundaries
- All objects encrypted with SSE-KMS using per-tenant keys
- Presigned URLs expire after 15 minutes maximum
- No public access to any bucket
- Object upload scanned for malware before storage
- Versioning enabled for audit and compliance objects
- MFA delete required for compliance-critical objects
- Cross-region replication encrypted in transit (TLS 1.3)
- Object lock (WORM) enabled for compliance archives

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | S3 access logs → Loki; CloudTrail events → audit log |
| Metrics | `s3_objects_uploaded_total`, `s3_objects_downloaded_total`, `s3_storage_bytes`, `s3_request_duration_seconds`, `s3_replication_lag_seconds` |
| Traces | OpenTelemetry spans: upload → scan → encrypt → store → replicate |
| Alerts | Storage growth > 20% daily, replication lag > 1h, unauthorized access attempts |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Upload failure | S3 error response | Retry with exponential backoff, multipart resume, alert |
| Download failure | 404 / access denied | Check permissions, verify object exists, alert |
| Replication lag | S3 replication metric | Investigate network, verify destination bucket health |
| KMS key rotation failure | KMS API error | Use previous key, alert, manual rotation |
| Lifecycle policy failure | S3 event notification | Manual cleanup, alert, review policy |
| Malware detection | Scan result positive | Quarantine object, alert security, preserve evidence |

## Configuration
```yaml
s3:
  bucket:
    primary: "usora-primary-{region}"
    replication:
      enabled: true
      destination_region: "us-west-2"
      destination_bucket: "usora-replica-us-west-2"
  encryption:
    sse: "aws:kms"
    key_rotation: true
    key_spec: "AES_256"
  versioning:
    enabled: true
    mfa_delete: true
  object_lock:
    enabled: true
    retention_mode: "COMPLIANCE"
    retention_days: 2555  # 7 years
  lifecycle:
    transitions:
      - storage_class: "STANDARD_IA"
        days: 90
      - storage_class: "GLACIER"
        days: 365
      - storage_class: "DEEP_ARCHIVE"
        days: 730
    expiration:
      enabled: false  # Manual GDPR deletion only
  access_control:
    block_public_access: true
    bucket_policy_enforced: true
    presigned_url_max_expiry: 900  # 15 minutes
  quotas:
    default_storage_gb: 1000
    max_object_size_mb: 500
    max_presigned_url_requests_per_minute: 100
```

## Dependencies
- `platform-infra` — S3 bucket provisioning, IAM roles
- `platform-secrets` — KMS key management
- `platform-observability` — Metrics, logs, alerting
- `data-retention` — Lifecycle management, GDPR deletion
- `security-audit` — Access logging, compliance evidence
