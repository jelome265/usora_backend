# Agent: Data Redis

## Metadata
- **Agent ID**: `usora-agent-data-redis`
- **Tier**: 4 — Data & Persistence
- **Owner**: Database Engineering / SRE
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The Data Redis agent provides namespaced caching, session storage, rate limiting counters, pub/sub messaging, and distributed locking for USORA. It enforces strict tenant isolation through key namespacing, ACLs, and memory quotas with cluster-mode replication for high availability.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Cache Engine | Redis Cluster | 7.2+ |
| Client | lettuce (Java) / redis-rs (Rust) | 6.4+ / 0.25+ |
| Sentinel | Redis Sentinel | 7.2+ |
| Persistence | AOF + RDB | — |
| Encryption | TLS 1.3 + ACL passwords | — |
| Monitoring | Redis Exporter + Grafana | latest |

## API Surface

### gRPC Services
```protobuf
service RedisService {
  rpc GetCacheEntry(CacheGetRequest) returns (CacheGetResponse);
  rpc SetCacheEntry(CacheSetRequest) returns (CacheSetResponse);
  rpc DeleteCacheEntry(CacheDeleteRequest) returns (CacheDeleteResponse);
  rpc IncrementCounter(CounterIncrementRequest) returns (CounterIncrementResponse);
  rpc GetRateLimitStatus(RateLimitStatusRequest) returns (RateLimitStatusResponse);
  rpc PublishMessage(PublishRequest) returns (PublishResponse);
  rpc SubscribeChannel(SubscribeRequest) returns (stream SubscribeResponse);
  rpc AcquireLock(LockRequest) returns (LockResponse);
  rpc ReleaseLock(LockReleaseRequest) returns (LockReleaseResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/v1/cache/{key}` | Get cached value |
| POST | `/api/v1/cache/{key}` | Set cached value |
| DELETE | `/api/v1/cache/{key}` | Delete cached value |
| POST | `/api/v1/counter/increment` | Increment counter |
| GET | `/api/v1/rate-limit/status` | Get rate limit status |
| POST | `/api/v1/pubsub/publish` | Publish message |
| POST | `/api/v1/lock/acquire` | Acquire distributed lock |
| POST | `/api/v1/lock/release` | Release distributed lock |

## Tenant Isolation Strategy
- **Key namespacing**: All keys prefixed with `tenant:{tid}:` — enforced at client level
- **ACL isolation**: Per-tenant Redis ACL users with restricted key patterns
- **Memory quotas**: Per-tenant `maxmemory-policy` with eviction on quota exceed
- **Database isolation**: Separate logical databases per tenant (0-15 in Redis Cluster)
- **Channel isolation**: Pub/sub channels prefixed with `tenant:{tid}:`
- **Rate limit isolation**: Counters per `(tenant_id, endpoint, window)` tuple

## Security Boundaries
- All Redis connections over TLS 1.3 with mutual authentication
- ACL passwords rotated every 30 days via Vault
- No `KEYS` command allowed; `SCAN` with tenant prefix only
- Sensitive data (session tokens, PII) encrypted before Redis storage
- Lua scripts sandboxed; no arbitrary code execution
- Memory quotas prevent noisy-neighbor DoS
- Backup encryption: RDB snapshots encrypted with tenant-specific keys

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Slow log (> 10ms) → Loki; ACL events → audit log |
| Metrics | `redis_commands_total`, `redis_memory_usage_bytes`, `redis_cache_hit_ratio`, `redis_rate_limit_hits`, `redis_evicted_keys_total` |
| Traces | OpenTelemetry spans per Redis command; propagated from caller |
| Alerts | Memory usage > 85%, eviction rate > 1%, replication lag > 1s, slow command rate > 0.1% |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Master failover | Sentinel promotion | Automatic failover (< 5s), alert, verify app reconnection |
| Cluster slot imbalance | CLUSTER INFO | Rebalance slots, alert |
| Memory saturation | `used_memory` > `maxmemory` | Evict per policy, alert, scale cluster |
| Replication lag | `master_repl_offset` diff | Route reads to master, alert, investigate network |
| ACL password expiry | Connection auth failure | Auto-rotate via Vault, reconnect clients |
| Cache stampede | Key miss spike | Circuit breaker, cache warming, alert |

## Configuration
```yaml
redis:
  cluster:
    nodes:
      - "redis-0.usora.svc.cluster.local:6379"
      - "redis-1.usora.svc.cluster.local:6379"
      - "redis-2.usora.svc.cluster.local:6379"
    replicas_per_master: 2
    require_full_coverage: true
  sentinel:
    enabled: true
    quorum: 2
    down_after_milliseconds: 5000
    failover_timeout: 10000
  persistence:
    aof:
      enabled: true
      appendfsync: "everysec"
      rewrite_incremental_fsync: true
    rdb:
      enabled: true
      save: ["900 1", "300 10", "60 10000"]
  security:
    tls:
      enabled: true
      cert_file: "/secrets/redis/tls/cert.pem"
      key_file: "/secrets/redis/tls/key.pem"
      ca_cert_file: "/secrets/redis/tls/ca.pem"
    acl:
      enabled: true
      password_rotation_days: 30
      key_prefix_enforcement: true
  performance:
    maxmemory_policy: "allkeys-lru"
    slowlog_log_slower_than: 10000  # 10ms
    slowlog_max_len: 128
    tcp_keepalive: 300
  tenant_quotas:
    default_memory_mb: 512
    default_max_keys: 100000
    default_ttl_seconds: 3600
```

## Dependencies
- `platform-infra` — Redis Cluster provisioning, networking
- `platform-secrets` — ACL password management, TLS certificates
- `platform-observability` — Metrics, logs, alerting
- `platform-gateway` — Rate limiting backend
- `platform-identity` — Session storage backend
