# Agent: Platform Identity

## Metadata
- **Agent ID**: `usora-agent-platform-identity`
- **Tier**: 1 — Core Platform
- **Owner**: Security Engineering
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Platform Identity agent owns authentication, authorization, and identity lifecycle management across all USORA tenants. It provides OAuth2/OIDC flows, multi-tenant IAM, RBAC/ABAC policies, and session management with strict tenant isolation.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Runtime | Spring Boot | 4.1 |
| JVM | Java 21 LTS | 21 |
| Concurrency | Virtual Threads | — |
| Auth Server | Spring Authorization Server | 1.4+ |
| Token Store | Redis (JWT stateless + refresh token rotation) | 7.2+ |
| Policy Engine | Open Policy Agent (OPA) / Cedar | latest |
| Directory | LDAP / SCIM bridge | — |

## API Surface

### gRPC Services
```protobuf
service IdentityService {
  rpc Authenticate(AuthenticationRequest) returns (AuthenticationResponse);
  rpc Authorize(AuthorizationRequest) returns (AuthorizationResponse);
  rpc IntrospectToken(TokenIntrospectionRequest) returns (TokenIntrospectionResponse);
  rpc RevokeToken(TokenRevocationRequest) returns (TokenRevocationResponse);
  rpc CreateUser(UserCreateRequest) returns (UserCreateResponse);
  rpc UpdateUserRoles(UserRoleUpdateRequest) returns (UserRoleUpdateResponse);
  rpc GetTenantPolicies(TenantPolicyRequest) returns (TenantPolicyResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/oauth2/token` | Token issuance (client credentials, auth code, refresh) |
| POST | `/oauth2/introspect` | Token introspection (RFC 7662) |
| POST | `/oauth2/revoke` | Token revocation (RFC 7009) |
| GET | `/oidc/.well-known/openid-configuration` | OIDC discovery |
| GET | `/oidc/userinfo` | Userinfo endpoint |
| POST | `/api/v1/users` | User provisioning (admin) |
| PUT | `/api/v1/users/{id}/roles` | Role assignment (admin) |

## Tenant Isolation Strategy
- **JWT namespace isolation**: `iss` claim includes tenant subdomain; `tid` claim mandatory
- **Token scope scoping**: Scopes prefixed with tenant ID: `tenant:{tid}:kyc:read`
- **Policy isolation**: OPA policies loaded per tenant; Cedar policies namespace-isolated
- **User namespace**: User IDs are globally unique (`uuid`) but user pools are logically partitioned
- **Session isolation**: Redis keys prefixed with `session:{tenant_id}:`
- **Cross-tenant access**: Explicitly denied at policy engine level; no implicit trust

## Security Boundaries
- All tokens signed with RS256 using per-tenant key pairs (rotated every 90 days)
- Refresh tokens are single-use with rotation on every refresh
- PKCE mandatory for public clients
- MFA enforced for admin roles; adaptive MFA based on risk score
- Password policies configurable per tenant (NIST 800-63B compliant defaults)
- Brute-force protection: exponential backoff via Redis-backed counters
- Session binding to device fingerprint + IP subnet

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Auth events (success/failure/elevation) → structured audit log → immutable store |
| Metrics | `identity_auth_total`, `identity_auth_failure_total`, `identity_token_issued_total`, `identity_policy_eval_duration_seconds` |
| Traces | OpenTelemetry spans across auth flow steps |
| Alerts | Auth failure rate > 5%, token issuance spike > 300%, policy engine latency > 50ms |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Token validation failure | JWT signature/expiration check | Return 401, log security event |
| Policy engine timeout | OPA evaluation timeout | Deny by default, fallback to cached decision |
| Redis session loss | Key miss | Force re-authentication, no silent failure |
| Key rotation in progress | New key not yet propagated | Grace period with dual-key acceptance |
| Directory sync failure | SCIM webhook error | Queue for retry, alert admin |

## Configuration
```yaml
identity:
  jwt:
    algorithm: RS256
    access_token_ttl: 900  # 15 minutes
    refresh_token_ttl: 604800  # 7 days
    rotation_window: 86400  # 1 day grace period
  mfa:
    required_roles: ["admin", "compliance_officer"]
    adaptive_enabled: true
    providers: ["totp", "webauthn", "sms_backup"]
  opa:
    url: "http://opa.usora.svc.cluster.local:8181"
    policy_bundle_path: "/policies"
    decision_cache_ttl: 60
  brute_force:
    max_attempts: 5
    window_seconds: 300
    lockout_duration: 900
```

## Dependencies
- `data-redis` — token store, session cache, brute-force counters
- `platform-secrets` — signing key management, HSM integration
- `platform-observability` — audit logging, security event streaming
- `compute-risk-scoring` — adaptive MFA risk signals
