# Agent: Compute AML Screening

## Metadata
- **Agent ID**: `usora-agent-compute-aml-screening`
- **Tier**: 3 — Compute & Verification
- **Owner**: Compliance Engineering / Data Science
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Compute AML Screening agent performs real-time and batch screening of individuals and entities against Politically Exposed Persons (PEP) lists, sanctions lists, adverse media, and watchlists. It supports fuzzy name matching, entity resolution, and risk-based scoring for Anti-Money Laundering (AML) and Counter-Terrorist Financing (CFT) compliance.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Runtime | Rust | 1.82+ |
| Async Runtime | Tokio | 1.40+ |
| gRPC | tonic | 0.12+ |
| Search Engine | Elasticsearch / OpenSearch | 2.16+ |
| Fuzzy Matching | custom Rust (Levenshtein + phonetic) | — |
| Entity Resolution | Neo4j / custom graph | 5.x |
| NLP | spaCy / custom NER (ONNX) | latest |
| Data Pipeline | Apache Flink / custom Rust | 1.20+ |
| Cache | Redis | 7.2+ |

## API Surface

### gRPC Services
```protobuf
service AMLScreeningService {
  rpc ScreenIndividual(IndividualScreeningRequest) returns (IndividualScreeningResponse);
  rpc ScreenEntity(EntityScreeningRequest) returns (EntityScreeningResponse);
  rpc ScreenTransaction(TransactionScreeningRequest) returns (TransactionScreeningResponse);
  rpc GetScreeningResult(ScreeningResultRequest) returns (ScreeningResultResponse);
  rpc UpdateWatchlist(WatchlistUpdateRequest) returns (WatchlistUpdateResponse);
  rpc GetAdverseMedia(AdverseMediaRequest) returns (AdverseMediaResponse);
  rpc BatchScreen(BatchScreeningRequest) returns (BatchScreeningResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/aml/screen/individual` | Screen individual |
| POST | `/api/v1/aml/screen/entity` | Screen entity |
| POST | `/api/v1/aml/screen/transaction` | Screen transaction parties |
| GET | `/api/v1/aml/results/{screeningId}` | Get screening result |
| POST | `/api/v1/aml/watchlist/update` | Update watchlist data |
| POST | `/api/v1/aml/adverse-media` | Search adverse media |
| POST | `/api/v1/aml/screen/batch` | Batch screening |

## Tenant Isolation Strategy
- **Index isolation**: Per-tenant Elasticsearch indices: `watchlist-tenant-{tid}`
- **Rule isolation**: Per-tenant matching thresholds and rules
- **Result isolation**: Screening results in tenant-scoped PostgreSQL schema
- **Queue isolation**: Batch screening jobs per tenant
- **Data isolation**: Watchlist data refreshed per tenant jurisdiction requirements
- **Alert isolation**: Per-tenant alert routing and escalation rules

## Security Boundaries
- Watchlist data encrypted at rest (AES-256-GCM)
- Screening queries logged with anonymized subject data
- No raw watchlist data exposed in API responses; only match scores and references
- Adverse media NLP models filtered to prevent false positives on common names
- Entity resolution graphs isolated per tenant
- All screening decisions include full audit trail for regulatory inspection
- Automatic false positive learning: feedback loop from manual reviewers

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Screening events → structured log → Loki (anonymized) |
| Metrics | `aml_screening_total`, `aml_match_found_total`, `aml_screening_duration_seconds`, `aml_watchlist_staleness_hours` |
| Traces | OpenTelemetry spans: name parsing → fuzzy search → entity resolution → scoring → alerting |
| Alerts | Match rate anomaly > 3 sigma, watchlist stale > 24h, screening latency > 2s (p99) |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Watchlist update failure | Scheduled job error | Use last known good data, alert, retry with backoff |
| Elasticsearch unavailable | Health check failure | Fallback to cached results, queue requests, alert |
| Fuzzy matching timeout | 1s deadline | Use exact match only, flag reduced confidence |
| Entity resolution timeout | Graph query timeout | Skip relationship analysis, use direct matches only |
| Adverse media API failure | NLP service error | Skip adverse media check, continue with watchlists |
| Batch screening overload | Queue depth > 5000 | Backpressure, scale workers, alert |

## Configuration
```yaml
aml_screening:
  watchlists:
    sources:
      - name: "OFAC_SDN"
        type: "sanctions"
        refresh_interval: "6h"
        url: "https://www.treasury.gov/ofac/downloads/sanctions/1.0/sdn_advanced.xml"
      - name: "EU_Consolidated"
        type: "sanctions"
        refresh_interval: "6h"
        url: "https://webgate.ec.europa.eu/fsd/fsf/public/files/csvFullSanctionsList/content?path=EN"
      - name: "UN_Sanctions"
        type: "sanctions"
        refresh_interval: "12h"
        url: "https://scsanctions.un.org/resources/xml/en/consolidated.xml"
      - name: "World_Check"
        type: "pep"
        refresh_interval: "24h"
        api_key: "${VAULT:worldcheck_api_key}"
      - name: "Dow_Jones"
        type: "pep"
        refresh_interval: "24h"
        api_key: "${VAULT:dowjones_api_key}"
  matching:
    fuzzy_threshold: 0.85
    phonetic_match: true
    name_variations: true
    alias_matching: true
    date_of_birth_match: true
    address_match: true
  entity_resolution:
    enabled: true
    graph_depth: 3
    relationship_types: ["family", "business", "political", "advisory"]
  adverse_media:
    enabled: true
    sources: ["news", "blogs", "court_records", "regulatory_filings"]
    sentiment_threshold: -0.5
    max_age_days: 365
  performance:
    max_latency_ms: 2000
    batch_size: 500
    max_concurrent_per_tenant: 200
    cache_ttl: 3600
```

## Dependencies
- `platform-gateway` — Request routing
- `platform-identity` — Service authentication
- `platform-observability` — Metrics, traces, alerting
- `platform-secrets` — API keys for watchlist providers
- `ai-nlp` — Adverse media parsing, entity extraction
- `data-postgresql` — Screening results, audit trail
- `data-redis` — Cache, queue management
