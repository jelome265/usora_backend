# ADR-005: Redis Key Namespacing for Tenant Isolation

## Status

Accepted

## Context

Redis is used for session storage, rate limiting counters, response caching, and feature flag storage. In a multi-tenant environment, Redis keys must be strictly isolated to prevent cross-tenant data leakage. Unlike PostgreSQL where schema-per-tenant provides strong isolation, Redis is a flat key-value store with no built-in multi-tenant isolation mechanism.

Options evaluated:

1. **Key Namespacing** — Prefix all keys with `tenant:{tenant_id}:{key_type}:{key}`.
2. **Redis Database-per-Tenant** — Each tenant gets a dedicated Redis logical database (0-15). Limited to 16 databases per Redis instance.
3. **Redis Cluster-per-Tenant** — Each tenant gets their own Redis cluster. Maximum isolation but extreme cost and complexity.
4. **Redis Key Patterns with ACL** — Redis 6+ ACLs can restrict key patterns per user, but complex to manage at scale.
5. **Separate Redis Instances** — Each tenant gets their own Redis instance. Similar to cluster-per-tenant but with single-node instances.

## Decision

Use **Redis Key Namespacing** with mandatory `tenant:{tenant_id}` prefix for all tenant-scoped keys.

## Consequences

### Positive

- **Simple and effective**: Every key is prefixed with the tenant ID, making cross-tenant access impossible without knowing the prefix.
- **No Redis architecture changes**: Works with standard Redis/Redis Cluster. No special Redis configuration needed.
- **Operational simplicity**: Single Redis cluster to manage. Backup, monitoring, and scaling are straightforward.
- **Performance**: No overhead from database switching or ACL evaluation. Key lookup is O(1) regardless of prefix.
- **Flexibility**: Supports both tenant-scoped and global keys (e.g., `global:rate_limit:ip:{ip}` for DDoS protection).
- **Key scanning safety**: `KEYS` or `SCAN` operations filtered by tenant prefix prevent accidental cross-tenant enumeration.

### Negative

- **No database-enforced isolation**: Relies on application discipline to always include the tenant prefix. A bug in key construction could leak data.
- **Key length overhead**: Tenant UUID (36 chars) + prefix adds ~45 bytes per key. For millions of keys, this adds memory overhead.
- **No per-tenant memory limits**: Redis does not enforce memory quotas per namespace. A single tenant could consume disproportionate memory.
- **Cross-tenant operations impossible**: Cannot perform Redis operations across tenants (e.g., `MGET` across tenant keys) without client-side coordination.

### Mitigations

- **Application-level enforcement**: All Redis access goes through a tenant-aware wrapper that injects the prefix. Direct Redis access is prohibited outside the wrapper.
- **Key construction validation**: Lint rules and code review enforce that all Redis keys include tenant prefix. Runtime assertions verify prefix presence.
- **Memory monitoring**: Per-tenant memory usage tracked via key pattern analysis. Alerts if a tenant exceeds expected memory usage.
- **Redis memory policies**: `allkeys-lru` eviction policy with per-tenant TTLs. Global keys have longer TTLs than tenant-scoped keys.
- **Key prefix validation middleware**: Gateway validates that all Redis keys in requests include the correct tenant prefix.

## Key Naming Convention

```
tenant:{tenant_id}:session:{session_id}       → Session data
tenant:{tenant_id}:rate_limit:{client_id}     → Rate limit counters
tenant:{tenant_id}:cache:{cache_key}          → Cached responses
tenant:{tenant_id}:feature_flags             → Tenant configuration
tenant:{tenant_id}:biometric:template:{id}    → Biometric template index metadata
global:rate_limit:ip:{ip_address}            → Global IP-based rate limiting
global:health:gateway:{region}               → Global health checks
```

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| Redis Database-per-Tenant | Limited to 16 databases per instance. Not scalable for thousands of tenants. |
| Redis Cluster-per-Tenant | Extreme cost and operational complexity. Thousands of clusters to manage. |
| Redis ACL with Key Patterns | Complex to manage at scale. ACL evaluation adds latency. Limited tooling for dynamic tenant creation. |
| Separate Redis Instances | Similar to cluster-per-tenant but worse resource utilization. Not feasible for SaaS model. |

## Related Decisions

- ADR-004: Schema-per-Tenant with Row-Level Security for PostgreSQL
- ADR-007: Kafka Topic Design for Multi-Tenant Event Bus
- ADR-001: Rust + Tokio for API Gateway Layer (rate limiting implementation)

## Date

2026-02-10

## Author

Diana Ross, Data Lead

## Reviewed By

Alice Chen (Platform Lead), Bob Martinez (Backend Lead)
