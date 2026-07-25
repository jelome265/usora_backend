# USORA — Operations Runbook

## 1. Document Overview

| Field | Value |
|---|---|
| **Version** | 1.0.0 |
| **Audience** | SRE, Platform Engineers, On-Call Engineers |
| **Classification** | Internal — Confidential |
| **Last Updated** | 2026-07-21 |
| **Review Cycle** | Monthly |

---

## 2. On-Call Structure

### 2.1 Rotation

| Tier | Response Time | Escalation Path |
|---|---|---|
| **L1 — Primary** | 5 minutes | Direct alert |
| **L2 — Secondary** | 15 minutes | Auto-escalate if L1 unacknowledged |
| **L3 — Engineering Lead** | 30 minutes | Auto-escalate if L2 unacknowledged |
| **L4 — Incident Commander** | 1 hour | Manual escalation for major incidents |

### 2.2 Severity Definitions

| Severity | Criteria | Response | Examples |
|---|---|---|---|
| **P1 — Critical** | Complete service outage, data loss, security breach | Immediate, all-hands | All regions down, tenant data leaked |
| **P2 — High** | Degraded service, partial outage, SLA breach imminent | 15 minutes | Single region down, >5% error rate |
| **P3 — Medium** | Performance degradation, non-critical feature failure | 1 hour | Elevated latency, dashboard slow |
| **P4 — Low** | Cosmetic issues, monitoring gaps, documentation | 4 hours | Alert false positive, typo in logs |

### 2.3 Communication Channels

| Channel | Purpose |
|---|---|
| PagerDuty | Primary alerting and escalation |
| Slack `#usora-alerts` | Real-time alert stream |
| Slack `#usora-incidents` | Active incident coordination |
| Slack `#usora-postmortems` | Blameless postmortem discussions |
| Zoom bridge | War room for P1/P2 incidents |
| Status page | External customer communication |

---

## 3. Service Catalog

### 3.1 Service Inventory

| Service | Language | Namespace | Criticality | Owner |
|---|---|---|---|---|
| `gateway` | Rust | `usora-gateway` | Critical | Platform Team |
| `orchestration` | Java | `usora-orchestration` | Critical | Backend Team |
| `compute-document` | Rust | `usora-compute` | Critical | ML Team |
| `compute-biometric` | Rust | `usora-compute` | Critical | ML Team |
| `compute-risk` | Rust | `usora-compute` | High | ML Team |
| `compute-fraud` | Rust | `usora-compute` | High | ML Team |
| `postgres-primary` | PostgreSQL | `usora-data` | Critical | Data Team |
| `postgres-replica` | PostgreSQL | `usora-data` | High | Data Team |
| `redis-cluster` | Redis | `usora-data` | Critical | Data Team |
| `kafka` | Kafka | `usora-data` | Critical | Data Team |
| `clickhouse` | ClickHouse | `usora-data` | Medium | Data Team |
| `vault` | HashiCorp Vault | `usora-security` | Critical | Security Team |

### 3.2 Dependency Graph

```
Client → Edge (CloudFlare) → Gateway (Rust)
                                    │
                    ┌───────────────┼───────────────┐
                    │               │               │
                    ▼               ▼               ▼
            Orchestration      Compute Direct    Cache
            (Java Spring)      (Rust Health)     (Redis)
                    │
        ┌───────────┼───────────┐
        │           │           │
        ▼           ▼           ▼
    Compute     Compute     Compute
    Document    Biometric   Risk/Fraud
    (Rust)      (Rust)      (Rust)
        │           │           │
        └───────────┴───────────┘
                    │
                    ▼
            Kafka → PostgreSQL
                    → S3
                    → ClickHouse
```

---

## 4. Alert Runbooks

### 4.1 GatewayHighErrorRate (P1)

**Alert:** `rate(usora_gateway_requests_total{status=~"5.."}[5m]) > 0.01`

**Symptoms:**
- Customers receiving 500/502/503/504 errors
- Error rate >1% over 5 minutes
- Potential revenue impact

**Diagnosis:**

