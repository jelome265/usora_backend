# USORA — API Specification Document

## 1. Document Overview

| Field | Value |
|---|---|
| **Version** | 1.0.0 |
| **Protocol** | REST (public) + gRPC (internal) |
| **Base URL** | `https://api.usora.io/v1/{tenant-id}/` |
| **OpenAPI** | 3.1.0 |
| **gRPC** | Protocol Buffers v3 |
| **Auth** | OAuth 2.0 + PKCE / API Key + HMAC / mTLS |
| **Last Updated** | 2026-07-21 |

---

## 2. Authentication

### 2.1 OAuth 2.0 + PKCE (Interactive Flows)

```http
POST /v1/{tenant-id}/auth/token HTTP/1.1
Host: api.usora.io
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
&client_id={client_id}
&code={authorization_code}
&code_verifier={pkce_verifier}
&redirect_uri=https://client-app.com/callback
```

**Response:**
```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIs...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "refresh_token": "rt_abc123...",
  "scope": "verifications:read verifications:write webhooks:manage"
}
```

### 2.2 API Key + HMAC (Server-to-Server)

```http
POST /v1/acme-corp/verifications HTTP/1.1
Host: api.usora.io
X-Api-Key: usora_live_abc123
X-Usora-Timestamp: 1721609460
X-Usora-Nonce: nonce_7f8a9b2c
X-Usora-Signature: sha256=def456...
Content-Type: application/json

{
  "workflow_id": "standard-kyc-v3",
  "user_reference": "user_12345"
}
```

**Signature Generation:**
```
signature = HMAC-SHA256(
  api_secret,
  timestamp + "." + nonce + "." + method + "." + path + "." + sha256(body)
)
```

### 2.3 mTLS (High-Security Integrations)

- Client presents X.509 certificate signed by USORA CA
- Certificate contains tenant_id in Subject CN
- Gateway validates certificate chain and extracts tenant context

---

## 3. Common Headers

### 3.1 Request Headers (Mandatory)

| Header | Value | Description |
|---|---|---|
| `Authorization` | `Bearer {jwt}` or `ApiKey {key}` | Authentication credential |
| `X-Request-ID` | UUID v7 | Unique request identifier (sortable, time-embedded) |
| `X-Tenant-ID` | UUID | Tenant identifier (validated against auth context) |
| `X-Client-Version` | SemVer (e.g., `2.1.0`) | Client SDK version |
| `Content-Type` | `application/json` | Request body format |
| `Accept` | `application/json` or `application/x-protobuf` | Response format preference |

### 3.2 Response Headers

| Header | Value | Description |
|---|---|---|
| `X-Request-ID` | UUID v7 | Echo of request ID for tracing |
| `X-Trace-ID` | Hex string | OpenTelemetry trace ID |
| `X-RateLimit-Limit` | Integer | Request quota per window |
| `X-RateLimit-Remaining` | Integer | Remaining requests in window |
| `X-RateLimit-Reset` | Unix timestamp | Window reset time |
| `X-Usora-Version` | SemVer | API version serving request |

---

## 4. Error Handling

### 4.1 Error Response Format

```json
{
  "error": {
    "code": "VERIFICATION_EXPIRED",
    "message": "The verification session has expired",
    "target": "verification_id",
    "details": [
      {
        "code": "SESSION_TIMEOUT",
        "message": "Session exceeded 60 minute limit",
        "target": "expires_at"
      }
    ],
    "request_id": "550e8400-e29b-41d4-a716-446655440000",
    "timestamp": "2026-07-21T23:31:00Z",
    "documentation_url": "https://docs.usora.io/errors/VERIFICATION_EXPIRED"
  }
}
```

### 4.2 Error Codes

| Code | HTTP | Category | Description |
|---|---|---|---|
| `UNAUTHORIZED` | 401 | Auth | Invalid or missing credentials |
| `TOKEN_EXPIRED` | 401 | Auth | JWT access token expired |
| `TOKEN_REVOKED` | 401 | Auth | JWT token revoked |
| `FORBIDDEN` | 403 | Auth | Insufficient permissions for action |
| `TENANT_NOT_FOUND` | 404 | Tenant | Tenant does not exist or is suspended |
| `TENANT_SUSPENDED` | 403 | Tenant | Tenant account suspended |
| `RATE_LIMITED` | 429 | Quota | Quota exceeded |
| `QUOTA_EXHAUSTED` | 429 | Quota | Monthly verification quota reached |
| `VERIFICATION_NOT_FOUND` | 404 | Resource | Verification ID invalid |
| `VERIFICATION_EXPIRED` | 410 | Resource | Session timeout |
| `VERIFICATION_COMPLETED` | 409 | Resource | Verification already finalized |
| `INVALID_DOCUMENT` | 422 | Validation | Document failed validation |
| `DOCUMENT_TOO_LARGE` | 413 | Validation | Document exceeds 50MB limit |
| `UNSUPPORTED_DOCUMENT_TYPE` | 422 | Validation | Document type not enabled for tenant |
| `BIOMETRIC_FAILED` | 422 | Validation | Liveness or match failed |
| `BIOMETRIC_TIMEOUT` | 408 | Validation | Biometric capture timeout |
| `WORKFLOW_NOT_FOUND` | 404 | Resource | Workflow ID invalid |
| `INVALID_WORKFLOW_STATE` | 409 | Resource | Action not allowed in current state |
| `WEBHOOK_DELIVERY_FAILED` | 502 | Integration | Webhook endpoint unreachable |
| `WEBHOOK_INVALID_URL` | 422 | Integration | Webhook URL failed validation |
| `INTERNAL_ERROR` | 500 | System | Unexpected server error |
| `GATEWAY_TIMEOUT` | 504 | System | Upstream service timeout |
| `SERVICE_UNAVAILABLE` | 503 | System | Temporary service degradation |

---

## 5. REST API Endpoints

### 5.1 Verifications

#### Create Verification

```http
POST /v1/{tenant-id}/verifications
```

**Request Body:**
```json
{
  "workflow_id": "standard-kyc-v3",
  "user_reference": "user_12345",
  "callback_url": "https://acme.com/webhooks/usora",
  "redirect_url": "https://acme.com/onboarding/complete",
  "metadata": {
    "source": "mobile_app",
    "campaign": "summer_2026",
    "locale": "en-US"
  },
  "expires_in_minutes": 60,
  "priority": "standard"
}
```

