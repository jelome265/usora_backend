# Agent: Data PostgreSQL

## Metadata
- **Agent ID**: `usora-agent-data-postgresql`
- **Tier**: 4 — Data & Persistence
- **Owner**: Database Engineering / SRE
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Data PostgreSQL agent manages the primary relational database layer for USORA using a schema-per-tenant architecture on Amazon Aurora PostgreSQL. It handles tenant schema provisioning, connection pooling, read replica management, automated partitioning, migrations, and encryption with strict tenant isolation.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Database | Amazon Aurora PostgreSQL | 16+ |
| Connection Pool | PgBouncer / HikariCP | 1.22+ / 5.1+ |
| Migrations | Flyway / Liquibase | 10+ / 4.27+ |
| Partitioning | PostgreSQL native + pg_partman | 5.1+ |
| Encryption | AWS KMS / pgcrypto | latest |
| Backup | Aurora automated + manual snapshots | — |
| Monitoring | pg_stat_statements + pgHero | latest |

## API Surface

### gRPC Services
```protobuf
service PostgreSQLService {
  rpc ProvisionSchema(SchemaProvisionRequest) returns (SchemaProvisionResponse);
  rpc DeprovisionSchema(SchemaDeprovisionRequest) returns (SchemaDeprovisionResponse);
  rpc RunMigration(MigrationRequest) returns (MigrationResponse);
  rpc GetConnectionPoolStats(PoolStatsRequest) returns (PoolStatsResponse);
  rpc ExecuteQuery(QueryRequest) returns (QueryResponse);
  rpc GetReplicationLag(ReplicationLagRequest) returns (ReplicationLagResponse);
  rpc CreatePartition(PartitionRequest) returns (PartitionResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/db/schema/provision` | Provision tenant schema |
| DELETE | `/api/v1/db/schema/{tenantId}` | Deprovision tenant schema |
| POST | `/api/v1/db/migrate` | Run database migration |
| GET | `/api/v1/db/pool-stats` | Get connection pool statistics |
| POST | `/api/v1/db/query` | Execute read-only query (admin) |
| GET | `/api/v1/db/replication-lag` | Get read replica lag |
| POST | `/api/v1/db/partition/create` | Create table partition |

## Tenant Isolation Strategy
- **Schema isolation**: Each tenant gets dedicated schema: `tenant_{tid}` with separate tables
- **Connection pooling**: PgBouncer per-tenant pools with max 20 connections
- **Row-level security (RLS)**: Fallback RLS policies on shared tables
- **Resource quotas**: Per-tenant CPU/memory limits via Aurora Serverless v2 ACU
- **Storage quotas**: Per-tenant storage limits with soft/hard thresholds
- **Backup isolation**: Per-tenant logical backups; point-in-time recovery per tenant
- **Encryption isolation**: Per-tenant column-level encryption keys via AWS KMS

## Security Boundaries
- All data encrypted at rest (Aurora storage encryption) and in transit (TLS 1.3)
- Column-level encryption for PII fields (SSN, passport, biometric hashes)
- No superuser access for application; least-privilege roles per tenant
- SQL injection prevention via parameterized queries enforced at pool level
- Audit logging: all DDL and DML logged to immutable audit table
- Backup encryption: snapshots encrypted with tenant-specific KMS keys
- Cross-tenant query prevention: schema search_path locked per connection

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Query logs → Loki (slow query log, DDL log) |
| Metrics | `postgresql_connections_active`, `postgresql_replication_lag_seconds`, `postgresql_transaction_rate`, `postgresql_slow_query_total`, `postgresql_tenant_storage_usage` |
| Traces | OpenTelemetry spans per query; propagated from application layer |
| Alerts | Replication lag > 5s, connection pool saturation > 90%, slow query rate > 1%, storage > 85% |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Primary failover | Aurora health check | Automatic failover (< 30s), alert, verify app recovery |
| Read replica lag | pg_stat_replication | Route reads to primary, scale replicas, alert |
| Connection pool exhaustion | PgBouncer metrics | Queue connections, scale pool, alert |
| Migration failure | Flyway/Liquibase error | Rollback transaction, alert, manual intervention |
| Storage saturation | CloudWatch metric | Auto-scale storage, alert, review data retention |
| Query timeout | statement_timeout | Kill query, return error, log slow query, alert |
| Schema corruption | Checksum verification | Restore from backup, alert, incident response |

## Configuration
```yaml
postgresql:
  aurora:
    cluster_identifier: "usora-primary"
    engine_version: "16.4"
    instance_class: "db.r6g.xlarge"
    multi_az: true
    backup_retention: 35
    preferred_backup_window: "03:00-04:00"
    deletion_protection: true
  pgbouncer:
    max_client_conn: 10000
    default_pool_size: 20
    min_pool_size: 5
    reserve_pool_size: 5
    reserve_pool_timeout: 3
    max_db_connections: 100
    query_timeout: 30000
    query_wait_timeout: 120000
  partitioning:
    enabled: true
    strategy: "range"
    partition_column: "created_at"
    partition_interval: "1 month"
    retention_months: 24
  encryption:
    at_rest: true
    in_transit: true
    column_level:
      enabled: true
      algorithm: "aes-256-gcm"
      key_provider: "aws_kms"
  migrations:
    tool: "flyway"
    locations: ["classpath:db/migration/tenant"]
    baseline_on_migrate: true
    validate_on_migrate: true
    out_of_order: false
```

## Dependencies
- `platform-infra` — Aurora provisioning, VPC, security groups
- `platform-secrets` — KMS keys, DB credentials
- `platform-observability` — Metrics, logs, alerting
- `orchestrator-tenant` — Schema provisioning on tenant onboarding
- `data-retention` — Partition management, data purging