```bash
# 1. Check gateway pod status
kubectl get pods -n usora-gateway -o wide
kubectl describe pod <gateway-pod> -n usora-gateway

# 2. Check gateway logs
kubectl logs -n usora-gateway -l app=gateway --tail=500 | grep ERROR

# 3. Check upstream health
kubectl get pods -n usora-orchestration
kubectl get pods -n usora-compute

# 4. Check gateway metrics
curl http://gateway-metrics:9090/metrics | grep usora_gateway_requests_total

# 5. Check circuit breaker state
curl http://gateway-metrics:9090/metrics | grep usora_gateway_circuit_breaker_state
```

**Remediation:**

| Scenario | Action |
|---|---|
| Gateway pods crashing | Check OOMKilled: `kubectl describe pod`. Scale horizontally: `kubectl scale deployment gateway --replicas=10 -n usora-gateway` |
| Upstream (orchestration) down | Check orchestration pods. If unhealthy, restart: `kubectl rollout restart deployment orchestration -n usora-orchestration` |
| Upstream (compute) down | Check compute pods. If queue backlog, scale compute workers |
| Circuit breaker open | Identify failing upstream, check logs, resolve root cause, breaker auto-closes on success |
| Database connection pool exhausted | Check PgBouncer: `kubectl logs -n usora-data -l app=pgbouncer`. Scale pool size |
| Redis unavailable | Check Redis cluster: `redis-cli -h redis-cluster CLUSTER INFO`. Failover if needed |

**Escalation:** If error rate not reduced within 15 minutes, escalate to L3.

---

### 4.2 OrchestrationWorkflowStuck (P1)

**Alert:** `usora_orchestration_workflow_active > 10000`

**Symptoms:**
- Verifications not progressing
- Customer complaints about stalled KYC
- Kafka consumer lag increasing

**Diagnosis:**

```bash
# 1. Check orchestration pod status
kubectl get pods -n usora-orchestration
kubectl top pods -n usora-orchestration

# 2. Check Camunda database connections
kubectl exec -it postgres-primary-0 -n usora-data -- psql -U camunda -c "SELECT count(*) FROM pg_stat_activity WHERE datname='camunda';"

# 3. Check Kafka consumer lag
kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group orchestration-workers

# 4. Check workflow engine metrics
curl http://orchestration-metrics:8080/actuator/metrics/camunda.bpmn.executions.active

# 5. Check for deadlocks
kubectl logs -n usora-orchestration -l app=orchestration --tail=1000 | grep -i "deadlock\|timeout\|lock"
```

**Remediation:**

| Scenario | Action |
|---|---|
| Orchestration pods at CPU limit | Scale: `kubectl scale deployment orchestration --replicas=6 -n usora-orchestration` |
| Camunda DB connection pool exhausted | Increase pool size in application.yml, rolling restart |
| Kafka consumer lag high | Scale consumer replicas, check for poison pill messages |
| External task workers stuck | Check compute worker health, restart if needed |
| Database deadlock | Identify blocking queries, kill if safe, analyze deadlock graph |
| Workflow instance corruption | Query Camunda API for stuck instances, manually migrate state if needed |

**Escalation:** If >1000 workflows remain stuck after 30 minutes, escalate to L3 + page Workflow Team.

---

### 4.3 ComputeTaskQueueBacklog (P1)

**Alert:** `usora_orchestration_task_queue_depth > 100000`

**Symptoms:**
- Document/biometric analysis taking >30 seconds
- Customer complaints about slow verification
- Kafka partition lag visible in dashboards

**Diagnosis:**

```bash
# 1. Check compute pod status and resource usage
kubectl get pods -n usora-compute -o wide
kubectl top pods -n usora-compute

# 2. Check Kafka lag by partition
kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group compute-document-workers
kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group compute-biometric-workers

# 3. Check compute worker logs
kubectl logs -n usora-compute -l app=compute-document --tail=500 | grep ERROR
kubectl logs -n usora-compute -l app=compute-biometric --tail=500 | grep ERROR

# 4. Check model inference latency
curl http://compute-metrics:9090/metrics | grep usora_compute_inference_duration_seconds

# 5. Check GPU utilization (if applicable)
nvidia-smi
```

**Remediation:**

| Scenario | Action |
|---|---|
| Compute pods at CPU limit | Scale HPA: `kubectl scale deployment compute-document --replicas=20 -n usora-compute` |
| Model inference slow | Check model version, rollback if regression. Check GPU memory |
| Kafka partition imbalance | Rebalance partitions, check partition key distribution |
| OOMKilled compute pods | Increase memory limits, check for memory leaks |
| Model loading failures | Verify model artifact in S3, check model version compatibility |
| Dead letter queue growing | Inspect DLQ messages, fix root cause, replay valid messages |

