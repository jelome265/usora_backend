# Agent: Integration Credit Bureau

## Metadata
- **Agent ID**: `usora-agent-integration-credit`
- **Tier**: 8 — Integration & Ecosystem
- **Owner**: Integration Engineering
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Integration Credit Bureau agent connects to credit reporting agencies (Experian, Equifax, TransUnion) and alternative data providers for identity verification and risk assessment. It provides credit history, identity verification, and fraud indicators as part of the KYC pipeline.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Bureaus | Experian / Equifax / TransUnion APIs | latest |
| Alternative | LexisNexis / ChexSystems | latest |
| Protocol | SOAP + REST + mTLS | — |
| Cache | Redis | 7.2+ |
| Encryption | AES-256-GCM | — |

## API Surface

### gRPC Services
```protobuf
service CreditBureauService {
  rpc VerifyIdentity(IdentityVerificationRequest) returns (IdentityVerificationResponse);
  rpc GetCreditReport(CreditReportRequest) returns (CreditReportResponse);
  rpc CheckFraudIndicators(FraudCheckRequest) returns (FraudCheckResponse);
  rpc GetAlternativeData(AlternativeDataRequest) returns (AlternativeDataResponse);
  rpc GetCreditScore(CreditScoreRequest) returns (CreditScoreResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/credit/verify` | Verify identity via credit bureau |
| POST | `/api/v1/credit/report` | Get credit report |
| POST | `/api/v1/credit/fraud-check` | Check fraud indicators |
| POST | `/api/v1/credit/alternative` | Get alternative data |
| POST | `/api/v1/credit/score` | Get credit score |

## Tenant Isolation Strategy
- **Provider isolation**: Per-tenant bureau configuration
- **Data isolation**: Credit data encrypted per tenant key
- **Rate limit isolation**: Per-tenant API quotas
- **Report isolation**: Per-tenant credit reports

## Security Boundaries
- All bureau connections via mTLS
- API credentials stored in Vault
- Credit data encrypted in transit and at rest
- PII redacted in logs
- Data retention per FCRA and local regulations
- Consumer consent required for credit pull
- Adverse action notices automated per FCRA

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Credit events → structured audit log → Loki (PII redacted) |
| Metrics | `credit_verification_total`, `credit_report_total`, `credit_api_latency_seconds`, `credit_provider_error_rate` |
| Traces | OpenTelemetry spans per credit operation |
| Alerts | Provider error rate > 5%, latency > 3s, consent violation |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Bureau API down | HTTP error | Circuit breaker, fallback to alternative bureau, alert |
| Consent missing | Validation error | Reject request, prompt for consent, log |
| Rate limit exceeded | 429 response | Backoff, queue, alert |
| Credit freeze | Bureau response code | Flag for manual review, guide user |

## Configuration
```yaml
credit:
  bureaus:
    experian:
      enabled: true
      api_url: "${VAULT:experian_api_url}"
      client_id: "${VAULT:experian_client_id}"
      client_secret: "${VAULT:experian_client_secret}"
      products: ["precise_id", "connect_check"]
    equifax:
      enabled: true
      api_url: "${VAULT:equifax_api_url}"
      client_id: "${VAULT:equifax_client_id}"
      client_secret: "${VAULT:equifax_client_secret}"
      products: ["identity_scan", "fraud_index"]
    transunion:
      enabled: true
      api_url: "${VAULT:transunion_api_url}"
      client_id: "${VAULT:transunion_client_id}"
      client_secret: "${VAULT:transunion_client_secret}"
      products: ["tloxp", "fraud_guard"]
  alternative:
    lexisnexis:
      enabled: true
      api_key: "${VAULT:lexisnexis_api_key}"
    chexsystems:
      enabled: true
      api_key: "${VAULT:chexsystems_api_key}"
  cache:
    ttl: 3600  # 1 hour
    max_entries: 10000
  compliance:
    fcra_compliant: true
    consent_required: true
    adverse_action_notice: true
    data_retention_days: 30
```

## Dependencies
- `compute-risk-scoring` — Credit data for risk models
- `orchestrator-compliance` — Regulatory compliance
- `platform-secrets` — Bureau credentials
- `data-redis` — Cache
