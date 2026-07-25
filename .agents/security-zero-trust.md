# Agent: Security Zero Trust

## Metadata
- **Agent ID**: `usora-agent-security-zero-trust`
- **Tier**: 6 — Security & Trust
- **Owner**: Security Engineering
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Security Zero Trust agent implements the foundational zero-trust security architecture for USORA. It enforces "never trust, always verify" principles across all layers: mTLS for all service-to-service communication, SPIFFE/SPIRE service identity, network micro-segmentation, and continuous authentication/authorization at every access point.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Service Mesh | Istio / Cilium Service Mesh | 1.22+ |
| mTLS | Istio mTLS / cert-manager | — |
| Service Identity | SPIFFE / SPIRE | 1.9+ |
| Network Policies | Cilium Network Policies | 1.16+ |
| WAF | AWS WAF v2 / custom Rust WAF | latest |
| DDoS Protection | AWS Shield Advanced | — |
| Identity Proxy | OAuth2 Proxy / Istio External Auth | latest |

## API Surface

### gRPC Services
```protobuf
service ZeroTrustService {
  rpc ValidateServiceIdentity(ServiceIdentityRequest) returns (ServiceIdentityResponse);
  rpc AuthorizeNetworkAccess(NetworkAccessRequest) returns (NetworkAccessResponse);
  rpc GetSecurityPosture(SecurityPostureRequest) returns (SecurityPostureResponse);
  rpc EnforceMicroSegmentation(SegmentationRequest) returns (SegmentationResponse);
  rpc RotateServiceCertificates(CertRotationRequest) returns (CertRotationResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/v1/zero-trust/identity/{serviceId}` | Validate service identity |
| POST | `/api/v1/zero-trust/network/authorize` | Authorize network access |
| GET | `/api/v1/zero-trust/posture` | Get security posture |
| POST | `/api/v1/zero-trust/segmentation/enforce` | Enforce micro-segmentation |
| POST | `/api/v1/zero-trust/certificates/rotate` | Rotate service certificates |

## Tenant Isolation Strategy
- **Service identity namespace**: SPIFFE IDs include tenant: `spiffe://usora.io/tenant/{tid}/service/{name}`
- **Network segmentation**: Per-tenant network policies; no cross-tenant pod communication
- **Certificate isolation**: Per-tenant intermediate CAs via cert-manager
- **Policy isolation**: Istio AuthorizationPolicies per tenant namespace
- **Ingress isolation**: Per-tenant Gateway resources with separate TLS termination
- **Egress isolation**: Egress gateways per tenant; no direct internet access from tenant pods

## Security Boundaries
- All pod-to-pod communication over mTLS (PERMISSIVE → STRICT mode migration)
- Service identity verified via SPIFFE/SPIRE at every connection establishment
- Network policies: default-deny all traffic; explicit allow rules only
- No service account token access from application pods (IRSA only)
- Pod security standards: restricted profile enforced via Gatekeeper/Kyverno
- Runtime security: Falco for syscall anomaly detection
- Image provenance: Sigstore cosign signature verification before deployment
- Supply chain: SLSA Level 3 compliance for all artifacts

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Istio access logs → Loki; Falco alerts → Loki |
| Metrics | `zero_trust_mtls_connections_total`, `zero_trust_authz_denied_total`, `zero_trust_certificate_expiry_days`, `zero_trust_network_policy_violations_total` |
| Traces | OpenTelemetry spans with security context; mTLS handshake traces |
| Alerts | Authz denied rate > 0.1%, certificate expiry < 7 days, Falco alert, policy violation |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| mTLS handshake failure | Istio proxy metrics | Fallback to PERMISSIVE mode (emergency only), alert, investigate |
| SPIRE server unavailable | Health check | Cached SVIDs valid for 24h; alert, restore SPIRE |
| Certificate expiry | cert-manager metrics | Auto-renewal 30 days before; alert at 14, 7, 1 days |
| Network policy misconfiguration | Denied traffic spike | Alert, verify policy, rollback if needed |
| Falco false positive | Alert fatigue | Tune rules, whitelist known patterns, alert |
| Service identity spoofing | SPIFFE validation failure | Block connection, alert security, incident response |

## Configuration
```yaml
zero_trust:
  istio:
    mtls_mode: "STRICT"
    proxy:
      concurrency: 2
      resources:
        requests:
          cpu: "100m"
          memory: "128Mi"
        limits:
          cpu: "500m"
          memory: "256Mi"
  spire:
    server:
      replicas: 3
      database: "postgresql"
    agent:
      socket_path: "/run/spire/sockets/agent.sock"
    svid_ttl: "86400"  # 24 hours
  cert_manager:
    issuer: "vault-issuer"
    renewal_before: "720h"  # 30 days
    key_algorithm: "ECDSA"
    key_size: 256
  network_policies:
    default_deny: true
    allow_dns: true
    allow_istio_control_plane: true
    allow_observability: true
  falco:
    enabled: true
    rules:
      - "falco_rules.yaml"
      - "falco_rules.local.yaml"
    output:
      http:
        url: "http://falco-sidekick.usora.svc.cluster.local:2801"
  image_verification:
    enabled: true
    cosign:
      key: "/secrets/cosign/cosign.pub"
    slsa_verification: true
```

## Dependencies
- `platform-infra` — Istio, Cilium, SPIRE deployment
- `platform-secrets` — Certificate management, Vault issuer
- `platform-observability` — Falco alerts, metrics, traces
- `platform-identity` — Service account identity mapping
- `devops-cicd` — Image signing, SLSA provenance