**Escalation:** If backlog not clearing within 20 minutes, escalate to L3 + page ML Team.

---

### 4.4 DatabaseReplicationLag (P2)

**Alert:** `pg_replication_lag_seconds > 5`

**Symptoms:**
- Read replicas serving stale data
- Inconsistent verification status across queries
- Potential for split-brain if failover occurs

**Diagnosis:**

```bash
# 1. Check replication lag
kubectl exec -it postgres-primary-0 -n usora-data -- psql -U postgres -c "SELECT client_addr, state, sent_lsn, write_lsn, flush_lsn, replay_lsn, pg_size_pretty(pg_wal_lsn_diff(sent_lsn, replay_lsn)) as lag FROM pg_stat_replication;"

# 2. Check WAL generation rate
kubectl exec -it postgres-primary-0 -n usora-data -- psql -U postgres -c "SELECT pg_current_wal_lsn();"

# 3. Check replica status
kubectl exec -it postgres-replica-0 -n usora-data -- psql -U postgres -c "SELECT pg_last_wal_receive_lsn(), pg_last_wal_replay_lsn(), pg_last_xact_replay_timestamp();"

# 4. Check network between primary and replica
kubectl exec -it postgres-primary-0 -n usora-data -- ping postgres-replica-0.postgres-replica

# 5. Check disk I/O on replica
kubectl exec -it postgres-replica-0 -n usora-data -- iostat -x 1 5
```

**Remediation:**

| Scenario | Action |
|---|---|
| Network latency between primary/replica | Check inter-zone network, consider same-zone placement |
| Replica disk I/O saturated | Scale replica storage IOPS, check for vacuum operations |
| Large transaction holding WAL | Identify long-running transaction, evaluate kill |
| Replica crash/restart | Allow catch-up, monitor lag. If persistent, rebuild replica |
| WAL archive accumulation | Check archive_command, ensure S3 connectivity |

**Escalation:** If lag >30 seconds for >10 minutes, escalate to L3 + page Data Team.

---

### 4.5 GatewayHighLatency (P2)

**Alert:** `histogram_quantile(0.99, usora_gateway_request_duration_seconds) > 0.1`

**Symptoms:**
- API response times >100ms p99
- Customer-facing slowness
- Potential timeout cascades

**Diagnosis:**

```bash
# 1. Check gateway resource usage
kubectl top pods -n usora-gateway

# 2. Check per-endpoint latency breakdown
curl http://gateway-metrics:9090/metrics | grep usora_gateway_request_duration_seconds_bucket

# 3. Check upstream latency
curl http://orchestration-metrics:8080/actuator/metrics/http.server.requests

# 4. Check Redis latency
redis-cli --latency -h redis-cluster

# 5. Check for GC pauses (Java) or async stalls (Rust)
kubectl logs -n usora-gateway -l app=gateway --tail=100 | grep "slow\|stall\|block"
```

**Remediation:**

| Scenario | Action |
|---|---|
| Gateway CPU throttled | Increase CPU limits, scale replicas |
| Upstream (orchestration) slow | Check orchestration metrics, scale if needed |
| Redis cache misses high | Warm cache, check TTL configuration, scale Redis cluster |
| TLS handshake overhead | Check certificate validity, consider session resumption |
| Rate limiting Redis slow | Check Redis cluster health, consider local cache tier |

---

### 4.6 CertificateExpiry (P2)

**Alert:** `certificate_expiry_days < 30`

**Diagnosis:**

```bash
# Check all certificates
kubectl get certificates -A
kubectl describe certificate gateway-tls -n usora-gateway

# Check cert-manager logs
kubectl logs -n usora-security -l app=cert-manager --tail=100

# Manual certificate check
echo | openssl s_client -connect api.usora.io:443 2>/dev/null | openssl x509 -noout -dates
```

**Remediation:**

| Scenario | Action |
|---|---|
| cert-manager failed renewal | Check DNS-01/HTTP-01 challenge, fix DNS/config, trigger manual renewal |
| Vault PKI certificate expiry | Renew via Vault API: `vault write pki/issue/usora common_name=...` |
| mTLS service certificate expiry | Rotate via SPIFFE/SPIRE or manual rollout |

