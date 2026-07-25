# Agent: Integration Banking

## Metadata
- **Agent ID**: `usora-agent-integration-banking`
- **Tier**: 8 — Integration & Ecosystem
- **Owner**: Integration Engineering
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Integration Banking agent manages connections to open banking APIs, account verification services, and transaction data providers for enhanced KYC verification. It enables real-time bank account verification, income verification, and transaction analysis as part of the KYC pipeline.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Open Banking | Open Banking API / Plaid / Yodlee | latest |
| Protocol | OAuth2 + mTLS | — |
| Cache | Redis | 7.2+ |
| Circuit Breaker | Resilience4j / custom Rust | latest |
| Rate Limiting | Redis token bucket | — |

## API Surface

### gRPC Services
```protobuf
service BankingIntegrationService {
  rpc InitiateAccountLinking(AccountLinkingRequest) returns (AccountLinkingResponse);
  rpc VerifyAccount(AccountVerificationRequest) returns (AccountVerificationResponse);
  rpc GetTransactionHistory(TransactionHistoryRequest) returns (TransactionHistoryResponse);
  rpc VerifyIncome(IncomeVerificationRequest) returns (IncomeVerificationResponse);
  rpc GetAccountBalance(BalanceRequest) returns (BalanceResponse);
  rpc DisconnectAccount(DisconnectRequest) returns (DisconnectResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/banking/link` | Initiate account linking |
| POST | `/api/v1/banking/verify` | Verify bank account |
| GET | `/api/v1/banking/transactions` | Get transaction history |
| POST | `/api/v1/banking/income` | Verify income |
| GET | `/api/v1/banking/balance` | Get account balance |
| POST | `/api/v1/banking/disconnect` | Disconnect account |

## Tenant Isolation Strategy
- **Provider isolation**: Per-tenant banking provider configuration
- **Data isolation**: Bank data encrypted per tenant key
- **Rate limit isolation**: Per-tenant API rate limits
- **Cache isolation**: Per-tenant cached bank data

## Security Boundaries
- All bank connections via OAuth2 with PKCE
- mTLS for provider connections
- Bank credentials never stored; only access tokens (encrypted)
- Transaction data PII redacted in logs
- Account linking requires explicit user consent
- Data retention: bank data purged after KYC completion per tenant policy

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Integration events → structured log → Loki (PII redacted) |
| Metrics | `banking_linking_total`, `banking_verification_total`, `banking_api_latency_seconds`, `banking_provider_error_rate` |
| Traces | OpenTelemetry spans: request → provider → response → cache |
| Alerts | Provider error rate > 5%, latency > 3s, rate limit approaching |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Provider API failure | HTTP error | Circuit breaker, fallback to cached data, alert |
| OAuth token expiry | 401 response | Auto-refresh token, retry, alert if refresh fails |
| Rate limit exceeded | 429 response | Backoff with jitter, queue requests, alert |
| Account linking failure | User abandonment | Retry with guidance, fallback to manual verification |

## Configuration
```yaml
banking:
  providers:
    - name: "plaid"
      enabled: true
      client_id: "${VAULT:plaid_client_id}"
      secret: "${VAULT:plaid_secret}"
      environment: "production"
      products: ["auth", "transactions", "income"]
    - name: "yodlee"
      enabled: true
      client_id: "${VAULT:yodlee_client_id}"
      secret: "${VAULT:yodlee_secret}"
      environment: "production"
    - name: "open_banking_eu"
      enabled: true
      tpp_id: "${VAULT:open_banking_tpp_id}"
      certificates: "/secrets/open_banking/"
  cache:
    ttl: 3600  # 1 hour
    max_entries: 10000
  circuit_breaker:
    failure_threshold: 5
    wait_duration: 30000  # 30s
    half_open_max_calls: 3
  rate_limiting:
    requests_per_minute: 100
    burst_size: 150
```

## Dependencies
- `orchestrator-core` — Business integration
- `compute-risk-scoring` — Transaction risk analysis
- `platform-secrets` — Provider credentials
- `data-redis` — Cache, rate limiting
