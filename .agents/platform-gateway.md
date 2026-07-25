# Agent: Platform Gateway

## Metadata
- **Agent ID**: `usora-agent-platform-gateway`
- **Tier**: 1 — Core Platform
- **Owner**: Platform Engineering
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Platform Gateway is the single entry point for all external traffic into the USORA platform. It is responsible for request routing, Web Application Firewall (WAF), rate limiting, tenant resolution, TLS termination, and protocol translation (HTTP/1.1 → HTTP/2 → gRPC).

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Runtime | Rust | 1.82+ |
| Framework | Axum | 0.7+ |
| Async Runtime | Tokio | 1.40+ |
| TLS | rustls | 0.23+ |
| WAF | custom + libinjection | latest |
| Rate Limiting | Redis (token bucket) | 7.2+ |
| Service Discovery | Consul / Kubernetes DNS | latest |

## API Surface

### gRPC Services (Internal)
```protobuf
service GatewayService {
  rpc ResolveTenant(TenantContext) returns (TenantResolution);
  rpc CheckRateLimit(RateLimitRequest) returns (RateLimitResponse);
  rpc ValidateToken(TokenValidationRequest) returns (TokenValidationResponse);
}
```

### REST Endpoints (External)
| Method | Path | Purpose |
|--------|------|---------|
| `*` | `/api/v1/*` | API proxy to backend services |
| `*` | `/health` | Health check (bypass auth) |
| `*` | `/metrics` | Prometheus metrics (bypass auth, IP-restricted) |

## Tenant Isolation Strategy
- **Header-based resolution**: `X-Tenant-ID` header validated against JWT `tid` claim
- **Path-based fallback**: `/api/v1/{tenantId}/...` for legacy clients
- **Connection pooling**: Per-tenant connection pools to upstream services
- **Rate limiting**: Token bucket per `(tenant_id, client_id, endpoint)` tuple
- **Circuit breaker**: Per-tenant circuit breakers prevent noisy-neighbor failures

## Security Boundaries
- TLS 1.3 mandatory for all external traffic
- mTLS for internal service mesh communication
- Request/response payload size limits (configurable per tenant)
- IP allowlist/blocklist per tenant
- OWASP Top 10 protection via WAF rules
- SQL injection / XSS filtering via libinjection

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Structured JSON via tracing → Loki |
| Metrics | Prometheus: `gateway_requests_total`, `gateway_latency_seconds`, `gateway_rate_limit_hits` |
| Traces | OpenTelemetry → Tempo (W3C Trace Context propagation) |
| Alerts | P99 latency > 100ms, error rate > 0.1%, rate limit saturation > 80% |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Upstream timeout | 5s timeout + circuit breaker | Return 503, trigger alert |
| Rate limit exceeded | Redis counter threshold | Return 429 with `Retry-After` header |
| Tenant resolution failure | JWT validation error | Return 401, log security event |
| WAF rule trigger | Pattern match | Return 403, increment threat counter |
| TLS handshake failure | rustls error | Log, increment metric, close connection |

## Configuration
```yaml
gateway:
  bind_address: "0.0.0.0:8443"
  tls:
    cert_path: "/secrets/tls/cert.pem"
    key_path: "/secrets/tls/key.pem"
    min_version: "1.3"
  rate_limiting:
    backend: "redis"
    default_rps: 100
    burst_size: 150
  waf:
    enabled: true
    rule_set: "owasp_core"
    custom_rules: "/config/waf/custom.rules"
  upstream:
    orchestrator: "http://orchestrator.usora.svc.cluster.local:8080"
    compute: "http://compute.usora.svc.cluster.local:8080"
```

## Dependencies
- `platform-identity` — JWT validation, token introspection
- `data-redis` — rate limiting counters, session cache
- `platform-observability` — tracing, metrics, logging
- `platform-secrets` — TLS certificate rotation