---

### 4.7 VaultSealStatus (P1)

**Alert:** `vault_status == "sealed"`

**Symptoms:**
- Services cannot retrieve secrets
- Dynamic database credentials failing
- New pods failing to start

**Diagnosis:**

```bash
# Check Vault status
kubectl exec -it vault-0 -n usora-security -- vault status

# Check Vault logs
kubectl logs -n usora-security -l app=vault --tail=500

# Check unseal key availability
kubectl get secret vault-unseal-keys -n usora-security
```

**Remediation:**

```bash
# Unseal Vault (requires 3 of 5 unseal keys)
kubectl exec -it vault-0 -n usora-security -- vault operator unseal <key1>
kubectl exec -it vault-0 -n usora-security -- vault operator unseal <key2>
kubectl exec -it vault-0 -n usora-security -- vault operator unseal <key3>

# Verify status
kubectl exec -it vault-0 -n usora-security -- vault status
```

**Escalation:** If unseal keys unavailable, page Security Team lead immediately.

---

## 5. Common Procedures

### 5.1 Scaling Gateway

```bash
# Check current state
kubectl get deployment gateway -n usora-gateway
kubectl top deployment gateway -n usora-gateway

# Scale horizontally
kubectl scale deployment gateway --replicas=15 -n usora-gateway

# Verify rollout
kubectl rollout status deployment gateway -n usora-gateway

# Monitor metrics
curl http://gateway-metrics:9090/metrics | grep usora_gateway_active_connections
```

### 5.2 Scaling Compute Workers

```bash
# Check current queue depth
kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group compute-document-workers

# Scale document workers
kubectl scale deployment compute-document --replicas=30 -n usora-compute

# Scale biometric workers
kubectl scale deployment compute-biometric --replicas=20 -n usora-compute

# Verify pods are ready
kubectl get pods -n usora-compute -w

# Monitor lag reduction
watch -n 5 'kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group compute-document-workers'
```

### 5.3 Restarting Orchestration

```bash
# Graceful rolling restart
kubectl rollout restart deployment orchestration -n usora-orchestration

# Monitor rollout
kubectl rollout status deployment orchestration -n usora-orchestration --timeout=300s

# Verify workflow engine health
curl http://orchestration:8080/actuator/health

# Check for workflow instance recovery
kubectl logs -n usora-orchestration -l app=orchestration --tail=100 | grep "recovery\|resumed"
```

### 5.4 PostgreSQL Failover

```bash
# 1. Verify primary is actually down
kubectl exec -it postgres-primary-0 -n usora-data -- pg_isready

# 2. Promote replica to primary
kubectl exec -it postgres-replica-0 -n usora-data -- pg_ctl promote -D /var/lib/postgresql/data

# 3. Update service endpoints
kubectl patch service postgres-primary -n usora-data -p '{"spec":{"selector":{"role":"primary"}}}'

# 4. Verify application connectivity
kubectl exec -it orchestration-xxx -n usora-orchestration -- psql $DATABASE_URL -c "SELECT 1;"

# 5. Rebuild old primary as replica (after recovery)
# Follow PostgreSQL pg_basebackup + replication slot procedure
```

### 5.5 Kafka Partition Rebalance

```bash
# Check current partition distribution
kafka-topics.sh --bootstrap-server kafka:9092 --describe --topic verification.events

# Increase partitions (if needed)
kafka-topics.sh --bootstrap-server kafka:9092 --alter --topic verification.events --partitions 12

# Reassign partitions for even distribution
# Generate reassignment JSON, then:
kafka-reassign-partitions.sh --bootstrap-server kafka:9092 --reassignment-json-file reassignment.json --execute

# Verify
kafka-topics.sh --bootstrap-server kafka:9092 --describe --topic verification.events
```

### 5.6 Clearing Redis Cache

```bash
# Connect to Redis
redis-cli -h redis-cluster -c

# Clear tenant-specific cache
EVAL "return redis.call('del', unpack(redis.call('keys', 'tenant:acme:cache:*')))" 0

# Clear rate limit counters (emergency only)
EVAL "return redis.call('del', unpack(redis.call('keys', 'tenant:acme:rate_limit:*')))" 0

# Flush all (nuclear option — avoid in production)
# redis-cli -h redis-cluster FLUSHALL
```

### 5.7 Rotating Secrets

