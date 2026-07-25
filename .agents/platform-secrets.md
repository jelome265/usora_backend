# Agent: Platform Secrets

## Metadata
- **Agent ID**: `usora-agent-platform-secrets`
- **Tier**: 1 — Core Platform
- **Owner**: Security Engineering
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Platform Secrets agent manages the complete lifecycle of sensitive credentials, encryption keys, certificates, and configuration secrets across all USORA tenants. It provides secure storage, dynamic secret generation, automatic rotation, and Hardware Security Module (HSM) integration with zero-trust access controls.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Secret Store | HashiCorp Vault | 1.18+ |
| HSM | Thales Luna 7 / AWS CloudHSM | latest |
| Key Management | Vault Transit / AWS KMS / GCP Cloud KMS | latest |
| Certificate Authority | Vault PKI / cert-manager | latest |
| Secret Injection | Vault Agent Injector / External Secrets Operator | latest |
| Encryption | AES-256-GCM / ChaCha20-Poly1305 | — |

## API Surface

### gRPC Services
```protobuf
service SecretsService {
  rpc GetSecret(SecretReadRequest) returns (SecretReadResponse);
  rpc PutSecret(SecretWriteRequest) returns (SecretWriteResponse);
  rpc RotateSecret(SecretRotationRequest) returns (SecretRotationResponse);
  rpc GenerateDynamicCredentials(DynamicCredRequest) returns (DynamicCredResponse);
  rpc SignCertificate(CSRRequest) returns (CertificateResponse);
  rpc RevokeCertificate(RevocationRequest) returns (RevocationResponse);
}
```

### REST Endpoints (Vault-native, wrapped)
| Method | Path | Purpose |
|--------|------|---------|
| GET | `/v1/secret/data/{path}` | Read secret (KV v2) |
| POST | `/v1/secret/data/{path}` | Write secret (KV v2) |
| POST | `/v1/transit/encrypt/{key}` | Encrypt data |
| POST | `/v1/transit/decrypt/{key}` | Decrypt data |
| POST | `/v1/pki/issue/{role}` | Issue certificate |
| POST | `/v1/database/creds/{role}` | Generate dynamic DB credentials |

## Tenant Isolation Strategy
- **Mount isolation**: Per-tenant secret engines: `secret/tenants/{tid}/`
- **Policy isolation**: Vault policies scoped to tenant mount paths only
- **Key namespace**: Transit encryption keys per tenant: `transit/keys/tenant-{tid}-{key_name}`
- **Certificate isolation**: Per-tenant PKI roles and intermediate CAs
- **Dynamic credential namespace**: Database roles prefixed with tenant ID
- **Audit namespace**: Audit logs tagged with `tenant_id` for compliance queries

## Security Boundaries
- All secrets encrypted at rest with HSM-backed master key (FIPS 140-2 Level 3)
- Transit encryption keys never leave HSM; Vault only handles ciphertext
- Dynamic credentials auto-expire (default TTL: 1h for DB, 24h for cloud)
- Certificate rotation every 90 days; automatic revocation on compromise
- Secret access logged with requestor identity, timestamp, and justification
- No plaintext secrets in environment variables; injection via Vault Agent sidecar
- Seal/unseal requires quorum of unseal keys (Shamir's Secret Sharing, 3-of-5)

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Vault audit logs → Loki; structured with `tenant_id`, `accessor`, `operation` |
| Metrics | `vault_secret_read_total`, `vault_secret_rotation_total`, `vault_certificate_issue_total`, `vault_hsm_latency_seconds` |
| Traces | OpenTelemetry spans on secret operations |
| Alerts | Secret access anomaly > 3 sigma, HSM connection failure, certificate expiry < 7 days |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Vault seal | Health check / 503 response | Auto-unseal via cloud KMS (AWS/GCP/Azure), alert on-call |
| HSM connection loss | Latency spike / timeout | Fail-closed (deny all secret access), queue requests, alert |
| Secret rotation failure | Rotation job error | Alert, manual intervention, fallback to current secret |
| Certificate expiry | Scheduled check | Auto-renewal 30 days before expiry; alert at 14, 7, 1 days |
| Policy misconfiguration | Access denied anomaly | Audit log review, policy rollback, incident response |

## Configuration
```yaml
secrets:
  vault:
    address: "https://vault.usora.svc.cluster.local:8200"
    tls:
      cert_path: "/secrets/vault/tls/cert.pem"
      key_path: "/secrets/vault/tls/key.pem"
    seal:
      type: "awskms"
      region: "us-east-1"
      kms_key_id: "alias/usora-vault-unseal"
  transit:
    default_key_type: "aes256-gcm96"
    auto_rotation_period: "90d"
  pki:
    default_ttl: "90d"
    max_ttl: "365d"
    allowed_domains: ["*.usora.io", "*.internal.usora"]
  database:
    dynamic_ttl: "1h"
    max_ttl: "24h"
    rotation_schedule: "0 2 * * *"  # Daily at 2 AM UTC
  audit:
    enabled: true
    backend: "file"
    file_path: "/audit/vault_audit.log"
    format: "json"
```

## Dependencies
- `platform-identity` — Authentication to Vault via OIDC / Kubernetes auth
- `platform-observability` — Audit log streaming, metrics, alerting
- `data-s3` — Cold storage for audit log archives
