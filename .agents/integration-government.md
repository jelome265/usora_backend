# Agent: Integration Government

## Metadata
- **Agent ID**: `usora-agent-integration-government`
- **Tier**: 8 — Integration & Ecosystem
- **Owner**: Integration Engineering
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Integration Government agent connects to government identity verification APIs including eIDAS, Aadhaar, DMV databases, and passport verification services. It provides authoritative identity verification against government-issued records for high-assurance KYC scenarios.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| eIDAS | eIDAS Node / custom | latest |
| Aadhaar | UIDAI API | latest |
| DMV | State DMV APIs | latest |
| Passport | ICAO PKD / INTERPOL | latest |
| Protocol | SAML / OAuth2 / SOAP / REST | — |
| Cache | Redis | 7.2+ |

## API Surface

### gRPC Services
```protobuf
service GovernmentIntegrationService {
  rpc VerifyEIDAS(EIDASVerificationRequest) returns (EIDASVerificationResponse);
  rpc VerifyAadhaar(AadhaarVerificationRequest) returns (AadhaarVerificationResponse);
  rpc VerifyDMV(DMVVerificationRequest) returns (DMVVerificationResponse);
  rpc VerifyPassport(PassportVerificationRequest) returns (PassportVerificationResponse);
  rpc GetVerificationStatus(VerificationStatusRequest) returns (VerificationStatusResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/government/eidas` | Verify via eIDAS |
| POST | `/api/v1/government/aadhaar` | Verify via Aadhaar |
| POST | `/api/v1/government/dmv` | Verify via DMV |
| POST | `/api/v1/government/passport` | Verify via passport |
| GET | `/api/v1/government/status/{verificationId}` | Get verification status |

## Tenant Isolation Strategy
- **Jurisdiction isolation**: Per-tenant enabled government integrations
- **Data isolation**: Verification results encrypted per tenant
- **Rate limit isolation**: Per-tenant API quotas
- **Audit isolation**: Per-tenant verification audit trails

## Security Boundaries
- All government API connections via mTLS
- API keys and certificates stored in Vault
- PII encrypted in transit and at rest
- Verification results signed with tenant key
- Data retention per jurisdiction requirements
- No government data cached beyond TTL

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Verification events → structured audit log → Loki |
| Metrics | `government_verification_total`, `government_verification_latency_seconds`, `government_provider_error_rate` |
| Traces | OpenTelemetry spans per verification |
| Alerts | Provider error rate > 5%, latency > 5s, certificate expiry < 30 days |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Government API down | HTTP error | Circuit breaker, fallback to alternative provider, alert |
| Certificate expiry | Scheduled check | Auto-renewal, alert at 30/14/7 days |
| Rate limit exceeded | 429 response | Backoff, queue, alert |
| Verification mismatch | Result discrepancy | Flag for manual review, alert |

## Configuration
```yaml
government:
  eidas:
    enabled: true
    node_url: "${VAULT:eidas_node_url}"
    certificate: "/secrets/eidas/cert.pem"
    private_key: "/secrets/eidas/key.pem"
    supported_countries: ["DE", "FR", "IT", "ES", "NL"]
  aadhaar:
    enabled: true
    api_url: "https://uidai.gov.in/auth/"
    license_key: "${VAULT:aadhaar_license_key}"
    asa_code: "${VAULT:aadhaar_asa_code}"
    aua_code: "${VAULT:aadhaar_aua_code}"
  dmv:
    enabled: true
    providers:
      - state: "CA"
        url: "${VAULT:dmv_ca_url}"
        api_key: "${VAULT:dmv_ca_key}"
      - state: "NY"
        url: "${VAULT:dmv_ny_url}"
        api_key: "${VAULT:dmv_ny_key}"
  passport:
    enabled: true
    icao_pkd:
      url: "${VAULT:icao_pkd_url}"
      certificate: "/secrets/icao/pkd_cert.pem"
    interpol:
      enabled: true
      api_key: "${VAULT:interpol_api_key}"
  cache:
    ttl: 1800  # 30 minutes
    max_entries: 5000
```

## Dependencies
- `compute-identity-verification` — Verification results
- `orchestrator-compliance` — Regulatory compliance
- `platform-secrets` — Government API credentials
- `data-redis` — Cache