```bash
# 1. Rotate database credentials via Vault
vault write -f database/rotate-role/usora-orchestration

# 2. Verify new credentials work
vault read database/creds/usora-orchestration

# 3. Trigger pod restart to pick up new credentials
kubectl rollout restart deployment orchestration -n usora-orchestration

# 4. Revoke old credentials after grace period (1 hour)
vault lease revoke -prefix database/creds/usora-orchestration/
```

---

## 6. Incident Response

### 6.1 Incident Lifecycle

```
Detection → Triage → Mitigation → Resolution → Postmortem
    │           │           │            │
    ▼           ▼           ▼            ▼
  Alert      Severity   Runbook      Status page
  Trigger    Decision   Execution    Update
```

### 6.2 War Room Protocol (P1/P2)

1. **Acknowledge** alert in PagerDuty within 5 minutes
2. **Create** incident channel: `#inc-YYYY-MM-DD-brief-description`
3. **Page** additional responders if needed
4. **Assign** roles:
   - **Incident Commander**: Coordinates response, communicates externally
   - **Technical Lead**: Drives technical mitigation
   - **Scribe**: Documents timeline and decisions
5. **Update** status page every 15 minutes
6. **Resolve** when service restored and stable for 10 minutes
7. **Schedule** postmortem within 48 hours

### 6.3 Communication Templates

**Initial Acknowledgment (Slack):**
```
:rotating_light: P1 Incident — USORA API Elevated Error Rate
- Started: 2026-07-21 23:45 UTC
- Symptom: >10% 5xx errors across all regions
- Impact: Customer verification flows failing
- Responder: @engineer-oncall
- Status: Investigating
- War room: Zoom link
```

**Status Update (every 15 min):**
```
:yellow_circle: Update — P1 Incident
- Duration: 23 minutes
- Current: Error rate reduced to 3%, root cause identified (Redis cluster partition)
- Action: Rebuilding Redis node, failback in progress
- ETA: 15 minutes to full recovery
```

**Resolution:**
```
:green_circle: Resolved — P1 Incident
- Duration: 42 minutes
- Root cause: Redis cluster network partition caused cache stampede
- Impact: ~2,500 failed verifications, 0 data loss
- Mitigation: Redis cluster rebuilt, circuit breakers prevented cascade
- Postmortem: Scheduled 2026-07-23 14:00 UTC
```

---

## 7. Postmortem Template

### 7.1 Structure

```markdown
# Postmortem: [Incident Title] — YYYY-MM-DD

## Metadata
| Field | Value |
|---|---|
| Incident ID | INC-2026-07-21-001 |
| Severity | P1 |
| Duration | 42 minutes |
| Detection | Automated alert (PagerDuty) |
| Responders | @alice, @bob, @charlie |

## Summary
One-sentence description of what happened and impact.

## Timeline (UTC)
| Time | Event |
|---|---|
| 23:42 | Alert fired: GatewayHighErrorRate |
| 23:43 | Engineer acknowledged alert |
| 23:45 | Incident declared, war room opened |
| 23:50 | Root cause identified: Redis cluster partition |
| 00:15 | Redis node rebuilt, cluster healthy |
| 00:24 | Error rate returned to baseline |
| 00:25 | Incident resolved |

## Root Cause
Detailed technical explanation of what caused the incident.

## Impact
- Failed verifications: 2,500
- Affected tenants: 45
- Revenue impact: $0 (no completed transactions lost)
- Data loss: None

## What Went Well
- Automated detection caught issue within 1 minute
- Circuit breakers prevented cascade to compute layer
- Runbook was accurate and up-to-date

## What Went Wrong
- Redis cluster lacked sufficient monitoring
- Failover took longer than expected (15 min vs 5 min target)
- Status page update delayed by 10 minutes

## Action Items
| ID | Action | Owner | Due Date | Priority |
|---|---|---|---|---|
| AI-1 | Add Redis cluster partition alert | @alice | 2026-07-28 | P1 |
| AI-2 | Automate Redis failover procedure | @bob | 2026-08-04 | P1 |
| AI-3 | Update status page SLO to <5 min | @charlie | 2026-07-25 | P2 |

## Lessons Learned
Key insights for future prevention.
```

---

## 8. Backup and Recovery

### 8.1 PostgreSQL Backup Verification