**Field Definitions:**

| Field | Type | Required | Default | Constraints |
|---|---|---|---|---|
| `workflow_id` | string | Yes | — | Must be active workflow for tenant |
| `user_reference` | string | Yes | — | Max 255 chars, tenant-unique |
| `callback_url` | string | No | null | HTTPS URL, max 2048 chars |
| `redirect_url` | string | No | null | HTTPS URL for post-verification redirect |
| `metadata` | object | No | `{}` | Max 20 keys, values max 1024 chars |
| `expires_in_minutes` | integer | No | 60 | Min 5, max 1440 |
| `priority` | string | No | `"standard"` | Enum: `express`, `standard`, `batch` |

**Response (201 Created):**
```json
{
  "id": "ver_7f8a9b2c3d4e5f6a",
  "tenant_id": "tenant_acme",
  "status": "pending_documents",
  "workflow_id": "standard-kyc-v3",
  "user_reference": "user_12345",
  "priority": "standard",
  "session_url": "https://verify.usora.io/s/7f8a9b2c3d4e5f6a",
  "session_token": "st_abc123...",
  "steps": [
    {
      "id": "document_upload",
      "name": "Document Upload",
      "status": "pending",
      "required": true,
      "order": 1
    },
    {
      "id": "biometric_capture",
      "name": "Biometric Verification",
      "status": "pending",
      "required": true,
      "order": 2
    },
    {
      "id": "address_verification",
      "name": "Address Verification",
      "status": "pending",
      "required": false,
      "order": 3
    }
  ],
  "risk_score": null,
  "result": null,
  "created_at": "2026-07-21T23:31:00Z",
  "expires_at": "2026-07-22T00:31:00Z",
  "completed_at": null,
  "metadata": {
    "source": "mobile_app",
    "campaign": "summer_2026"
  }
}
```

---

#### Get Verification

```http
GET /v1/{tenant-id}/verifications/{verification-id}
```

**Response (200 OK):**
```json
{
  "id": "ver_7f8a9b2c3d4e5f6a",
  "tenant_id": "tenant_acme",
  "status": "document_analyzing",
  "workflow_id": "standard-kyc-v3",
  "user_reference": "user_12345",
  "priority": "standard",
  "session_url": "https://verify.usora.io/s/7f8a9b2c3d4e5f6a",
  "steps": [
    {
      "id": "document_upload",
      "name": "Document Upload",
      "status": "completed",
      "required": true,
      "order": 1,
      "completed_at": "2026-07-21T23:32:15Z",
      "result": {
        "document_type": "passport",
        "country_code": "US",
        "extracted_data": {
          "full_name": "John Michael Doe",
          "date_of_birth": "1990-05-15",
          "document_number": "P123456789",
          "expiry_date": "2030-05-15",
          "nationality": "USA"
        },
        "authenticity_score": 0.98,
        "tampering_detected": false
      }
    },
    {
      "id": "biometric_capture",
      "name": "Biometric Verification",
      "status": "pending",
      "required": true,
      "order": 2
    }
  ],
  "risk_score": null,
  "result": null,
  "created_at": "2026-07-21T23:31:00Z",
  "expires_at": "2026-07-22T00:31:00Z",
  "completed_at": null,
  "metadata": { ... }
}
```

---

#### List Verifications

```http
GET /v1/{tenant-id}/verifications
```

**Query Parameters:**

| Parameter | Type | Default | Description |
|---|---|---|---|
| `status` | string | — | Filter by status (comma-separated) |
| `user_reference` | string | — | Filter by user reference |
| `workflow_id` | string | — | Filter by workflow |
| `from` | ISO 8601 | — | Created after |
| `to` | ISO 8601 | — | Created before |
| `risk_level` | string | — | `low`, `medium`, `high`, `critical` |
| `sort` | string | `-created_at` | Sort field and direction |
| `limit` | integer | 20 | Max 100 |
| `cursor` | string | — | Pagination cursor |

**Response (200 OK):**
```json
{
  "data": [
    { /* verification object */ },
    { /* verification object */ }
  ],
  "pagination": {
    "limit": 20,
    "next_cursor": "eyJpZCI6InZlcl94eXo...",
    "has_more": true,
    "total_count": 1543
  }
}
```

---

#### Cancel Verification

```http
POST /v1/{tenant-id}/verifications/{verification-id}/cancel
```

**Request Body:**
```json
{
  "reason": "user_request",
  "notes": "User decided to verify later"
}
```

**Response (200 OK):**
```json
{
  "id": "ver_7f8a9b2c3d4e5f6a",
  "status": "cancelled",
  "cancelled_at": "2026-07-21T23:35:00Z",
  "cancel_reason": "user_request"
}
```

---

### 5.2 Documents

#### Upload Document

```http
POST /v1/{tenant-id}/verifications/{verification-id}/documents
Content-Type: multipart/form-data
```

**Form Fields:**

| Field | Type | Required | Description |
|---|---|---|---|
| `document` | file | Yes | Image file (JPG, PNG, PDF, TIFF) |
| `document_type` | string | Yes | `passport`, `drivers_license`, `national_id`, etc. |
| `country_code` | string | Yes | ISO 3166-1 alpha-2 (e.g., `US`) |
| `side` | string | No | `front`, `back` (for two-sided documents) |

**Response (202 Accepted):**
```json
{
  "document_id": "doc_a1b2c3d4",
  "verification_id": "ver_7f8a9b2c3d4e5f6a",
  "status": "processing",
  "document_type": "passport",
  "country_code": "US",
  "side": "front",
  "uploaded_at": "2026-07-21T23:32:00Z",
  "estimated_completion": "2026-07-21T23:32:05Z"
}
```

---

#### Get Document Analysis

```http
GET /v1/{tenant-id}/verifications/{verification-id}/documents/{document-id}
```

