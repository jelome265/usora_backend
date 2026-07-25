# Agent: Orchestrator Workflow

## Metadata
- **Agent ID**: `usora-agent-orchestrator-workflow`
- **Tier**: 2 — Business Orchestration
- **Owner**: Backend Engineering / Process Engineering
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Orchestrator Workflow agent manages all BPMN-based KYC process definitions, workflow execution, state machines, and saga orchestration. It provides a visual, configurable way to define KYC pipelines while ensuring strict tenant isolation, auditability, and fault tolerance across all workflow instances.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Workflow Engine | Camunda Platform 8 (Zeebe) | 8.6+ |
| BPMN Modeler | Camunda Modeler | 5.28+ |
| Saga Pattern | Camunda Sagas / custom compensation | — |
| Workers | Spring Zeebe Client | 8.6+ |
| State Store | Zeebe broker (Raft) + Elasticsearch | 8.6+ |
| Monitoring | Camunda Operate + Optimize | 8.6+ |

## API Surface

### gRPC Services (Zeebe Gateway)
```protobuf
service WorkflowService {
  rpc DeployProcess(DeployProcessRequest) returns (DeployProcessResponse);
  rpc StartProcessInstance(StartProcessRequest) returns (StartProcessResponse);
  rpc CancelProcessInstance(CancelProcessRequest) returns (CancelProcessResponse);
  rpc GetProcessInstanceState(ProcessStateRequest) returns (ProcessStateResponse);
  rpc SendMessage(SendMessageRequest) returns (SendMessageResponse);
  rpc UpdateProcessVariable(VariableUpdateRequest) returns (VariableUpdateResponse);
}
```

### REST Endpoints (Camunda Operate API)
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/workflows/deploy` | Deploy BPMN process definition |
| POST | `/api/v1/workflows/{processKey}/start` | Start workflow instance |
| POST | `/api/v1/workflows/{instanceId}/cancel` | Cancel running instance |
| GET | `/api/v1/workflows/{instanceId}/state` | Get instance state and variables |
| POST | `/api/v1/workflows/{instanceId}/message` | Send message to waiting instance |
| GET | `/api/v1/workflows/instances` | List workflow instances (paginated) |

## Tenant Isolation Strategy
- **Process definition namespace**: BPMN deployments tagged with `tenant_id` custom header
- **Worker isolation**: Job workers filter by `tenant_id` job header; no cross-tenant job processing
- **Variable scoping**: Process variables prefixed with tenant context; encrypted at rest per tenant key
- **Instance isolation**: Workflow instance IDs are globally unique but queries filtered by tenant ACL
- **Audit isolation**: Operate audit logs tagged with `tenant_id`; no cross-tenant visibility
- **Resource quotas**: Per-tenant max concurrent instances, max process definition versions

## Security Boundaries
- BPMN deployments require `workflow:deploy` role + tenant admin approval
- Process variables containing PII encrypted with tenant-specific transit key
- Job workers authenticate via mTLS to Zeebe gateway
- Workflow modifications (cancel, variable update) require `case:manage` role
- All state transitions immutable and auditable via Operate history
- Compensation transactions require dual-authorization for amounts > threshold

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Zeebe broker logs → Loki; worker execution logs → Loki |
| Metrics | `workflow_instances_started_total`, `workflow_instances_completed_total`, `workflow_job_execution_duration_seconds`, `workflow_incidents_total` |
| Traces | OpenTelemetry spans per workflow instance; propagated through job workers |
| Alerts | Incident rate > 0.5%, job timeout rate > 2%, instance stuck > 30min |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Zeebe broker partition loss | Raft metrics / health check | Automatic re-election, alert if quorum lost |
| Job worker crash | Heartbeat timeout | Job re-activation to available worker, retry counter increment |
| Compensation failure | Compensation job incident | Escalate to manual review queue, alert, audit log |
| Process instance stuck | No progress > 30min | Alert, manual intervention, optional auto-cancel |
| BPMN deployment failure | Deployment rejection | Rollback to previous version, alert process owner |
| Variable serialization error | Job failure with exception | DLQ for failed jobs, alert, manual inspection |

## Configuration
```yaml
workflow:
  zeebe:
    gateway:
      address: "zeebe-gateway.usora.svc.cluster.local:26500"
    worker:
      max_jobs_active: 32
      poll_interval: 100
      timeout: 30000
    broker:
      partitions: 3
      replication_factor: 3
      cluster_size: 3
  operate:
    enabled: true
    elasticsearch:
      url: "http://elasticsearch.usora.svc.cluster.local:9200"
  optimize:
    enabled: true
    import_interval: 60000
  security:
    mTLS:
      enabled: true
      cert_path: "/secrets/zeebe/client-cert.pem"
      key_path: "/secrets/zeebe/client-key.pem"
    encryption:
      enabled: true
      key_provider: "vault"
      key_path: "transit/keys/tenant-{tid}-workflow"
```

## Dependencies
- `orchestrator-core` — Business logic integration, case management
- `platform-identity` — Worker authentication, RBAC for workflow operations
- `platform-secrets` — Encryption keys for process variables
- `platform-observability` — Metrics, traces, incident alerting
- `data-kafka` — Event correlation with workflow messages
