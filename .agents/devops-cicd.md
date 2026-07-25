# Agent: DevOps CI/CD

## Metadata
- **Agent ID**: `usora-agent-devops-cicd`
- **Tier**: 7 — DevOps & Lifecycle
- **Owner**: DevOps Engineering
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The DevOps CI/CD agent manages the continuous integration and deployment pipeline for all USORA services. It handles build, test, security scanning, artifact signing, and GitOps-based deployment with environment promotion and rollback capabilities.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| CI | GitHub Actions | latest |
| CD | ArgoCD | 2.12+ |
| Registry | Harbor | 2.11+ |
| Signing | Sigstore / cosign | latest |
| SAST | SonarQube + Semgrep | latest |
| DAST | OWASP ZAP | latest |
| SCA | Snyk + Trivy | latest |
| GitOps | Flux / ArgoCD | latest |
| Secrets | External Secrets Operator | latest |

## API Surface

### gRPC Services
```protobuf
service CICDService {
  rpc TriggerBuild(BuildTriggerRequest) returns (BuildTriggerResponse);
  rpc GetBuildStatus(BuildStatusRequest) returns (BuildStatusResponse);
  rpc PromoteArtifact(PromoteRequest) returns (PromoteResponse);
  rpc RollbackDeployment(RollbackRequest) returns (RollbackResponse);
  rpc GetDeploymentHistory(DeploymentHistoryRequest) returns (DeploymentHistoryResponse);
  rpc ApproveDeployment(DeploymentApprovalRequest) returns (DeploymentApprovalResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/cicd/build` | Trigger build |
| GET | `/api/v1/cicd/build/{buildId}` | Get build status |
| POST | `/api/v1/cicd/promote` | Promote artifact to environment |
| POST | `/api/v1/cicd/rollback` | Rollback deployment |
| GET | `/api/v1/cicd/history` | Get deployment history |
| POST | `/api/v1/cicd/approve` | Approve deployment |

## Tenant Isolation Strategy
- **Pipeline isolation**: Per-tenant deployment pipelines with separate ArgoCD apps
- **Artifact isolation**: Per-tenant image repositories in Harbor
- **Secret isolation**: Per-tenant Kubernetes secrets via External Secrets Operator
- **Environment isolation**: Separate namespaces per tenant per environment
- **Approval isolation**: Per-tenant deployment approval workflows

## Security Boundaries
- All artifacts signed with cosign + Sigstore
- SAST/DAST/SCA gates mandatory before deployment
- No secrets in Git; all secrets from Vault via External Secrets Operator
- Deployment approvals require dual authorization for production
- Immutable tags: no image tag overwriting
- SBOM generation and attestation for all artifacts
- Supply chain security: SLSA Level 3 compliance

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Build logs → Loki; deployment events → audit log |
| Metrics | `cicd_build_duration_seconds`, `cicd_deployment_duration_seconds`, `cicd_rollback_total`, `cicd_security_gate_failures_total` |
| Traces | OpenTelemetry spans: build → test → scan → sign → deploy |
| Alerts | Build failure rate > 5%, deployment failure, security gate failure |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Build failure | GitHub Actions exit code | Alert, preserve logs, manual investigation |
| Security gate failure | SAST/DAST/SCA scan | Block deployment, alert security team |
| Deployment failure | ArgoCD sync error | Auto-rollback to previous healthy revision |
| Artifact signing failure | cosign error | Retry, alert, manual signing if needed |
| Registry unavailable | Harbor health check | Retry with backoff, alert, use cached images |

## Configuration
```yaml
cicd:
  github:
    organization: "usora"
    repository: "usora-platform"
    workflows:
      - name: "build-and-test"
        triggers: ["push", "pull_request"]
      - name: "security-scan"
        triggers: ["pull_request"]
      - name: "deploy"
        triggers: ["workflow_dispatch"]
  argocd:
    server: "argocd.usora.svc.cluster.local"
    sync_policy: "automated"
    prune: true
    self_heal: true
  harbor:
    url: "https://harbor.usora.io"
    project: "usora"
    replication:
      enabled: true
      destinations: ["us-west-2", "eu-west-1"]
  security:
    sast:
      tools: ["sonarqube", "semgrep"]
      threshold: "high"
    dast:
      tool: "owasp_zap"
      threshold: "high"
    sca:
      tools: ["snyk", "trivy"]
      threshold: "critical"
    signing:
      enabled: true
      keyless: true  # Sigstore keyless signing
  approval:
    required_for: ["production"]
    min_approvers: 2
    approver_roles: ["sre", "platform_admin"]
```

## Dependencies
- `platform-infra` — Deployment targets, Terraform
- `security-penetration` — Security gates
- `devops-testing` — Test execution
- `platform-secrets` — Secret injection
- `platform-observability` — Metrics, logs