**Response (200 OK):**
```json
{
  "document_id": "doc_a1b2c3d4",
  "verification_id": "ver_7f8a9b2c3d4e5f6a",
  "status": "completed",
  "document_type": "passport",
  "country_code": "US",
  "side": "front",
  "extracted_data": {
    "full_name": "John Michael Doe",
    "first_name": "John",
    "middle_name": "Michael",
    "last_name": "Doe",
    "date_of_birth": "1990-05-15",
    "document_number": "P123456789",
    "document_expiry": "2030-05-15",
    "nationality": "USA",
    "sex": "M",
    "place_of_birth": "New York, NY",
    "mrz": "P<USADOE<<JOHN<MICHAEL<<<<<<<<<<<<<<<<<<<<<P1234567890USA9005158M3005157<<<<<<<<<<<<<<08"
  },
  "authenticity": {
    "score": 0.98,
    "checks": {
      "template_match": { "passed": true, "score": 0.99 },
      "hologram_detection": { "passed": true, "score": 0.97 },
      "uv_ir_analysis": { "passed": true, "score": 0.96 },
      "microprint_check": { "passed": true, "score": 0.98 }
    }
  },
  "tampering": {
    "detected": false,
    "checks": {
      "pixel_analysis": { "passed": true, "anomalies": [] },
      "font_consistency": { "passed": true, "score": 0.99 },
      "metadata_forensics": { "passed": true, "indicators": [] }
    }
  },
  "risk_flags": [],
  "processing_time_ms": 2340,
  "completed_at": "2026-07-21T23:32:04Z"
}
```

---

### 5.3 Biometrics

#### Submit Biometric Capture

```http
POST /v1/{tenant-id}/verifications/{verification-id}/biometrics
Content-Type: multipart/form-data
```

**Form Fields:**

| Field | Type | Required | Description |
|---|---|---|---|
| `capture` | file | Yes | Image or video file |
| `type` | string | Yes | `facial`, `fingerprint`, `voice`, `iris` |
| `liveness_challenge` | object | No | Challenge-response data for active liveness |

**Response (202 Accepted):**
```json
{
  "biometric_id": "bio_e5f6a7b8",
  "verification_id": "ver_7f8a9b2c3d4e5f6a",
  "type": "facial",
  "status": "processing",
  "submitted_at": "2026-07-21T23:33:00Z",
  "estimated_completion": "2026-07-21T23:33:03Z"
}
```

---

#### Get Biometric Analysis

```http
GET /v1/{tenant-id}/verifications/{verification-id}/biometrics/{biometric-id}
```

**Response (200 OK):**
```json
{
  "biometric_id": "bio_e5f6a7b8",
  "verification_id": "ver_7f8a9b2c3d4e5f6a",
  "type": "facial",
  "status": "completed",
  "liveness": {
    "passed": true,
    "method": "passive_active",
    "confidence": 0.99,
    "deepfake_probability": 0.001,
    "checks": {
      "texture_analysis": { "passed": true, "score": 0.99 },
      "depth_estimation": { "passed": true, "score": 0.98 },
      "temporal_consistency": { "passed": true, "score": 0.97 },
      "challenge_response": { "passed": true, "score": 0.99 }
    }
  },
  "match": {
    "score": 0.94,
    "threshold": 0.85,
    "passed": true,
    "comparison_type": "document_photo",
    "template_id": "tmpl_abc123"
  },
  "quality": {
    "score": 0.96,
    "lighting": "good",
    "blur": "none",
    "occlusion": "none"
  },
  "processing_time_ms": 1850,
  "completed_at": "2026-07-21T23:33:02Z"
}
```

---

### 5.4 Risk Scoring

#### Get Risk Score

```http
GET /v1/{tenant-id}/verifications/{verification-id}/risk
```

**Response (200 OK):**
```json
{
  "verification_id": "ver_7f8a9b2c3d4e5f6a",
  "tenant_id": "tenant_acme",
  "overall_score": 23.5,
  "risk_level": "low",
  "thresholds": {
    "low": 30.0,
    "medium": 70.0,
    "high": 90.0
  },
  "signals": [
    {
      "category": "document",
      "weight": 0.25,
      "score": 15.0,
      "factors": [
        { "name": "authenticity", "score": 2.0, "description": "High authenticity confidence" },
        { "name": "template_match", "score": 5.0, "description": "Known template matched" },
        { "name": "fraud_pattern", "score": 8.0, "description": "No known fraud patterns detected" }
      ]
    },
    {
      "category": "biometric",
      "weight": 0.20,
      "score": 4.0,
      "factors": [
        { "name": "face_match", "score": 3.0, "description": "Strong face match (0.94)" },
        { "name": "liveness", "score": 1.0, "description": "High liveness confidence" }
      ]
    },
    {
      "category": "behavioral",
      "weight": 0.15,
      "score": 2.5,
      "factors": [
        { "name": "session_duration", "score": 1.5, "description": "Normal session duration" },
        { "name": "device_reputation", "score": 1.0, "description": "Device has positive reputation" }
      ]
    }
  ],
  "model_version": "risk-v3.2.1",
  "computed_at": "2026-07-21T23:34:00Z"
}
```

---

### 5.5 Watchlist Screening

#### Screen Identity

```http
POST /v1/{tenant-id}/watchlists/screen
```

**Request Body:**
```json
{
  "full_name": "John Michael Doe",
  "date_of_birth": "1990-05-15",
  "nationality": "USA",
  "document_number": "P123456789",
  "lists": ["pep", "sanctions", "adverse_media"],
  "fuzzy_matching": true,
  "match_threshold": 0.85
}
```

**Response (200 OK):**
```json
{
  "screening_id": "scr_c9d0e1f2",
  "tenant_id": "tenant_acme",
  "status": "completed",
  "lists_screened": ["pep", "sanctions", "adverse_media"],
  "matches": [
    {
      "match_id": "match_001",
      "list": "pep",
      "match_score": 0.92,
      "entity": {
        "name": "John M. Doe",
        "date_of_birth": "1989-05-15",
        "nationality": "USA",
        "position": "Former Deputy Minister of Finance",
        "country": "United States",
        "pep_level": "national"
      },
      "match_factors": [
        { "field": "name", "similarity": 0.95 },
        { "field": "date_of_birth", "similarity": 0.89, "note": "Year differs by 1" }
      ],
      "requires_review": true
    }
  ],
  "total_matches": 1,
  "false_positive_probability": 0.15,
  "screened_at": "2026-07-21T23:35:00Z"
}
```

