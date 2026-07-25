# Agent: Security Threat Model

## Metadata
- **Agent ID**: `usora-agent-security-threat-model`
- **Tier**: 6 — Security & Trust
- **Owner**: Security Engineering / Threat Intelligence
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Security Threat Model agent maintains the living threat model for USORA using STRIDE methodology. It tracks attack surfaces, threat actors, vulnerabilities, mitigations, and risk scores across all system components with automated threat intelligence integration and continuous reassessment.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Threat Modeling | Microsoft Threat Modeling Tool / custom | latest |
| STRIDE Analysis | custom Rust + OpenAPI parser | — |
| Threat Intelligence | MISP / AlienVault OTX / CrowdStrike | latest |
| Vulnerability DB | NVD / OSV / Snyk | latest |
| Risk Scoring | CVSS v3.1 / custom risk matrix | — |
| Documentation | Markdown + Mermaid diagrams | — |
| Automation | GitHub Actions / custom CI | latest |

## API Surface

### gRPC Services
```protobuf
service ThreatModelService {
  rpc GetThreatModel(ThreatModelRequest) returns (ThreatModelResponse);
  rpc AddThreat(ThreatAddRequest) returns (ThreatAddResponse);
  rpc UpdateMitigation(MitigationUpdateRequest) returns (MitigationUpdateResponse);
  rpc GetRiskScore(RiskScoreRequest) returns (RiskScoreResponse);
  rpc RunSTRIDEAnalysis(STRIDEAnalysisRequest) returns (STRIDEAnalysisResponse);
  rpc GetThreatIntelligence(ThreatIntelRequest) returns (ThreatIntelResponse);
  rpc GenerateReport(ThreatReportRequest) returns (ThreatReportResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/v1/threat-model` | Get current threat model |
| POST | `/api/v1/threat-model/threats` | Add new threat |
| PUT | `/api/v1/threat-model/threats/{threatId}/mitigation` | Update mitigation |
| GET | `/api/v1/threat-model/risk-score` | Get overall risk score |
| POST | `/api/v1/threat-model/stride` | Run STRIDE analysis |
| GET | `/api/v1/threat-model/intel` | Get threat intelligence |
| POST | `/api/v1/threat-model/report` | Generate threat model report |

## Tenant Isolation Strategy
- **Threat scope isolation**: Per-tenant threat models for tenant-specific attack surfaces
- **Data isolation**: Tenant-specific threat intelligence feeds and indicators
- **Access control**: Threat model access restricted to security team + tenant admins
- **Report isolation**: Per-tenant threat assessment reports

## Security Boundaries
- Threat model data classified as CONFIDENTIAL; encrypted at rest
- Threat intelligence feeds sanitized before ingestion (no active malware samples)
- Automated STRIDE analysis runs on every API spec change via CI
- Vulnerability correlation: automatic mapping of CVEs to USORA components
- Risk acceptance requires CISO approval with documented justification
- All threat model changes versioned in Git with signed commits

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Threat analysis events → structured log → Loki |
| Metrics | `threat_model_threats_total`, `threat_model_mitigated_total`, `threat_model_risk_score`, `threat_intel_indicators_received_total` |
| Traces | N/A (analysis-time concern) |
| Alerts | New critical threat identified, unmitigated threat > 30 days, risk score > threshold |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| STRIDE analysis failure | CI pipeline error | Manual review, fix parser, re-run |
| Threat intel feed unavailable | HTTP error | Use cached data (max 24h stale), alert |
| False positive threat | Manual review | Mark as false positive, document rationale |
| Risk score miscalculation | Validation error | Recalculate, alert, manual verification |
| Report generation failure | Timeout / error | Retry, fallback to manual report, alert |

## Configuration
```yaml
threat_model:
  methodology: "STRIDE"
  risk_matrix:
    likelihood: ["rare", "unlikely", "possible", "likely", "almost_certain"]
    impact: ["negligible", "minor", "moderate", "major", "catastrophic"]
    thresholds:
      critical: 20
      high: 15
      medium: 10
      low: 5
  threat_intelligence:
    sources:
      - name: "misp"
        url: "${VAULT:misp_url}"
        api_key: "${VAULT:misp_api_key}"
        refresh_interval: "1h"
      - name: "alienvault_otx"
        api_key: "${VAULT:otx_api_key}"
        refresh_interval: "6h"
      - name: "crowdstrike"
        api_key: "${VAULT:crowdstrike_api_key}"
        refresh_interval: "1h"
  automation:
    stride_analysis_on_api_change: true
    cve_correlation: true
    auto_alert_on_critical: true
  reporting:
    frequency: "monthly"
    formats: ["pdf", "markdown"]
    distribution: ["ciso", "cto", "security_team"]
```

## Dependencies
- `platform-observability` — Metrics, alerting
- `platform-secrets` — API keys for threat intel feeds
- `devops-cicd` — Automated STRIDE analysis on API changes
- `security-zero-trust` — Network topology for attack surface mapping
