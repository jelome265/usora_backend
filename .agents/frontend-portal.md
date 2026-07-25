# Agent: Frontend Portal

## Metadata
- **Agent ID**: `usora-agent-frontend-portal`
- **Tier**: 5 — Frontend & Experience
- **Owner**: Frontend Engineering
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Frontend Portal is the administrative dashboard for USORA tenants. Built with TypeScript 5 and Tailwind 4, it provides case management, tenant configuration, analytics, user management, and compliance reporting with real-time updates via WebSockets and SSE.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Framework | React 19 | 19.0+ |
| Language | TypeScript | 5.6+ |
| Styling | Tailwind CSS | 4.0+ |
| State Management | Zustand / TanStack Query | 5.0+ / 5.59+ |
| Routing | React Router | 7.0+ |
| Charts | Recharts / Tremor | latest |
| Real-time | WebSockets + SSE | — |
| Build | Vite | 6.0+ |
| Testing | Vitest + Playwright | latest |

## API Surface

### Internal APIs (consumed)
| Source | Purpose |
|--------|---------|
| `orchestrator-core` | Case CRUD, tenant config |
| `orchestrator-case` | Case assignment, escalation, resolution |
| `orchestrator-compliance` | Compliance reports, audit trails |
| `compute-risk-scoring` | Risk score visualization |
| `platform-identity` | Auth, RBAC, user management |
| `platform-observability` | Metrics, logs, alerts dashboard |

### WebSocket Events
| Event | Direction | Purpose |
|-------|-----------|---------|
| `case:updated` | Server → Client | Real-time case status updates |
| `case:assigned` | Server → Client | Case assignment notifications |
| `alert:triggered` | Server → Client | System alert notifications |
| `notification:new` | Server → Client | General notifications |

## Tenant Isolation Strategy
- **Subdomain routing**: `https://{tenantId}.admin.usora.io`
- **JWT tenant claim**: All API requests include `tid` claim; server validates
- **UI feature gating**: Per-tenant feature flags from tenant config API
- **Data filtering**: All list views filtered by tenant ID server-side
- **Theme isolation**: Per-tenant branding (logo, colors, favicon) from config
- **Role-based UI**: Components rendered conditionally based on user roles

## Security Boundaries
- CSP headers: strict `default-src`, `script-src`, `style-src`
- XSS protection: React automatic escaping + DOMPurify for rich text
- CSRF tokens on all state-changing requests
- Session timeout: 15 minutes idle, 8 hours absolute
- MFA enforcement for admin roles
- Content Security Policy: no inline scripts, no eval
- Input validation: Zod schemas for all form inputs
- Audit logging: all admin actions logged to backend

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Client-side errors → Sentry → Loki |
| Metrics | Web Vitals (LCP, FID, CLS) → Mimir; custom events: `portal_page_view`, `portal_action_click` |
| Traces | OpenTelemetry browser SDK → Tempo |
| Alerts | Error rate > 1%, LCP > 2.5s, API error rate > 5% |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| API timeout | 10s timeout | Retry with backoff, show skeleton UI, notify user |
| WebSocket disconnect | Connection close | Auto-reconnect with exponential backoff, queue updates |
| Auth token expiry | 401 response | Silent refresh via refresh token, redirect to login if failed |
| Feature flag fetch failure | Config API error | Fallback to default features, alert |
| Chart data load failure | API error | Show error state with retry button |
| Real-time update miss | Sequence gap detection | Full refresh of affected data |

## Configuration
```yaml
frontend_portal:
  build:
    bundler: "vite"
    target: "es2022"
    source_map: true  # Production: false
  api:
    base_url: "https://api.usora.io"
    timeout: 10000
    retry_attempts: 3
  websocket:
    url: "wss://realtime.usora.io"
    reconnect_interval: 3000
    max_reconnect_attempts: 10
  auth:
    session_timeout_minutes: 15
    absolute_timeout_hours: 8
    mfa_required_roles: ["admin", "compliance_officer"]
  features:
    case_management: true
    analytics_dashboard: true
    compliance_reporting: true
    user_management: true
    tenant_configuration: true
    audit_log_viewer: true
  theming:
    default_primary_color: "#0f172a"
    default_font: "Inter"
    tenant_customization: true
```

## Dependencies
- `platform-gateway` — API proxy, rate limiting
- `platform-identity` — Authentication, RBAC
- `orchestrator-core` — Business data
- `orchestrator-case` — Case management
- `orchestrator-compliance` — Compliance data
- `platform-observability` — Metrics, error tracking
