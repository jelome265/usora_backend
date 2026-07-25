# Agent: Orchestrator Compliance

## Metadata
- **Agent ID**: `usora-agent-orchestrator-compliance`
- **Tier**: 2 — Business Orchestration
- **Owner**: Compliance Engineering / Legal
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Orchestrator Compliance agent manages regulatory rule engines, compliance checks, audit reporting, and evidence collection across all KYC operations. It ensures USORA meets GDPR, AML/CFT, KYC/AML directives, PCI-DSS, SOC2, and jurisdiction-specific requirements with automated compliance validation and immutable evidence chains.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Runtime | Spring Boot | 4.1 |
| JVM | Java 21 LTS | 21 |
| Concurrency | Virtual Threads | — |
| Rule Engine | Drools / Easy Rules | 8.44+ |
| Policy Engine | Open Policy Agent (OPA) | 0.68+ |
| Reporting | Apache POI / JasperReports | latest |
| Blockchain | Hyperledger Fabric (optional evidence anchoring) | 2.5+ |
| Database | PostgreSQL (schema-per-tenant) | 16+ |
| Cache | Redis | 7.2+ |

## API Surface

### gRPC Services
```protobuf
service ComplianceService {
  rpc ValidateCompliance(ComplianceValidationRequest) returns (ComplianceValidationResponse);
  rpc GetRegulatoryRules(RegulatoryRulesRequest) returns (RegulatoryRulesResponse);
  rpc UpdateRegulatoryRules(RegulatoryRulesUpdateRequest) returns (RegulatoryRulesUpdateResponse);
  rpc GenerateReport(ReportGenerationRequest) returns (ReportGenerationResponse);
  rpc GetAuditTrail(AuditTrailRequest) returns (AuditTrailResponse);
  rpc CheckJurisdictionCompliance(JurisdictionCheckRequest) returns (JurisdictionCheckResponse);
  rpc SubmitEvidence(EvidenceSubmissionRequest) returns (EvidenceSubmissionResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/compliance/validate` | Run compliance validation on a case |
| GET | `/api/v1/compliance/rules` | Get active regulatory rules for tenant |
| PUT | `/api/v1/compliance/rules` | Update regulatory rules (compliance admin) |
| POST | `/api/v1/compliance/reports` | Generate compliance report |
| GET | `/api/v1/compliance/audit/{caseId}` | Get full audit trail for case |
| POST | `/api/v1/compliance/jurisdiction-check` | Check jurisdiction-specific requirements |
| POST | `/api/v1/compliance/evidence` | Submit compliance evidence |

## Tenant Isolation Strategy
- **Rule isolation**: Per-tenant Drools rule packages: `compliance.rules.tenant_{tid}`
- **Policy isolation**: OPA policies loaded per tenant namespace
- **Report isolation**: Generated reports stored in tenant-scoped S3 prefix
- **Audit isolation**: Audit trails in `tenant_{tid}.compliance_audit` table
- **Jurisdiction isolation**: Per-tenant jurisdiction configuration (EU, US, APAC, etc.)
- **Evidence isolation**: Evidence hashes stored per tenant; blockchain anchoring per tenant channel

## Security Boundaries
- Compliance rules versioned and signed; tamper detection via Merkle tree
- Rule updates require dual authorization: compliance officer + legal review
- Audit trails append-only with cryptographic integrity (SHA-256 chain)
- Evidence submission requires `compliance:submit` role + notarization
- Reports access restricted to `compliance:read` role within tenant
- Jurisdiction data (sanctions lists, PEP) refreshed daily from authoritative sources
- All compliance decisions logged with full rationale for regulatory inspection

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Compliance validation events → structured audit log → Loki |
| Metrics | `compliance_validation_total`, `compliance_rule_violation_total`, `compliance_report_generation_duration_seconds`, `compliance_audit_trail_query_latency` |
| Traces | OpenTelemetry spans per compliance check; propagated through rule engine |
| Alerts | Compliance violation rate > 1%, rule engine latency > 2s, sanctions list stale > 24h |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Rule engine compilation failure | Drools error | Fallback to last known good ruleset, alert compliance team |
| Sanctions list stale | Last update timestamp check | Queue refresh job, use cached list with warning flag |
| Audit trail corruption | Hash chain verification failure | Freeze affected cases, alert security, manual investigation |
| Report generation timeout | Async job timeout | Retry with smaller batch, stream results, alert |
| Evidence anchoring failure | Blockchain transaction error | Queue for retry, local hash verification, alert |
| Jurisdiction config conflict | Validation error | Deny operation, alert compliance admin, manual resolution |

## Configuration
```yaml
compliance:
  rules:
    engine: "drools"
    refresh_interval: "1h"
    version_control: true
    signing_required: true
  sanctions:
    sources:
      - name: "OFAC"
        url: "https://www.treasury.gov/ofac/downloads/sanctions/1.0/sdn_advanced.xml"
        refresh_interval: "24h"
      - name: "EU Consolidated"
        url: "https://webgate.ec.europa.eu/fsd/fsf/public/files/csvFullSanctionsList/content?path=EN"
        refresh_interval: "24h"
      - name: "UN"
        url: "https://scsanctions.un.org/resources/xml/en/consolidated.xml"
        refresh_interval: "24h"
  audit:
    immutable: true
    hash_algorithm: "SHA-256"
    retention_years: 7
    blockchain_anchor: true
  reporting:
    formats: ["pdf", "xlsx", "csv"]
    max_rows_per_report: 100000
    async_generation: true
  jurisdictions:
    default: "eu_gdpr"
    supported: ["eu_gdpr", "us_aml", "uk_aml", "singapore_mas", "uae_central_bank"]
```

## Dependencies
- `orchestrator-core` — Case data access, business context
- `orchestrator-case` — Case lifecycle events, audit timeline
- `platform-identity` — RBAC for compliance roles
- `platform-observability` — Audit logging, metrics, alerting
- `data-postgresql` — Compliance rules, audit trails
- `data-s3` — Report storage, evidence archives
- `compute-aml-screening` — Sanctions/PEP screening results
