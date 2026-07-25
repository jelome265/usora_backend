# Agent: DevOps Cost Optimization

## Metadata
- **Agent ID**: `usora-agent-devops-cost`
- **Tier**: 7 — DevOps & Lifecycle
- **Owner**: FinOps / Platform Engineering
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The DevOps Cost Optimization agent manages cloud cost governance for USORA including resource right-sizing, spot instance usage, reserved capacity planning, and per-tenant cost allocation. It ensures cost efficiency while maintaining performance and reliability.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Cost | Kubecost / OpenCost | 1.108+ |
| Rightsizing | AWS Compute Optimizer | latest |
| Spot | Karpenter + AWS Spot | latest |
| Budgets | AWS Budgets + custom | latest |
| Forecasting | custom ML / AWS Cost Explorer | latest |
| Allocation | custom | — |

## API Surface

### gRPC Services
```protobuf
service CostService {
  rpc GetCostReport(CostReportRequest) returns (CostReportResponse);
  rpc GetTenantAllocation(TenantAllocationRequest) returns (TenantAllocationResponse);
  rpc GetRightsizingRecommendations(RightsizingRequest) returns (RightsizingResponse);
  rpc GetSpotRecommendations(SpotRequest) returns (SpotResponse);
  rpc GetReservedCapacityRecommendations(ReservedCapacityRequest) returns (ReservedCapacityResponse);
  rpc SetBudgetAlert(BudgetAlertRequest) returns (BudgetAlertResponse);
  rpc GetForecast(ForecastRequest) returns (ForecastResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/v1/cost/report` | Get cost report |
| GET | `/api/v1/cost/tenant/{tenantId}` | Get tenant cost allocation |
| GET | `/api/v1/cost/rightsizing` | Get rightsizing recommendations |
| GET | `/api/v1/cost/spot` | Get spot instance recommendations |
| GET | `/api/v1/cost/reserved-capacity` | Get reserved capacity recommendations |
| POST | `/api/v1/cost/budget-alert` | Set budget alert |
| GET | `/api/v1/cost/forecast` | Get cost forecast |

## Tenant Isolation Strategy
- **Cost allocation**: Per-tenant cost tracking via Kubernetes labels
- **Budget isolation**: Per-tenant budget alerts and thresholds
- **Quota enforcement**: Per-tenant resource quotas with cost-based limits
- **Reporting isolation**: Per-tenant cost reports and dashboards

## Security Boundaries
- Cost data access restricted to finance and platform admin roles
- No sensitive data in cost reports
- Budget changes require approval workflow
- Forecast data used for planning only; not contractual

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Cost events → Loki |
| Metrics | `cost_total_usd`, `cost_per_tenant_usd`, `cost_rightsizing_savings_usd`, `cost_spot_savings_usd` |
| Alerts | Budget threshold exceeded, cost anomaly detected, forecast deviation > 20% |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Cost data lag | Kubecost sync delay | Use AWS Cost Explorer fallback, alert |
| Rightsizing recommendation error | Compute Optimizer API error | Manual review, alert |
| Spot instance interruption | AWS EC2 event | Karpenter fallback to on-demand, alert |
| Budget alert misfire | False positive | Adjust threshold, alert finance |

## Configuration
```yaml
cost:
  kubecost:
    enabled: true
    url: "http://kubecost.usora.svc.cluster.local:9090"
    retention: "30d"
  allocation:
    method: "label"
    tenant_label: "usora.io/tenant-id"
    service_label: "usora.io/service"
  rightsizing:
    enabled: true
    schedule: "0 0 * * 0"  # Weekly
    auto_apply: false  # Manual approval required
    min_savings_usd: 100
  spot:
    enabled: true
    max_interruption_rate: 10  # Percentage
    fallback_to_ondemand: true
    excluded_workloads: ["postgresql", "redis", "kafka"]
  reserved_capacity:
    enabled: true
    min_utilization: 70  # Percentage
    term: "1y"
    payment_option: "partial_upfront"
  budgets:
    - name: "platform-total"
      amount: 50000
      period: "monthly"
      alert_thresholds: [50, 80, 100]
    - name: "per-tenant-default"
      amount: 1000
      period: "monthly"
      alert_thresholds: [80, 100]
  anomaly_detection:
    enabled: true
    sensitivity: "medium"
    min_anomaly_usd: 500
```

## Dependencies
- `platform-infra` — Resource provisioning, Karpenter
- `platform-observability` — Cost metrics
- `orchestrator-tenant` — Tenant resource quotas
