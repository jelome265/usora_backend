# Agent: Platform Infrastructure

## Metadata
- **Agent ID**: `usora-agent-platform-infra`
- **Tier**: 1 — Core Platform
- **Owner**: Platform Engineering / DevOps
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Platform Infrastructure agent defines and manages all cloud-native infrastructure for USORA across multiple environments (dev, staging, production). It provisions compute (EKS/GKE), networking (VPC, subnets, service mesh), databases (PostgreSQL, Redis), messaging (Kafka), storage (S3), and security controls (IAM, WAF, DDoS protection) with infrastructure-as-code and GitOps workflows.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| IaC | Terraform | 1.9+ |
| IaC State | Terraform Cloud / S3 + DynamoDB | latest |
| Kubernetes | Amazon EKS / Google GKE | 1.30+ |
| Service Mesh | Istio / Cilium Service Mesh | 1.22+ |
| GitOps | ArgoCD / Flux | 2.12+ |
| Image Registry | Harbor / ECR / GCR | latest |
| Policy | OPA Gatekeeper / Kyverno | latest |
| Cost | Kubecost / OpenCost | latest |

## API Surface

### gRPC Services
```protobuf
service InfraService {
  rpc ProvisionTenantEnvironment(TenantEnvRequest) returns (TenantEnvResponse);
  rpc DeprovisionTenantEnvironment(TenantEnvRequest) returns (DeprovisionResponse);
  rpc GetResourceUsage(ResourceUsageRequest) returns (ResourceUsageResponse);
  rpc ApplySecurityPolicy(PolicyApplyRequest) returns (PolicyApplyResponse);
  rpc ValidateCompliance(ComplianceValidationRequest) returns (ComplianceValidationResponse);
}
```

### Terraform Modules
| Module | Purpose |
|--------|---------|
| `modules/eks-cluster` | EKS cluster with managed node groups, Karpenter autoscaling |
| `modules/vpc` | VPC with public/private subnets, NAT, VPC endpoints |
| `modules/postgresql` | RDS Aurora PostgreSQL with multi-AZ, encryption, backups |
| `modules/redis` | ElastiCache Redis cluster with cluster mode, encryption |
| `modules/kafka` | MSK (Managed Kafka) with encryption, ACLs, monitoring |
| `modules/s3` | S3 buckets with encryption, versioning, lifecycle, cross-region replication |
| `modules/iam` | IAM roles, policies, OIDC provider for IRSA |
| `modules/waf` | AWS WAF v2 with managed rules, rate limiting, geo-blocking |

## Tenant Isolation Strategy
- **Namespace isolation**: Each tenant gets dedicated K8s namespace: `tenant-{tid}`
- **Network isolation**: Istio sidecars enforce mTLS + L4/L7 policies per namespace
- **Resource quotas**: Per-tenant CPU/memory limits, pod count limits, storage quotas
- **IAM isolation**: IRSA (IAM Roles for Service Accounts) per tenant namespace
- **Database isolation**: Schema-per-tenant in shared Aurora cluster; dedicated instances for enterprise tier
- **Storage isolation**: Per-tenant S3 prefix with bucket policies enforcing prefix access
- **Kafka isolation**: Tenant-scoped topics with ACLs; no cross-topic read access

## Security Boundaries
- Private subnets for all workloads; no public IPs on pods
- VPC endpoints for all AWS services (S3, ECR, CloudWatch, KMS) — no internet egress required
- Network policies (Calico/Cilium) default-deny; explicit allow rules only
- Pod security standards (PSS) enforced: restricted profile
- Image scanning (Trivy) mandatory before deployment; block on CRITICAL vulnerabilities
- Secrets never in Terraform state; external data sources to Vault
- Encryption at rest: EBS volumes, RDS, S3, ElastiCache, MSK
- Encryption in transit: TLS 1.3 for all service mesh traffic

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Terraform plan/apply logs → Loki; ArgoCD sync logs → Loki |
| Metrics | `infra_provision_duration_seconds`, `infra_resource_usage`, `infra_cost_per_tenant` |
| Traces | OpenTelemetry on infrastructure API calls |
| Alerts | Provisioning failure, resource quota exhaustion, cost anomaly > 20%, security policy violation |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Terraform apply failure | Exit code non-zero | Auto-rollback via Terraform state, alert, manual review |
| Node pool exhaustion | Karpenter pending pods | Scale node pool, alert if > 5min pending |
| Database failover | RDS Aurora failover event | Automatic (Aurora handles), alert, verify app recovery |
| Network partition | Cilium connectivity tests | Alert, verify mesh health, restart affected pods |
| Cost spike | Kubecost anomaly detection | Alert FinOps team, investigate resource abuse |
| Security policy violation | Gatekeeper/Kyverno deny | Block deployment, alert security team, require exception approval |

## Configuration
```yaml
infra:
  provider: "aws"  # aws | gcp | azure
  region: "us-east-1"
  environments:
    dev:
      cluster_version: "1.30"
      node_instance_type: "m6i.xlarge"
      min_nodes: 2
      max_nodes: 10
    staging:
      cluster_version: "1.30"
      node_instance_type: "m6i.2xlarge"
      min_nodes: 3
      max_nodes: 20
    production:
      cluster_version: "1.30"
      node_instance_type: "m6i.4xlarge"
      min_nodes: 6
      max_nodes: 100
  networking:
    vpc_cidr: "10.0.0.0/16"
    private_subnets: ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
    public_subnets: ["10.0.101.0/24", "10.0.102.0/24", "10.0.103.0/24"]
  database:
    instance_class: "db.r6g.xlarge"
    multi_az: true
    backup_retention: 35
    encryption: true
  storage:
    versioning: true
    replication_enabled: true
    replication_region: "us-west-2"
  security:
    waf_enabled: true
    ddos_protection: true
    pod_security_standard: "restricted"
```

## Dependencies
- `platform-secrets` — Terraform backend credentials, encryption keys
- `platform-observability` — Infrastructure metrics, logs, alerting
- `platform-identity` — IRSA role mapping, service account auth
