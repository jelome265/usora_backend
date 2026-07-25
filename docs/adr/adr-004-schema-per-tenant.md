# ADR-004: Schema-per-Tenant with Row-Level Security for PostgreSQL

## Status

Accepted

## Context

USORA is a multi-tenant platform where each tenant's data must be strictly isolated. A single PostgreSQL cluster serves all tenants, but data leakage between tenants would be a catastrophic compliance violation (GDPR, SOC 2, PCI DSS). We need a database isolation strategy that balances:

- **Security**: Strong tenant isolation, provable at the database level
- **Operational simplicity**: Manageable number of database objects, backup/restore procedures
- **Performance**: No significant query overhead from isolation mechanism
- **Scalability**: Support for thousands of tenants without database architecture changes
- **Cost**: Efficient use of database resources

Options evaluated:

1. **Schema-per-Tenant** — Each tenant gets a dedicated PostgreSQL schema with identical table structures. RLS policies add defense in depth.
2. **Row-Level Security (RLS) only** — Single schema, all tenant data in shared tables with RLS policies filtering rows per tenant.
3. **Database-per-Tenant** — Each tenant gets a dedicated PostgreSQL database. Maximum isolation but operational nightmare at scale.
4. **Shared Table with Tenant ID Column** — Simplest, but relies entirely on application-level filtering; no database-enforced isolation.
5. **Separate PostgreSQL Instances** — Each tenant gets their own PostgreSQL server. Maximum isolation, maximum cost and complexity.

## Decision

Use **Schema-per-Tenant with Row-Level Security (RLS)** as the primary isolation strategy.

## Consequences

### Positive

- **Strong isolation**: Schema separation provides physical separation of tenant data at the database object level. RLS adds a second layer of defense — even if a query accidentally omits the tenant filter, RLS prevents data leakage.
- **Operational simplicity**: Single PostgreSQL cluster to manage. Backup/restore is schema-aware — can restore a single tenant without affecting others.
- **Performance**: Schema search path allows efficient query routing. No RLS overhead for properly parameterized queries (tenant context set once per connection).
- **Scalability**: PostgreSQL supports thousands of schemas per database. Connection pooling (PgBouncer) handles the multiplexing efficiently.
- **Migration flexibility**: Schema-per-tenant allows per-tenant schema migrations — can roll out changes gradually without affecting all tenants simultaneously.
- **Compliance**: Schema separation + RLS provides strong evidence for auditors that tenant isolation is enforced at the database level, not just application logic.

### Negative

- **Schema management overhead**: DDL operations (migrations) must be applied across all tenant schemas. Requires custom migration tooling.
- **Connection context**: Every database connection must set the tenant context (`SET app.current_tenant = 'tenant-id'`) before executing queries. Connection pool must be tenant-aware.
- **Cross-tenant analytics**: Queries across all tenants require dynamic SQL or `UNION ALL` across schemas. ClickHouse handles cross-tenant analytics separately.
- **Schema count limits**: PostgreSQL supports ~10,000 schemas per database comfortably. For extreme scale, may need database sharding.
- **Backup complexity**: pg_dump per schema is slower than single-database dump. Requires parallel backup strategy.

### Mitigations

- Custom migration tool (`usora-migrate`) applies schema changes across all tenant schemas with rollback capability and dry-run mode.
- Connection pool (PgBouncer) uses transaction-level pooling with tenant context reset on every transaction boundary.
- Cross-tenant analytics delegated to ClickHouse, which ingests from Kafka and maintains tenant-isolated tables.
- Automated backup uses parallel `pg_dump` with schema filtering; tested restore procedures documented in runbook.
- Schema count monitored; sharding strategy planned for >5,000 tenants.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| RLS only (shared schema) | Single point of failure — if RLS policy is disabled or bypassed, all tenant data is exposed. Less provable isolation for auditors. |
| Database-per-tenant | Operational nightmare: thousands of databases to backup, patch, monitor. Connection pool exhaustion. Excessive resource overhead. |
| Shared table (app-level filtering) | No database-enforced isolation. Application bug = data breach. Unacceptable for compliance requirements. |
| Separate instances | Maximum cost and complexity. Not feasible for SaaS model with thousands of tenants. |

## Related Decisions

- ADR-005: Redis Key Namespacing for Tenant Isolation
- ADR-007: Kafka Topic Design for Multi-Tenant Event Bus
- ADR-012: ClickHouse for Cross-Tenant Analytics

## Date

2026-02-10

## Author

Diana Ross, Data Lead

## Reviewed By

Alice Chen (Platform Lead), Bob Martinez (Backend Lead)