```bash
# Daily: Verify backup integrity
aws s3 ls s3://usora-backups/postgres/daily/ | tail -5

# Weekly: Test restore to staging
# 1. Provision temporary instance
# 2. Download latest backup
aws s3 cp s3://usora-backups/postgres/daily/postgres-20260721.dump.gz /tmp/
# 3. Restore
gunzip -c /tmp/postgres-20260721.dump.gz | psql -U postgres -d usora_staging
# 4. Run consistency checks
psql -U postgres -d usora_staging -c "SELECT count(*) FROM verifications;"
# 5. Clean up
kubectl delete pod postgres-restore-test
```

### 8.2 Kafka Topic Recovery

```bash
# Check topic replication
kafka-topics.sh --bootstrap-server kafka:9092 --describe --topic audit.logs

# If under-replicated:
# 1. Identify offline partition
kafka-topics.sh --bootstrap-server kafka:9092 --describe --topic audit.logs | grep "UnderReplicated"

# 2. Restart failed broker
kubectl delete pod kafka-2 -n usora-data

# 3. Verify recovery
watch -n 5 'kafka-topics.sh --bootstrap-server kafka:9092 --describe --topic audit.logs'
```

### 8.3 S3 Document Recovery

```bash
# List deleted objects (versioning enabled)
aws s3api list-object-versions --bucket usora-documents-acme --prefix ver_7f8a9b2c/

# Restore deleted object
aws s3api copy-object \
  --copy-source usora-documents-acme/doc_abc123?versionId=xyz789 \
  --bucket usora-documents-acme \
  --key doc_abc123 \
  --server-side-encryption AES256
```

---

## 9. Security Incidents

### 9.1 Suspected Data Breach

1. **Isolate** affected tenant's namespace
   ```bash
   kubectl label namespace tenant-acme isolation=quarantine
   kubectl apply -f network-policies/deny-all.yaml -n tenant-acme
   ```

2. **Preserve** logs and evidence
   ```bash
   kubectl logs --all-containers -n tenant-acme --since=24h > /evidence/tenant-acme-logs-$(date +%s).txt
   ```

3. **Notify** Security Team and Legal
4. **Assess** scope: which data, which tenants, which time window
5. **Contain**: Rotate credentials, revoke sessions
6. **Eradicate**: Patch vulnerability, remove attacker access
7. **Recover**: Restore from clean backups if needed
8. **Document**: Full timeline for legal/compliance

### 9.2 DDoS Response

```bash
# 1. Activate CloudFlare Under Attack mode
curl -X PATCH "https://api.cloudflare.com/client/v4/zones/{zone_id}/settings/security_level" \
  -H "Authorization: Bearer {token}" \
  -d '{"value":"under_attack"}'

# 2. Enable rate limiting rules
curl -X POST "https://api.cloudflare.com/client/v4/zones/{zone_id}/rate_limits" \
  -H "Authorization: Bearer {token}" \
  -d '{"threshold":100,"period":60,"match":{"request":{"url":"*.usora.io/*"}}}'

# 3. Scale gateway aggressively
kubectl scale deployment gateway --replicas=50 -n usora-gateway

# 4. Enable challenge for suspicious IPs
# Via CloudFlare dashboard or API

# 5. Monitor and adjust
# Revert when attack subsides
```

---

## 10. Contact Directory

| Role | Name | PagerDuty | Slack | Phone |
|---|---|---|---|---|
| Platform Lead | Alice Chen | @alice.platform | @alice | +1-555-0101 |
| Backend Lead | Bob Martinez | @bob.backend | @bob | +1-555-0102 |
| ML Lead | Charlie Park | @charlie.ml | @charlie | +1-555-0103 |
| Data Lead | Diana Ross | @diana.data | @diana | +1-555-0104 |
| Security Lead | Evan Wright | @evan.security | @evan | +1-555-0105 |
| Incident Commander | On-call rotation | @incident.cmdr | #usora-incidents | Bridge line |
| Executive Escalation | CTO Office | — | @cto | +1-555-0199 |

---

## 11. Document Information

| Field | Value |
|---|---|
| **Document Version** | 1.0.0 |
| **Last Updated** | 2026-07-21 |
| **Author** | USORA SRE Team |
| **Review Cycle** | Monthly |
| **Classification** | Internal — Confidential |
| **Next Review** | 2026-08-21 |

---

*USORA — Trust at Scale*