---

### 5.6 Webhooks

#### Register Webhook

```http
POST /v1/{tenant-id}/webhooks
```

**Request Body:**
```json
{
  "url": "https://acme.com/webhooks/usora",
  "events": [
    "verification.created",
    "verification.completed",
    "verification.approved",
    "verification.rejected",
    "document.processed",
    "biometric.processed"
  ],
  "secret": "whsec_custom_secret_123",
  "active": true,
  "metadata": {
    "environment": "production",
    "team": "onboarding"
  }
}
```

**Response (201 Created):**
```json
{
  "webhook_id": "whk_a3b4c5d6",
  "tenant_id": "tenant_acme",
  "url": "https://acme.com/webhooks/usora",
  "events": [ ... ],
  "active": true,
  "delivery_stats": {
    "total_delivered": 0,
    "total_failed": 0,
    "last_delivery": null
  },
  "created_at": "2026-07-21T23:36:00Z"
}
```

---

#### List Webhooks

```http
GET /v1/{tenant-id}/webhooks
```

**Response (200 OK):**
```json
{
  "data": [
    {
      "webhook_id": "whk_a3b4c5d6",
      "url": "https://acme.com/webhooks/usora",
      "events": [ "verification.completed", "verification.approved" ],
      "active": true,
      "delivery_stats": {
        "total_delivered": 15420,
        "total_failed": 3,
        "last_delivery": "2026-07-21T23:30:00Z",
        "last_status": 200
      }
    }
  ]
}
```

---

#### Delete Webhook

```http
DELETE /v1/{tenant-id}/webhooks/{webhook-id}
```

**Response (204 No Content)**

---

### 5.7 Reports

#### Generate Compliance Report

```http
POST /v1/{tenant-id}/reports
```

**Request Body:**
```json
{
  "type": "compliance",
  "format": "pdf",
  "date_range": {
    "from": "2026-06-01T00:00:00Z",
    "to": "2026-06-30T23:59:59Z"
  },
  "filters": {
    "status": ["approved", "rejected"],
    "workflow_id": "standard-kyc-v3"
  },
  "delivery": {
    "method": "email",
    "recipients": ["compliance@acme.com"]
  }
}
```

**Response (202 Accepted):**
```json
{
  "report_id": "rpt_f7g8h9i0",
  "tenant_id": "tenant_acme",
  "type": "compliance",
  "status": "generating",
  "estimated_completion": "2026-07-21T23:38:00Z",
  "download_url": null,
  "created_at": "2026-07-21T23:37:00Z"
}
```

---

#### Get Report Status

```http
GET /v1/{tenant-id}/reports/{report-id}
```

**Response (200 OK):**
```json
{
  "report_id": "rpt_f7g8h9i0",
  "tenant_id": "tenant_acme",
  "type": "compliance",
  "status": "completed",
  "download_url": "https://cdn.usora.io/reports/tenant_acme/rpt_f7g8h9i0.pdf?token=...",
  "expires_at": "2026-07-28T23:37:00Z",
  "file_size": 2457600,
  "created_at": "2026-07-21T23:37:00Z",
  "completed_at": "2026-07-21T23:37:45Z"
}
```

---

### 5.8 Tenant Configuration

#### Get Tenant Settings

```http
GET /v1/{tenant-id}/settings
```

**Response (200 OK):**
```json
{
  "tenant_id": "tenant_acme",
  "name": "Acme Corporation",
  "status": "active",
  "plan": "enterprise",
  "configuration": {
    "workflows": ["standard-kyc-v3", "enhanced-kyc-v2"],
    "document_types": ["passport", "drivers_license", "national_id"],
    "biometric_modalities": ["facial", "fingerprint"],
    "risk_thresholds": {
      "low": 30.0,
      "medium": 70.0,
      "high": 90.0
    },
    "data_retention_days": 2555,
    "jurisdiction": "EU",
    "webhook_timeout_seconds": 30,
    "max_upload_size_mb": 50,
    "supported_locales": ["en-US", "de-DE", "fr-FR"]
  },
  "quotas": {
    "verifications_per_month": 100000,
    "used_this_month": 45231,
    "reset_date": "2026-08-01T00:00:00Z"
  },
  "branding": {
    "logo_url": "https://cdn.acme.com/logo.png",
    "primary_color": "#0066CC",
    "favicon_url": "https://cdn.acme.com/favicon.ico"
  },
  "created_at": "2025-01-15T00:00:00Z",
  "updated_at": "2026-07-20T12:00:00Z"
}
```

---

#### Update Tenant Settings

```http
PATCH /v1/{tenant-id}/settings
```

**Request Body:**
```json
{
  "risk_thresholds": {
    "low": 25.0,
    "medium": 65.0,
    "high": 85.0
  },
  "branding": {
    "primary_color": "#FF6600"
  }
}
```

**Response (200 OK):**
```json
{
  "tenant_id": "tenant_acme",
  "configuration": {
    "risk_thresholds": {
      "low": 25.0,
      "medium": 65.0,
      "high": 85.0
    },
    "branding": {
      "primary_color": "#FF6600"
    }
  },
  "updated_at": "2026-07-21T23:40:00Z"
}
```

---

## 6. Webhook Events

### 6.1 Event Payload Format

```http
POST /webhooks/usora HTTP/1.1
Host: tenant-api.example.com
X-Usora-Signature: sha256=abc123def456...
X-Usora-Event-ID: evt_7f8a9b2c3d4e5f6a
X-Usora-Timestamp: 1721609460
X-Usora-Webhook-ID: whk_a3b4c5d6
Content-Type: application/json

{
  "event": "verification.completed",
  "id": "evt_7f8a9b2c3d4e5f6a",
  "tenant_id": "tenant_acme",
  "timestamp": "2026-07-21T23:31:00Z",
  "data": {
    "verification_id": "ver_7f8a9b2c3d4e5f6a",
    "user_reference": "user_12345",
    "status": "approved",
    "risk_score": 23.5,
    "risk_level": "low",
    "workflow_id": "standard-kyc-v3",
    "completed_steps": ["document_upload", "biometric_capture", "risk_scoring"],
    "result": {
      "decision": "approved",
      "confidence": 0.98,
      "flags": []
    },
    "completed_at": "2026-07-21T23:31:00Z"
  }
}
```

