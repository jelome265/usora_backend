# ADR-006: Camunda for BPMN Workflow Engine

## Status

Accepted

## Context

USORA's verification workflows are complex, multi-step processes with dynamic branching based on risk scores, document types, and regulatory requirements. Workflows include human-in-the-loop escalation, SLA tracking, A/B testing of variants, and real-time visualization. The workflow engine must support:

- BPMN 2.0 standard for process modeling
- External task workers for long-running operations (document analysis, biometric matching)
- Tenant-isolated process definitions and instances
- History and audit trail for compliance
- Scalability to handle thousands of concurrent workflows
- Integration with Spring Boot ecosystem

Options evaluated:

1. **Camunda 7.21** — Mature, BPMN 2.0 compliant, Java-native, extensive ecosystem, enterprise adoption.
2. **Temporal** — Modern, durable execution, excellent for long-running workflows, but not BPMN-standard.
3. **Cadence** (Uber) — Similar to Temporal, durable execution, but smaller ecosystem.
4. **Custom workflow engine** — Full control, but massive engineering effort to build BPMN parser, execution engine, and history management.
5. **Activiti / Flowable** — Lightweight BPMN engines, but less enterprise features and community support than Camunda.

## Decision

Use **Camunda 7.21** as the BPMN workflow engine.

## Consequences

### Positive

- **BPMN 2.0 standard**: Process definitions are portable, vendor-independent, and understood by business analysts. Compliance auditors recognize BPMN as a standard.
- **Java-native integration**: Seamless integration with Spring Boot. Process definitions deployed as Spring Boot resources. External task workers are Spring-managed beans.
- **Tenant isolation**: Camunda supports multi-tenancy with tenant-scoped process definitions, process instances, and history data. Aligns with USORA's tenant isolation model.
- **External task pattern**: Long-running tasks (document analysis, biometric matching) are modeled as external tasks that Rust compute workers claim and complete. Decouples workflow orchestration from compute execution.
- **History and audit**: Comprehensive history tables record every state transition, variable change, and task completion. Critical for compliance (AML, GDPR, SOC 2).
- **Visualization**: Camunda Cockpit provides real-time workflow visualization for operations teams — queue depths, stuck instances, SLA breaches.
- **Scalability**: Supports horizontal scaling with partition-aware routing. Job executor can be clustered across multiple nodes.
- **Enterprise ecosystem**: Extensive documentation, training, professional services, and community support.

### Negative

- **Database coupling**: Camunda requires its own database schema (or schema-per-tenant). History tables grow large and require cleanup strategies.
- **Versioning complexity**: Process definition versioning requires careful management. Running instances cannot be migrated to new definitions without explicit migration scripts.
- **Learning curve**: BPMN modeling requires training for developers and business analysts. Complex gateway expressions can be error-prone.
- **Resource overhead**: Camunda job executor adds CPU and memory overhead to the orchestration layer.
- **History cleanup**: Without cleanup, history tables grow indefinitely. Requires tenant-specific retention policies and scheduled cleanup jobs.

### Mitigations

- History cleanup scheduled daily with tenant-specific retention policies (e.g., 90 days for standard tenants, 7 years for financial services tenants).
- Process definition versioning managed via Git with CI/CD deployment. Migration scripts tested in staging before production.
- BPMN training provided to all backend engineers and business analysts. Model review process ensures correctness.
- Camunda database tables isolated per tenant schema, aligned with ADR-004.
- Job executor tuned for USORA's workload: async job execution, reduced lock time, optimized polling intervals.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| Temporal | Not BPMN-standard; would require retraining business analysts; less recognized by compliance auditors |
| Cadence | Smaller ecosystem than Temporal; less enterprise adoption; not BPMN-standard |
| Custom engine | Massive engineering effort; would take 12+ months to build equivalent features; ongoing maintenance burden |
| Flowable | Smaller ecosystem and community than Camunda; fewer enterprise features; less documentation |

## Related Decisions

- ADR-002: Java 21 + Spring Boot for Orchestration Layer
- ADR-004: Schema-per-Tenant with Row-Level Security for PostgreSQL
- ADR-011: Saga Pattern for Distributed Transactions

## Date

2026-04-05

## Author

Bob Martinez, Backend Lead

## Reviewed By

Alice Chen (Platform Lead), Charlie Park (ML Lead)
