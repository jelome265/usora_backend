# Agent: Frontend Applicant

## Metadata
- **Agent ID**: `usora-agent-frontend-applicant`
- **Tier**: 5 — Frontend & Experience
- **Owner**: Frontend Engineering / UX
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Frontend Applicant agent provides the end-user KYC submission experience. It guides applicants through identity document upload, biometric capture, liveness checks, and form completion with real-time validation, progress tracking, and multi-language support. Built with TypeScript 5 and Tailwind 4 for performance and accessibility.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Framework | React 19 | 19.0+ |
| Language | TypeScript | 5.6+ |
| Styling | Tailwind CSS | 4.0+ |
| State Management | Zustand | 5.0+ |
| Form Handling | React Hook Form + Zod | 7.53+ / 3.23+ |
| Camera | MediaDevices API + custom WebRTC | — |
| File Upload | Tus / custom resumable upload | latest |
| Animations | Framer Motion | 11.0+ |
| Build | Vite | 6.0+ |
| Testing | Vitest + Playwright | latest |

## API Surface

### Internal APIs (consumed)
| Source | Purpose |
|--------|---------|
| `orchestrator-core` | KYC submission, status tracking |
| `compute-identity-verification` | Document upload, face capture, liveness |
| `compute-risk-scoring` | Risk assessment feedback |
| `platform-identity` | Anonymous session, applicant token |
| `integration-webhook` | Progress notifications |

### WebSocket Events
| Event | Direction | Purpose |
|-------|-----------|---------|
| `kyc:status` | Server → Client | Real-time KYC status updates |
| `kyc:document:processed` | Server → Client | Document processing complete |
| `kyc:biometric:result` | Server → Client | Biometric verification result |
| `upload:progress` | Client → Server | Upload progress (for large files) |

## Tenant Isolation Strategy
- **Subdomain routing**: `https://{tenantId}.apply.usora.io`
- **Tenant config injection**: Branding, fields, flows loaded from tenant config API
- **Session isolation**: Anonymous sessions scoped to tenant; no cross-tenant session sharing
- **Data isolation**: All submissions tagged with tenant ID; UI filters by tenant context
- **Feature gating**: Per-tenant KYC flow steps (document types, biometric requirements)
- **Localization**: Per-tenant default language and supported locales

## Security Boundaries
- Document images client-side encrypted before upload (AES-256-GCM with ephemeral key)
- Camera access: explicit user permission, no background capture
- Biometric data: never stored client-side; streamed to backend via WebRTC
- XSS protection: React automatic escaping, CSP headers
- CSRF protection: double-submit cookie pattern
- Input validation: Zod schemas for all form fields
- Rate limiting: per-session upload limits
- PII minimization: only collect required fields per tenant config

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Client errors → Sentry → Loki; submission events → backend audit log |
| Metrics | `applicant_session_started`, `applicant_document_uploaded`, `applicant_kyc_completed`, `applicant_dropoff_step` |
| Traces | OpenTelemetry browser SDK → Tempo |
| Alerts | Drop-off rate > 50% at any step, upload failure rate > 5%, client error rate > 1% |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Camera access denied | Permission API error | Show manual upload fallback, guide user |
| Upload interrupted | Network error | Resumable upload (Tus), auto-retry, progress preservation |
| Document rejection | Backend validation error | Show specific error, allow re-capture, preserve other data |
| Biometric capture failure | Detection timeout | Retry with guidance, fallback to manual review |
| Session expiry | Token expiration | Save progress to localStorage, prompt re-authentication |
| Backend timeout | 10s API timeout | Retry with backoff, show loading state, notify user |

## Configuration
```yaml
frontend_applicant:
  build:
    bundler: "vite"
    target: "es2022"
    source_map: false
  api:
    base_url: "https://api.usora.io"
    timeout: 15000
    retry_attempts: 3
  upload:
    chunk_size_mb: 5
    max_file_size_mb: 20
    max_files: 5
    resumable: true
    allowed_types: ["image/jpeg", "image/png", "application/pdf"]
  camera:
    resolution: { width: 1920, height: 1080 }
    auto_capture: true
    capture_delay_ms: 2000
    fallback_to_manual: true
  session:
    anonymous_token_ttl: 3600  # 1 hour
    progress_persistence: true
    local_storage_key: "usora_kyc_progress"
  accessibility:
    wcag_level: "AA"
    screen_reader_support: true
    keyboard_navigation: true
    high_contrast_mode: true
  localization:
    default_locale: "en"
    supported_locales: ["en", "es", "fr", "de", "pt", "zh", "ja", "ar"]
    rtl_support: true
```

## Dependencies
- `platform-gateway` — API proxy, rate limiting
- `platform-identity` — Anonymous session management
- `orchestrator-core` — KYC submission API
- `compute-identity-verification` — Document/biometric processing
- `platform-observability` — Error tracking, metrics