### 6.2 Event Types

| Event | Description | When Fired |
|---|---|---|
| `verification.created` | New verification initiated | Immediately after POST /verifications |
| `verification.document_uploaded` | Document submitted | After successful upload |
| `verification.document_processed` | Document analysis complete | After compute returns results |
| `verification.biometric_captured` | Biometric submitted | After successful capture |
| `verification.biometric_processed` | Biometric analysis complete | After compute returns results |
| `verification.risk_scored` | Risk score computed | After all signals aggregated |
| `verification.escalated` | Sent to manual review | When risk threshold exceeded |
| `verification.review_assigned` | Reviewer assigned | When queue worker assigns |
| `verification.review_completed` | Manual review done | When reviewer submits decision |
| `verification.approved` | Final approval | Workflow reaches approved end state |
| `verification.rejected` | Final rejection | Workflow reaches rejected end state |
| `verification.completed` | All steps finished (any outcome) | Workflow reaches any end state |
| `verification.expired` | Session timeout | TTL expires |
| `verification.cancelled` | Cancelled by client | POST /cancel received |
| `watchlist.hit` | PEP/sanctions match found | Screening completes with match |
| `compliance.alert` | Compliance threshold breached | Automated rule triggers |

---

## 7. gRPC Service Definitions

### 7.1 Gateway Service

```protobuf
// usora/gateway/v1/gateway.proto
syntax = "proto3";
package usora.gateway.v1;

option go_package = "github.com/usora/api/go/gateway/v1";
option java_package = "io.usora.api.gateway.v1";
option rust_package = "usora_api::gateway::v1";

// GatewayService is the public-facing API surface.
// All requests are authenticated, authorized, and routed through
// the Rust API Gateway layer before reaching downstream services.
service GatewayService {
  // Create a new verification session
  rpc CreateVerification(CreateVerificationRequest) returns (Verification);
  
  // Retrieve verification by ID
  rpc GetVerification(GetVerificationRequest) returns (Verification);
  
  // List verifications with filtering and pagination
  rpc ListVerifications(ListVerificationsRequest) returns (ListVerificationsResponse);
  
  // Cancel an in-progress verification
  rpc CancelVerification(CancelVerificationRequest) returns (Verification);
  
  // Stream real-time verification events
  rpc StreamVerificationEvents(StreamVerificationEventsRequest) 
    returns (stream VerificationEvent);
}

message CreateVerificationRequest {
  string tenant_id = 1;
  string workflow_id = 2;
  string user_reference = 3;
  string callback_url = 4;
  string redirect_url = 5;
  map<string, string> metadata = 6;
  int32 expires_in_minutes = 7;
  Priority priority = 8;
  
  enum Priority {
    PRIORITY_UNSPECIFIED = 0;
    PRIORITY_EXPRESS = 1;
    PRIORITY_STANDARD = 2;
    PRIORITY_BATCH = 3;
  }
}

message GetVerificationRequest {
  string tenant_id = 1;
  string verification_id = 2;
}

message ListVerificationsRequest {
  string tenant_id = 1;
  repeated string status = 2;
  string user_reference = 3;
  string workflow_id = 4;
  string from = 5;  // RFC 3339
  string to = 6;    // RFC 3339
  string risk_level = 7;
  string sort = 8;
  int32 limit = 9;
  string cursor = 10;
}

message ListVerificationsResponse {
  repeated Verification verifications = 1;
  Pagination pagination = 2;
}

message CancelVerificationRequest {
  string tenant_id = 1;
  string verification_id = 2;
  string reason = 3;
  string notes = 4;
}

message StreamVerificationEventsRequest {
  string tenant_id = 1;
  string verification_id = 2;
  repeated string event_types = 3;
}

message Verification {
  string id = 1;
  string tenant_id = 2;
  string status = 3;
  string workflow_id = 4;
  string user_reference = 5;
  string priority = 6;
  string session_url = 7;
  string session_token = 8;
  repeated VerificationStep steps = 9;
  RiskScore risk_score = 10;
  VerificationResult result = 11;
  string created_at = 12;   // RFC 3339
  string expires_at = 13;   // RFC 3339
  string completed_at = 14; // RFC 3339
  map<string, string> metadata = 15;
}

message VerificationStep {
  string id = 1;
  string name = 2;
  string status = 3;  // PENDING | IN_PROGRESS | COMPLETED | FAILED | SKIPPED
  bool required = 4;
  int32 order = 5;
  string completed_at = 6;
  google.protobuf.Struct result = 7;
}

message RiskScore {
  float overall_score = 1;
  string risk_level = 2;  // LOW | MEDIUM | HIGH | CRITICAL
  repeated RiskSignal signals = 3;
  string model_version = 4;
  string computed_at = 5;
}

message RiskSignal {
  string category = 1;
  float weight = 2;
  float score = 3;
  repeated RiskFactor factors = 4;
}

message RiskFactor {
  string name = 1;
  float score = 2;
  string description = 3;
}

message VerificationResult {
  string decision = 1;     // APPROVED | REJECTED | ESCALATED
  float confidence = 2;
  repeated string flags = 3;
}

message VerificationEvent {
  string event_id = 1;
  string event_type = 2;
  string tenant_id = 3;
  string verification_id = 4;
  string timestamp = 5;
  google.protobuf.Struct payload = 6;
}

message Pagination {
  int32 limit = 1;
  string next_cursor = 2;
  bool has_more = 3;
  int64 total_count = 4;
}
```

### 7.2 Orchestration Service (Internal)

