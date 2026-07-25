# Agent: DevOps SRE

## Metadata
- **Agent ID**: `usora-agent-devops-sre`
- **Tier**: 7 — DevOps & Lifecycle
- **Owner**: SRE
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The DevOps SRE agent manages site reliability engineering for USORA including SLOs/SLIs, error budgets, incident response, runbook automation, and on-call rotation. It ensures platform reliability meets enterprise-grade standards with proactive monitoring and automated remediation.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| SLO | Grafana SLO | latest |
| Incident | PagerDuty + incident.io | latest |
| Runbooks | Backstage | latest |
| Status | Statuspage | latest |
| RCA | custom | — |
| Automation | Ansible / custom operators | latest |
| ChatOps | Slack + PagerDuty | latest |

## API Surface

### gRPC Services
```protobuf
service SREService {
  rpc GetSLOStatus(SLOStatusRequest) returns (SLOStatusResponse);
  rpc GetErrorBudget(ErrorBudgetRequest) returns (ErrorBudgetResponse);
  rpc TriggerIncident(IncidentTriggerRequest) returns (IncidentTriggerResponse);
  rpc GetIncidentStatus(IncidentStatusRequest) returns (IncidentStatusResponse);
  rpc ResolveIncident(IncidentResolveRequest) returns (IncidentResolveResponse);
  rpc ExecuteRunbook(RunbookExecuteRequest) returns (RunbookExecuteResponse);
  rpc GetOnCallSchedule(OnCallRequest) returns (OnCallResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/v1/sre/slo` | Get SLO status |
| GET | `/api/v1/sre/error-budget` | Get error budget |
| POST | `/api/v1/sre/incident` | Trigger incident |
| GET | `/api/v1/sre/incident/{incidentId}` | Get incident status |
| POST | `/api/v1/sre/incident/{incidentId}/resolve` | Resolve incident |
| POST | `/api/v1/sre/runbook/{runbookId}/execute` | Execute runbook |
| GET | `/api/v1/sre/oncall` | Get on-call schedule |

## Tenant Isolation Strategy
- **SLO isolation**: Per-tenant SLO definitions and dashboards
- **Error budget isolation**: Per-tenant error budget tracking
- **Incident isolation**: Per-tenant incident routing and visibility
- **On-call isolation**: Per-tenant on-call rotations

## Security Boundaries
- Incident data encrypted at rest
- On-call schedules access-controlled
- Runbook execution logged and audited
- Incident communication via secure channels only

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Incident logs → Loki; runbook execution logs → audit log |
| Metrics | `sre_slo_availability`, `sre_error_budget_remaining`, `sre_incident_count`, `sre_mttr_seconds`, `sre_mttd_seconds` |
| Traces | OpenTelemetry spans per incident lifecycle |
| Alerts | Error budget exhaustion > 50%, SLO breach, incident escalation |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| SLO breach | Metric threshold | Auto-execute runbook, alert, page on-call |
| Error budget exhaustion | Budget calculation | Freeze non-critical deployments, alert leadership |
| Incident escalation failure | No acknowledgment | Escalate to next level, alert manager |
| Runbook execution failure | Ansible error | Manual intervention, alert, update runbook |

## Configuration
```yaml
sre:
  slos:
    - name: "kyc-availability"
      target: 99.99
      window: "30d"
      burn_rate_alerts:
        - multiplier: 2
          window: "1h"
        - multiplier: 1
          window: "6h"
    - name: "api-latency"
      target: 99.9
      threshold: "200ms"
      window: "30d"
    - name: "case-resolution"
      target: 99.5
      threshold: "24h"
      window: "30d"
  error_budget:
    alert_threshold: 50  # Alert at 50% exhaustion
    freeze_threshold: 90  # Freeze deployments at 90% exhaustion
  incident:
    severity_levels:
      - name: "SEV-1"
        description: "Critical - platform down"
        response_time: "5m"
        escalation_time: "15m"
      - name: "SEV-2"
        description: "Major - significant degradation"
        response_time: "15m"
        escalation_time: "30m"
      - name: "SEV-3"
        description: "Minor - partial degradation"
        response_time: "30m"
        escalation_time: "1h"
      - name: "SEV-4"
        description: "Low - cosmetic issues"
        response_time: "1h"
        escalation_time: "4h"
  runbooks:
    - id: "db-failover"
      name: "Database Failover"
      auto_execute: true
      conditions: ["postgresql_primary_down"]
    - id: "cache-warmup"
      name: "Cache Warmup"
      auto_execute: false
      conditions: ["redis_eviction_spike"]
  oncall:
    rotation: "weekly"
    handoff_time: "09:00"
    timezone: "UTC"
    escalation_policy:
      - level: 1
        responders: ["primary"]
        timeout: "15m"
      - level: 2
        responders: ["secondary"]
        timeout: "15m"
      - level: 3
        responders: ["manager"]
        timeout: "30m"
```

## Dependencies
- `platform-observability` — Metrics, alerting
- `devops-cicd` — Deployment correlation
- `platform-infra` — Infrastructure remediation