```protobuf
// usora/orchestration/v1/orchestration.proto
syntax = "proto3";
package usora.orchestration.v1;

// OrchestrationService is consumed by the Gateway layer.
// It manages verification lifecycle and workflow execution.
service OrchestrationService {
  rpc StartVerification(StartVerificationRequest) returns (VerificationState);
  rpc GetVerificationState(GetVerificationStateRequest) returns (VerificationState);
  rpc ProcessDocument(ProcessDocumentRequest) returns (TaskReference);
  rpc ProcessBiometric(ProcessBiometricRequest) returns (TaskReference);
  rpc ComputeRiskScore(ComputeRiskScoreRequest) returns (TaskReference);
  rpc SubmitManualReview(SubmitManualReviewRequest) returns (VerificationState);
  rpc CancelWorkflow(CancelWorkflowRequest) returns (VerificationState);
}

message StartVerificationRequest {
  string tenant_id = 1;
  string verification_id = 2;
  string workflow_id = 3;
  map<string, string> context = 4;
}

message VerificationState {
  string verification_id = 1;
  string tenant_id = 2;
  string status = 3;
  string workflow_instance_id = 4;
  repeated string active_tasks = 5;
  google.protobuf.Struct variables = 6;
  string updated_at = 7;
}

message ProcessDocumentRequest {
  string tenant_id = 1;
  string verification_id = 2;
  string document_id = 3;
  string document_type = 4;
  string country_code = 5;
  string s3_url = 6;
  AnalysisDepth depth = 7;
  
  enum AnalysisDepth {
    DEPTH_UNSPECIFIED = 0;
    DEPTH_STANDARD = 1;
    DEPTH_FORENSIC = 2;
  }
}

message ProcessBiometricRequest {
  string tenant_id = 1;
  string verification_id = 2;
  string biometric_id = 3;
  BiometricType type = 4;
  string s3_url = 5;
  
  enum BiometricType {
    TYPE_UNSPECIFIED = 0;
    TYPE_FACIAL = 1;
    TYPE_FINGERPRINT = 2;
    TYPE_VOICE = 3;
    TYPE_IRIS = 4;
  }
}

message ComputeRiskScoreRequest {
  string tenant_id = 1;
  string verification_id = 2;
  repeated string signal_sources = 3;
}

message SubmitManualReviewRequest {
  string tenant_id = 1;
  string verification_id = 2;
  string reviewer_id = 3;
  string decision = 4;  // APPROVE | REJECT | REQUEST_MORE_INFO
  string reason = 5;
  repeated string flags = 6;
}

message TaskReference {
  string task_id = 1;
  string status = 2;
  string estimated_completion = 3;
}
```

### 7.3 Compute Service (Internal)

```protobuf
// usora/compute/v1/document.proto
syntax = "proto3";
package usora.compute.v1;

// DocumentAnalysisService processes identity documents.
service DocumentAnalysisService {
  rpc AnalyzeDocument(AnalyzeDocumentRequest) returns (DocumentAnalysisResult);
  rpc AnalyzeDocumentStream(stream AnalyzeDocumentRequest) 
    returns (stream DocumentAnalysisResult);
  rpc GetDocumentAnalysis(GetDocumentAnalysisRequest) returns (DocumentAnalysisResult);
}

message AnalyzeDocumentRequest {
  string task_id = 1;
  string tenant_id = 2;
  string verification_id = 3;
  string document_id = 4;
  bytes document_image = 5;  // Optional: inline image
  string s3_url = 6;         // Optional: S3 reference
  string document_type = 7;
  string country_code = 8;
  AnalysisDepth depth = 9;
  
  enum AnalysisDepth {
    DEPTH_UNSPECIFIED = 0;
    DEPTH_STANDARD = 1;
    DEPTH_FORENSIC = 2;
  }
}

message DocumentAnalysisResult {
  string task_id = 1;
  string tenant_id = 2;
  string verification_id = 3;
  string document_id = 4;
  Status status = 5;
  ExtractedData extracted_data = 6;
  AuthenticityCheck authenticity = 7;
  TamperingCheck tampering = 8;
  float risk_score = 9;
  repeated string flags = 10;
  int64 processing_time_ms = 11;
  string completed_at = 12;
  
  enum Status {
    STATUS_UNSPECIFIED = 0;
    STATUS_SUCCESS = 1;
    STATUS_FAILED = 2;
    STATUS_SUSPICIOUS = 3;
  }
}

message ExtractedData {
  string full_name = 1;
  string first_name = 2;
  string middle_name = 3;
  string last_name = 4;
  string date_of_birth = 5;
  string document_number = 6;
  string document_expiry = 7;
  string nationality = 8;
  string sex = 9;
  string place_of_birth = 10;
  string mrz = 11;
  map<string, string> raw_fields = 12;
}

message AuthenticityCheck {
  float score = 1;
  map<string, CheckResult> checks = 2;
}

message TamperingCheck {
  bool detected = 1;
  map<string, CheckResult> checks = 2;
}

message CheckResult {
  bool passed = 1;
  float score = 2;
  repeated string anomalies = 3;
}

message GetDocumentAnalysisRequest {
  string task_id = 1;
  string tenant_id = 2;
}
```

```protobuf
// usora/compute/v1/biometric.proto
syntax = "proto3";
package usora.compute.v1;

// BiometricAnalysisService processes biometric captures.
service BiometricAnalysisService {
  rpc AnalyzeBiometric(AnalyzeBiometricRequest) returns (BiometricAnalysisResult);
  rpc SearchTemplate(SearchTemplateRequest) returns (SearchTemplateResponse);
}

message AnalyzeBiometricRequest {
  string task_id = 1;
  string tenant_id = 2;
  string verification_id = 3;
  string biometric_id = 4;
  BiometricType type = 5;
  bytes capture_data = 6;
  string s3_url = 7;
  
  enum BiometricType {
    TYPE_UNSPECIFIED = 0;
    TYPE_FACIAL = 1;
    TYPE_FINGERPRINT = 2;
    TYPE_VOICE = 3;
    TYPE_IRIS = 4;
  }
}

message BiometricAnalysisResult {
  string task_id = 1;
  string tenant_id = 2;
  string verification_id = 3;
  Status status = 4;
  LivenessResult liveness = 5;
  MatchResult match = 6;
  QualityResult quality = 7;
  int64 processing_time_ms = 8;
  string completed_at = 9;
  
  enum Status {
    STATUS_UNSPECIFIED = 0;
    STATUS_SUCCESS = 1;
    STATUS_FAILED = 2;
  }
}

message LivenessResult {
  bool passed = 1;
  string method = 2;
  float confidence = 3;
  float deepfake_probability = 4;
  map<string, CheckResult> checks = 5;
}

message MatchResult {
  float score = 1;
  float threshold = 2;
  bool passed = 3;
  string comparison_type = 4;
  string template_id = 5;
}

message QualityResult {
  float score = 1;
  string lighting = 2;
  string blur = 3;
  string occlusion = 4;
}

message SearchTemplateRequest {
  string tenant_id = 1;
  bytes template_hash = 2;
  int32 top_k = 3;
  float threshold = 4;
}

message SearchTemplateResponse {
  repeated MatchCandidate candidates = 1;
}

message MatchCandidate {
  string template_id = 1;
  float score = 2;
  string tenant_id = 3;
}
```

```protobuf
// usora/compute/v1/risk.proto
syntax = "proto3";
package usora.compute.v1;

// RiskScoringService computes risk scores from aggregated signals.
service RiskScoringService {
  rpc ComputeRiskScore(ComputeRiskScoreRequest) returns (RiskScoreResult);
  rpc GetRiskFeatures(GetRiskFeaturesRequest) returns (RiskFeatures);
}

message ComputeRiskScoreRequest {
  string task_id = 1;
  string tenant_id = 2;
  string verification_id = 3;
  repeated Signal signals = 4;
  
  message Signal {
    string category = 1;
    string source = 2;
    float value = 3;
    map<string, string> metadata = 4;
  }
}

message RiskScoreResult {
  string task_id = 1;
  string tenant_id = 2;
  string verification_id = 3;
  float overall_score = 4;
  string risk_level = 5;
  repeated RiskSignal signals = 6;
  string model_version = 7;
  string computed_at = 8;
}

message RiskSignal {
  string category = 1;
  float weight = 2;
  float score = 3;
  repeated RiskFactor factors = 4;
}

message RiskFactor {
  string name = 1;
  float score = 2;
  string description = 3;
}

message GetRiskFeaturesRequest {
  string tenant_id = 1;
  string verification_id = 2;
}

message RiskFeatures {
  float document_authenticity = 1;
  float template_match_score = 2;
  bool known_fraud_pattern = 3;
  float face_match_score = 4;
  float liveness_confidence = 5;
  float deepfake_probability = 6;
  float typing_speed_variance = 7;
  float mouse_jitter = 8;
  float device_reputation = 9;
  float name_dob_consistency = 10;
  float address_verification = 11;
  float ip_reputation = 12;
  float geolocation_anomaly = 13;
  bool vpn_proxy_detected = 14;
  bool emulator_detected = 15;
}
```

### 7.4 Audit Service (Internal)

```protobuf
// usora/audit/v1/audit.proto
syntax = "proto3";
package usora.audit.v1;

// AuditService records immutable audit trails.
service AuditService {
  rpc RecordEvent(RecordEventRequest) returns (RecordEventResponse);
  rpc QueryEvents(QueryEventsRequest) returns (QueryEventsResponse);
  rpc VerifyChain(VerifyChainRequest) returns (VerifyChainResponse);
}

message RecordEventRequest {
  string tenant_id = 1;
  string actor_id = 2;
  ActorType actor_type = 3;
  string action = 4;
  string resource_type = 5;
  string resource_id = 6;
  bytes payload_hash = 7;
  string source_ip = 8;
  string user_agent = 9;
  map<string, string> metadata = 10;
  
  enum ActorType {
    ACTOR_TYPE_UNSPECIFIED = 0;
    ACTOR_TYPE_USER = 1;
    ACTOR_TYPE_SERVICE = 2;
    ACTOR_TYPE_SYSTEM = 3;
  }
}

message RecordEventResponse {
  string record_id = 1;
  string previous_hash = 2;
  string current_hash = 3;
  int64 timestamp_ms = 4;
}

message QueryEventsRequest {
  string tenant_id = 1;
  string actor_id = 2;
  string resource_type = 3;
  string resource_id = 4;
  string from = 5;
  string to = 6;
  int32 limit = 7;
  string cursor = 8;
}

message QueryEventsResponse {
  repeated AuditRecord records = 1;
  string next_cursor = 2;
}

message AuditRecord {
  string record_id = 1;
  string tenant_id = 2;
  string actor_id = 3;
  string actor_type = 4;
  string action = 5;
  string resource_type = 6;
  string resource_id = 7;
  string payload_hash = 8;
  string previous_state_hash = 9;
  int64 timestamp_ms = 10;
  string source_ip = 11;
  string user_agent = 12;
  map<string, string> metadata = 13;
}

message VerifyChainRequest {
  string tenant_id = 1;
  string from_record_id = 2;
  string to_record_id = 3;
}

message VerifyChainResponse {
  bool valid = 1;
  int64 records_verified = 2;
  string merkle_root = 3;
  string blockchain_tx_hash = 4;
}
```

---

## 8. SDK Specifications

### 8.1 Web SDK (TypeScript)

```typescript
// Initialization
import { UsoraClient } from '@usora/sdk-web';

const client = new UsoraClient({
  tenantId: 'tenant_acme',
  apiKey: 'usora_live_abc123',
  environment: 'production', // or 'sandbox'
  apiVersion: 'v1'
});

// Create verification
const verification = await client.verifications.create({
  workflowId: 'standard-kyc-v3',
  userReference: 'user_12345',
  callbackUrl: 'https://acme.com/webhooks/usora',
  metadata: { source: 'web_onboarding' }
});

// Get embedded verification URL
const sessionUrl = verification.sessionUrl;
// Redirect user or embed in iframe

// Poll for status
const status = await client.verifications.get(verification.id);

// Or use webhooks
client.webhooks.on('verification.completed', (event) => {
  console.log('Verification complete:', event.data.result);
});
```

### 8.2 Mobile SDK (iOS — Swift)

```swift
import UsoraSDK

let config = UsoraConfig(
    tenantId: "tenant_acme",
    apiKey: "usora_live_abc123",
    environment: .production
)

let client = UsoraClient(config: config)

// Launch verification flow
let verification = try await client.createVerification(
    workflowId: "standard-kyc-v3",
    userReference: "user_12345"
)

// Present native verification UI
let controller = UsoraVerificationViewController(
    verification: verification,
    delegate: self
)
present(controller, animated: true)

// Delegate callbacks
extension ViewController: UsoraVerificationDelegate {
    func verificationDidComplete(_ result: VerificationResult) {
        // Handle approval/rejection
    }
    
    func verificationDidFail(_ error: UsoraError) {
        // Handle error
    }
}
```

### 8.3 Mobile SDK (Android — Kotlin)

```kotlin
import io.usora.sdk.UsoraClient
import io.usora.sdk.UsoraConfig

val config = UsoraConfig(
    tenantId = "tenant_acme",
    apiKey = "usora_live_abc123",
    environment = UsoraEnvironment.PRODUCTION
)

val client = UsoraClient(context, config)

// Create and launch verification
lifecycleScope.launch {
    val verification = client.createVerification(
        workflowId = "standard-kyc-v3",
        userReference = "user_12345"
    )
    
    // Launch activity
    UsoraVerificationActivity.launch(
        context = this@MainActivity,
        verification = verification
    )
}

// Result in onActivityResult
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    when (val result = UsoraVerificationActivity.parseResult(resultCode, data)) {
        is VerificationResult.Success -> { /* approved */ }
        is VerificationResult.Failure -> { /* rejected */ }
        is VerificationResult.Error -> { /* error */ }
    }
}
```

---

## 9. Rate Limits

### 9.1 Default Limits by Plan

| Plan | Requests/Second | Burst | Verifications/Month |
|---|---|---|---|
| Starter | 10 | 20 | 1,000 |
| Professional | 100 | 200 | 10,000 |
| Enterprise | 1,000 | 2,000 | Unlimited |

### 9.2 Endpoint-Specific Limits

| Endpoint | Limit | Notes |
|---|---|---|
| `POST /verifications` | 10/sec | Creation rate limit |
| `POST /verifications/{id}/documents` | 5/sec | Document upload |
| `POST /verifications/{id}/biometrics` | 5/sec | Biometric upload |
| `GET /verifications/{id}` | 100/sec | Status polling |
| `POST /watchlists/screen` | 50/sec | Screening requests |
| `POST /webhooks` | 5/sec | Webhook management |
| `GET /reports/{id}` | 10/sec | Report download |

### 9.3 Rate Limit Response

```http
HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1721609520
Retry-After: 60
Content-Type: application/json

{
  "error": {
    "code": "RATE_LIMITED",
    "message": "Rate limit exceeded. Retry after 60 seconds.",
    "request_id": "550e8400-e29b-41d4-a716-446655440000",
    "timestamp": "2026-07-21T23:32:00Z"
  }
}
```

---

## 10. Versioning

### 10.1 API Version Strategy

- **URL path versioning**: `/v1/`, `/v2/`
- **Minimum 12-month deprecation window** for breaking changes
- **Sunset header** on deprecated endpoints:
  ```
  Sunset: Sat, 01 Jan 2027 00:00:00 GMT
  Deprecation: true
  ```
- **Changelog** published at `https://docs.usora.io/changelog`

### 10.2 Breaking vs Non-Breaking Changes

| Type | Examples | Notification |
|---|---|---|
| **Breaking** | Removing fields, changing types, new required params | 90 days email + dashboard notice |
| **Non-breaking** | Adding optional fields, new endpoints, new enum values | Changelog + webhook event |

---

## 11. Pagination

### 11.1 Cursor-Based Pagination

```http
GET /v1/acme-corp/verifications?limit=20
```

**Response:**
```json
{
  "data": [ ... ],
  "pagination": {
    "limit": 20,
    "next_cursor": "eyJpZCI6InZlcl94eXoiLCJjcmVhdGVkX2F0IjoiMjAyNi0wNy0yMVQyMzowMDowMFoifQ",
    "has_more": true,
    "total_count": 1543
  }
}
```

**Next Request:**
```http
GET /v1/acme-corp/verifications?limit=20&cursor=eyJpZCI6InZlcl94eXoiLCJjcmVhdGVkX2F0IjoiMjAyNi0wNy0yMVQyMzowMDowMFoifQ
```

### 11.2 Cursor Format

- Base64-encoded JSON: `{ "id": "last_id", "created_at": "2026-07-21T23:00:00Z" }`
- Opaque to clients — do not parse or construct manually

---

## 12. Idempotency

### 12.1 Idempotency Keys

```http
POST /v1/acme-corp/verifications HTTP/1.1
Idempotency-Key: idem_ver_abc123_20260721
```

**Behavior:**
- Key scoped to tenant + endpoint + 24-hour window
- Duplicate requests with same key return cached response (201 → 200)
- Key can be any string, max 255 chars
- Recommended format: `{resource}_{uuid}_{date}`

### 12.2 Idempotent Endpoints

| Endpoint | Idempotent | Key Required |
|---|---|---|
| `POST /verifications` | Yes | Recommended |
| `POST /verifications/{id}/cancel` | Yes | Recommended |
| `POST /verifications/{id}/documents` | No | N/A |
| `POST /verifications/{id}/biometrics` | No | N/A |
| `POST /webhooks` | Yes | Recommended |
| `DELETE /webhooks/{id}` | Yes | No |
| `PATCH /settings` | Yes | Recommended |

---

## 13. File Uploads

### 13.1 Supported Formats

| Type | Extensions | Max Size | Notes |
|---|---|---|---|
| JPEG | `.jpg`, `.jpeg` | 50MB | Preferred for photos |
| PNG | `.png` | 50MB | Supports transparency |
| PDF | `.pdf` | 50MB | Multi-page supported |
| TIFF | `.tiff`, `.tif` | 50MB | High bit-depth |
| HEIC | `.heic` | 50MB | iOS native |
| WebP | `.webp` | 50MB | Modern format |

### 13.2 Upload Constraints

- **Resolution**: Min 300 DPI for documents, recommended 600 DPI
- **Dimensions**: Min 800x600 pixels
- **Color space**: RGB or Grayscale
- **Compression**: JPEG quality >= 80 recommended

---

## 14. Changelog

| Version | Date | Changes |
|---|---|---|
| 1.0.0 | 2026-07-21 | Initial release — verification lifecycle, document/biometric analysis, risk scoring, webhooks, reports |

---

*USORA API — v1.0.0*
