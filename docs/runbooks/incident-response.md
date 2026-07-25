# USORA Incident Response Runbook

> **Version:** 1.0.0  
> **Owner:** SRE Team  
> **Last Updated:** 2026-07-25  
> **Classification:** Internal — Confidential  
> **Next Review:** 2026-08-25  

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Incident Severity Matrix](#2-incident-severity-matrix)
3. [Communication](#3-communication)
4. [Incident Response Workflow](#4-incident-response-workflow)
5. [Specific Scenario Runbooks](#5-specific-scenario-runbooks)
6. [Appendix](#6-appendix)

---

## 1. Introduction

### 1.1 Purpose

The USORA Incident Response Runbook provides standardized, step-by-step procedures for detecting, triaging, containing, investigating, resolving, and learning from incidents. Consistent execution of these procedures minimizes service disruption, data loss, and regulatory exposure.

### 1.2 Scope

This runbook covers all USORA production services across all regions (us-east-1, eu-west-1, ap-southeast-1). It applies to:
- Platform infrastructure outages and degradations
- Security incidents (data breaches, unauthorized access)
- Data integrity issues (corruption, loss, inconsistency)
- Performance degradations affecting SLO attainment
- Compliance and regulatory incidents (GDPR breach, audit failure)

### 1.3 Definitions

| Term | Definition |
|------|------------|
| **Incident** | An unplanned event that causes or may cause service degradation, data loss, or security compromise |
| **SEV** | Severity level (1-4) indicating business impact |
| **IC** | Incident Commander — the single decision-maker for an active incident |
| **TL** | Technical Lead — drives technical investigation and mitigation |
| **Scribe** | Documents timeline, decisions, and actions during the incident |
| **MTTR** | Mean Time To Resolve — target metric for incident duration |
| **MTTD** | Mean Time To Detect — target metric for alert-to-acknowledgement |
| **War Room** | Dedicated Slack channel + Zoom bridge for real-time coordination |
| **Blameless Postmortem** | Root cause analysis focused on systemic improvements, not individual blame |

### 1.4 Key Metrics and Targets

| Metric | Target | Measurement |
|--------|--------|-------------|
| MTTD (SEV1) | < 2 minutes | Alert trigger to first acknowledgement |
| MTTD (SEV2) | < 5 minutes | Alert trigger to first acknowledgement |
| MTTR (SEV1) | < 60 minutes | Incident start to resolution |
| MTTR (SEV2) | < 4 hours | Incident start to resolution |
| Postmortem (SEV1) | < 24 hours | Incident resolved to postmortem published |
| Postmortem (SEV2) | < 48 hours | Incident resolved to postmortem published |

---

## 2. Incident Severity Matrix

### 2.1 SEV1 — Critical

**Criteria:** Complete platform outage, data breach confirmed or highly suspected, tenant isolation violation, data loss, regulatory notification required.

| Dimension | Description |
|-----------|-------------|
| **Response Time** | < 15 minutes |
| **PagerDuty** | Pages all on-call (Primary + Secondary + Tertiary) |
| **Notification** | CISO, CTO, VP Engineering immediately; Legal within 1 hour |
| **Examples** | All regions down, confirmed cross-tenant data access, ransomware, database cluster offline, KMS key compromise |
| **Status Page** | Update within 5 minutes of declaration |
| **War Room** | Mandatory — Slack channel + Zoom bridge |
| **Postmortem** | Mandatory — within 24 hours |
| **SLA Credit** | Eligible for 100% SLA credit |

### 2.2 SEV2 — High

**Criteria:** Degraded service for a subset of tenants, ML inference failures, elevated error rates (>5%), significant latency increase (>5x p99), single region down.

| Dimension | Description |
|-----------|-------------|
| **Response Time** | < 30 minutes |
| **PagerDuty** | Pages Primary + Secondary on-call |
| **Notification** | VP Engineering within 30 minutes |
| **Examples** | Single region unavailable, verification pipeline stuck for >15 min, >5% error rate on gateway, Redis cluster degraded, Kafka broker failed |
| **Status Page** | Update within 10 minutes |
| **War Room** | Recommended — Slack channel + optional Zoom |
| **Postmortem** | Mandatory — within 48 hours |
| **SLA Credit** | Eligible for partial SLA credit |

### 2.3 SEV3 — Medium

**Criteria:** Non-critical feature broken, single-tenant issue, elevated error rates (1-5%), monitoring alert false positives, performance degradation not affecting SLOs.

| Dimension | Description |
|-----------|-------------|
| **Response Time** | < 2 hours during business hours |
| **PagerDuty** | Pages Primary on-call |
| **Notification** | Team lead within 1 hour |
| **Examples** | Dashboard slow for single tenant, webhook delivery delayed, reporting queries timing out, ML model version mismatch on staging |
| **Status Page**| Not required (unless customer-facing feature) |
| **War Room** | Optional |
| **Postmortem** | Recommended but not required |
| **SLA Credit**| Not applicable |

### 2.4 SEV4 — Low

**Criteria:** Cosmetic issues, documentation errors, non-urgent bugs, minor monitoring gaps, alert tuning required.

| Dimension | Description |
|-----------|-------------|
| **Response Time** | Next sprint |
| **PagerDuty** | No page — filed as Jira ticket |
| **Notification** | None required |
| **Examples** | Typo in error message, incorrect documentation, missing metric label, alert threshold needs tuning |
| **Status Page**| Not required |
| **War Room** | Not applicable |
| **Postmortem** | Not required |
| **SLA Credit**| Not applicable |

### 2.5 Severity Decision Flowchart

```
Is the entire platform unavailable?
├── YES → SEV1
└── NO  → Is there confirmed data breach or isolation violation?
         ├── YES → SEV1
         └── NO  → Is a single region or major feature degraded?
                  ├── YES → Is >5% of traffic affected?
                  │        ├── YES → SEV1
                  │        └── NO  → SEV2
                  └── NO  → Is it a single-tenant or minor feature issue?
                           ├── YES → SEV3
                           └── NO  → Is it cosmetic or non-urgent?
                                    ├── YES → SEV4
                                    └── NO  → Escalate for re-evaluation
```

**Important:** If uncertain, escalate. It is better to declare a SEV1 and downgrade than to under-react. Severity can be changed at any point during the incident.

---

## 3. Communication

### 3.1 Slack Channels

| Channel | Purpose | Visibility | Retention |
|---------|---------|------------|-----------|
| `#usora-alerts` | Automated alert stream from Prometheus/Alertmanager | All engineering | 90 days |
| `#usora-incident` | Active incident coordination (one thread per incident) | All engineering | 90 days |
| `#usora-incident-{date}-{description}` | Dedicated war room channel (auto-created) | Invite-only responders | Archived after postmortem |
| `#usora-postmortem` | Blameless postmortem discussions and artifacts | All engineering | Indefinite |
| `#usora-status` | Customer-facing status page updates | All company | 90 days |
| `#usora-security` | Security incident coordination | Security Team + invited | Indefinite |

### 3.2 PagerDuty Schedules

| Schedule | Responders | Cover Method |
|----------|------------|-------------|
| **SRE Primary** | SRE Team (rotation: 7 days) | Weekly rotation, 24/7 |
| **SRE Secondary** | SRE Team (rotation: 7 days) | Weekly rotation, 24/7 |
| **SRE Tertiary** | SRE Team Lead | Always on, backs up primary+secondary |
| **Security On-Call** | Security Team | Weekly rotation, 24/7 |
| **Data On-Call** | Data Engineering Team | Weekly rotation, 24/7 |
| **ML On-Call** | ML Platform Team | Weekly rotation, business hours |
| **Engineering Lead** | VP Eng + Tech Leads | Monthly rotation, escalated for SEV1 |

**PagerDuty escalation policy:**

```
Alert Triggered
    │
    ▼
Primary SRE (5 min acknowledgement)
    │
    ├── Acknowledged → Handle incident
    │
    └── Unacknowledged after 5 min
            │
            ▼
        Secondary SRE (5 min)
            │
            ├── Acknowledged → Handle incident
            │
            └── Unacknowledged after 5 min
                    │
                    ▼
                Tertiary SRE (5 min)
                    │
                    ├── Acknowledged → Handle incident
                    │
                    └── Unacknowledged after 5 min
                            │
                            ▼
                        Engineering Lead (manual page)
```

### 3.3 Stakeholder Notification Template

**SEV1 Notification (within 15 minutes):**

```
:rotating_light: SEV1 INCIDENT DECLARED

Title: [Brief description of the issue]
Severity: SEV1
Declared at: [UTC timestamp]
Declared by: @responder

Impact:
- Affected services: [gateway, orchestration, compute, database, etc.]
- Affected tenants: [all / subset / single tenant]
- User impact: [complete outage / data access issue / severe degradation]
- Error rate: [X]%
- Latency increase: [X]x p99

Current status:
- [Investigating / Mitigating / Resolved]
- Actions taken: [brief summary]

Responders:
- Incident Commander: @name (Slack handle)
- Technical Lead: @name
- Scribe: @name
- War room: #inc-YYYY-MM-DD-description

Next update: [UTC timestamp + 15 min]
```

**SEV2 Notification (within 30 minutes):**

```
:warning: SEV2 INCIDENT DECLARED

Title: [Brief description]
Severity: SEV2
Declared at: [UTC timestamp]

Impact:
- Affected services: [list]
- Affected tenants: [list or "all"]
- User impact: [partial degradation]

Responders: @primary @secondary
War room: #inc-YYYY-MM-DD-description

Next update: [UTC timestamp + 30 min]
```

**Resolution Notification:**

```
:white_check_mark: INCIDENT RESOLVED

Title: [Incident title]
Severity: SEV[X]
Duration: [X] minutes
Resolved at: [UTC timestamp]

Resolution summary:
[1-2 sentence description of what was done]

Root cause: [brief description]

Postmortem scheduled: [date/time]

Impact summary:
- Failed requests: [X]
- Affected tenants: [X]
- Data loss: [Yes/No — if yes, quantify]
```

### 3.4 Status Page Updates

For SEV1/SEV2 incidents, the external status page (status.usora.io) must be updated:

```
INVESTIGATING → We are investigating reports of [issue].
IDENTIFIED   → We have identified [root cause] and are working on a fix.
MONITORING   → The fix has been applied. We are monitoring results.
RESOLVED     → The incident has been resolved. [summary]
```

---

## 4. Incident Response Workflow

### 4.1 DETECT

**Alert sources (ordered by reliability):**

| Source | Type | Reliability | Examples |
|--------|------|-------------|----------|
| Prometheus + Alertmanager | Automated | High | Error rate > threshold, latency > SLO |
| PagerDuty | Aggregated | High | Integrated from Alertmanager, CloudWatch, custom |
| Grafana Managed Alerts | Automated | High | Dashboard-level alerting rules |
| CloudWatch Alarms | Automated | Medium | AWS infrastructure alarms |
| Zendesk / Support Tickets | Manual | Low (noisy) | Customer reports of issues |
| Internal team report | Manual | Medium | Engineers noticing anomalies |
| Third-party monitoring | Automated | Medium | Status page monitors, Pingdom, Checkly |

**Alert routing:**

```
Prometheus Alert
    │
    ├── Critical (SEV1/SEV2) → PagerDuty → Phone push notification
    ├── Warning (SEV3) → PagerDuty → Email/Slack notification
    └── Info (SEV4) → Slack #usora-alerts only
```

### 4.2 TRIAGE (within 5 minutes)

**Triage checklist:**

1. **Verify the alert is real**
   - Check the monitoring dashboard for the alerted metric
   - Confirm the alert is not a known false positive (check #usora-alerts history)
   - Check if maintenance windows or deployments are in progress

2. **Classify severity**
   - Use the severity decision flowchart (Section 2.5)
   - If uncertain, escalate to higher severity
   - Record initial severity estimate in incident document

3. **Identify affected services and tenants**
   - Which services show elevated error rates or latency?
   - Are all tenants affected or a subset?
   - Check tenant-aware dashboards for per-tenant breakdown

4. **Assign Incident Commander (IC)**
   - Primary on-call assumes IC role by default
   - IC can hand off to a more experienced responder if needed
   - IC is responsible for coordination, not hands-on debugging

5. **Open Slack war room channel**
   - Create channel: `#inc-YYYY-MM-DD-description`
   - Pin the incident document to the channel
   - Invite relevant responders

6. **Update status page (SEV1/SEV2 only)**
   - Set status to "Investigating"
   - Include brief description of impact

**Triage template:**

```
:mag: Triage Report — [Alert Name]

Verified: [Yes/No — how?]
False positive: [Yes/No]
Severity: [SEV1/2/3/4]

Affected services: [list]
Affected tenants: [all / subset / single]
Impact: [description]

Initial hypothesis: [what might be wrong]
IC: @name
TL: @name
Scribe: @name
War room: #inc-YYYY-MM-DD-description

Additional responders paged: [@name, @name]
```

### 4.3 CONTAIN (within 15 minutes of SEV1 declaration)

The Containment phase prioritizes stopping the bleeding over understanding root cause.

**Containment options (ordered by aggressiveness):**

| Option | Action | Time to Effect | Reversible |
|--------|--------|---------------|------------|
| **1. Circuit breaker** | Enable circuit breakers for affected upstream service | Immediate | Yes — auto-close |
| **2. Rate limiting** | Tighten rate limits at gateway for affected tenants | < 30s | Yes |
| **3. Traffic redirect** | Shift traffic from degraded region to healthy region | < 60s | Yes |
| **4. Scale up** | Increase replica count for overloaded service | < 2 min | Yes |
| **5. Scale down** | Reduce load on failing service (drain connections) | < 30s | Yes |
| **6. Restart service** | Rolling restart of degraded pods | < 3 min | Yes |
| **7. Block IP/subnet** | Add WAF block rule for malicious traffic | < 60s | Yes |
| **8. Quarantine tenant** | Isolate tenant namespace, block all ingress | < 2 min | Yes (manual) |
| **9. Failover** | Promote replica, redirect traffic to DR region | < 5 min | Partial |
| **10. Snapshot/backup** | Capture forensic evidence before recovery actions | < 5 min | N/A |

**Containment decision matrix:**

```
Is there active data loss or unauthorized access?
├── YES → Snapshot evidence → Quarantine → Notify CISO
└── NO  → Is there active user-facing impact?
         ├── YES → Circuit breaker → Redirect traffic → Scale
         └── NO  → Investigate before containing (if safe)
```

**Containment verification:**

After each containment action, verify:
1. Did the action reduce the error rate?
2. Did the action reduce latency?
3. Is there any negative side effect (e.g., increased errors elsewhere)?
4. Document what was done, by whom, and the result

### 4.4 INVESTIGATE

**Investigation data sources:**

| Data Source | Access Method | Use Case |
|-------------|--------------|----------|
| Prometheus metrics | Grafana dashboard / PromQL query | Error rates, latency, throughput, resource usage |
| Loki logs | Grafana Explore / LogQL query | Application errors, stack traces, audit logs |
| Tempo traces | Grafana Explore / TraceQL query | Request-level latency breakdown, service dependencies |
| Kubernetes events | `kubectl get events -n <ns>` | Pod crashes, OOMKilled, liveness probe failures |
| Container logs | `kubectl logs -n <ns> <pod>` | Detailed application error messages |
| AWS CloudWatch | AWS Console | Infrastructure-level metrics, RDS/ElastiCache/MSK metrics |
| PagerDuty timeline | PagerDuty console | Alert timeline, acknowledgement delays |
| Deployment history | ArgoCD / `kubectl rollout history` | Recent changes that may have caused the incident |

**Investigation workflow:**

```
1. CHECK RECENT CHANGES
   ├── Deployments (ArgoCD): What changed in the last 1 hour?
   ├── Config changes (Vault, ConfigMap): What changed in the last 24 hours?
   └── Infrastructure changes (Terraform): What changed in the last 24 hours?

2. CHECK DEPENDENCIES
   ├── Is the upstream service healthy? (check its health endpoint)
   ├── Is the downstream service healthy? (check database, Redis, Kafka)
   └── Are there any cascading failures?

3. CHECK RESOURCE UTILIZATION
   ├── CPU: Are any pods CPU-throttled?
   ├── Memory: Are any pods near OOM limit?
   ├── Disk: Are any volumes full?
   └── Network: Are there packet drops or connection timeouts?

4. CHECK DISTRIBUTED TRACES
   ├── Filter traces by: service, status code, duration > p99
   ├── Identify which service/operation is the bottleneck
   └── Check for error spans or exception events

5. CORRELATE ACROSS SOURCES
   ├── Do logs show errors at the same time as the metric spike?
   ├── Do traces show the same error pattern?
   └── Do recent changes correlate with the incident start time?
```

**Evidence preservation checklist (for security incidents or postmortem):**

- [ ] Capture pod logs for affected services: `kubectl logs --since=2h ... > evidence/logs.txt`
- [ ] Capture Kubernetes events: `kubectl get events --sort-by='.lastTimestamp' > evidence/events.txt`
- [ ] Export Grafana dashboard snapshot (PDF/PNG)
- [ ] Export Prometheus query results (JSON/CSV)
- [ ] Capture network flow logs (if available)
- [ ] Take screenshots of alerts and dashboards
- [ ] Record all commands executed during investigation
- [ ] Document timeline of all actions taken

### 4.5 RESOLVE

**Resolution checklist:**

1. **Apply the fix**
   - Deploy the fix through standard CI/CD pipeline (ArgoCD)
   - For emergency fixes, use the expedited deployment process (Section 6.2)
   - Ensure the fix is reviewed by at least one other engineer (even if post-facto)

2. **Run validation tests**
   - Verify the error rate returns to baseline
   - Verify latency returns to SLO targets
   - Run smoke test suite: `curl` health endpoints, test critical user journeys
   - Verify downstream services are operating normally

3. **Monitor for stability**
   - Watch dashboards for 10 minutes after fix is applied
   - Verify no secondary issues or cascading failures
   - Check that all circuit breakers have closed
   - Verify tenant isolation is intact (cross-tenant queries return empty)

4. **Document timeline**
   - Record all actions taken with timestamps
   - Note what was tried and didn't work (prevents repeat)
   - Update the incident document with resolution details

5. **Declare resolution**
   - Update status page to "Resolved"
   - Post resolution message to #usora-incident
   - Close PagerDuty incident
   - Announce in company-wide Slack channel (for SEV1)

### 4.6 POST-MORTEM (within 48 hours for SEV1/SEV2)

**Postmortem process:**

1. **Schedule meeting** within 48 hours of resolution
   - Invite all responders + relevant team leads
   - Block 60-90 minutes on calendar
   - Send pre-read materials 24 hours in advance

2. **Prepare the postmortem document**
   - Fill in the timeline from scribe notes
   - Gather metrics (error count, affected users, duration)
   - Document root cause analysis (5 Whys technique)
   - Draft action items with owners and due dates

3. **Conduct the postmortem meeting**
   - Review the timeline (no interruptions until timeline is complete)
   - Discuss root cause (focus on system, not individual)
   - Identify contributing factors
   - Generate action items
   - Assign owners and due dates

4. **Publish the postmortem**
   - Post to #usora-postmortem channel
   - Ensure all action items are tracked in Jira
   - Add any new runbook entries if gaps were found
   - Schedule follow-up to verify action items are completed

**Postmortem template:**

```markdown
# Postmortem: [Incident Title] — YYYY-MM-DD

## Metadata
| Field | Value |
|-------|-------|
| Incident ID | INC-YYYY-MM-DD-NNN |
| Severity | SEV[X] |
| Duration | [X] minutes |
| Detection method | [Alert / Manual report / Customer complaint] |
| Responders | @name, @name, @name |

## Summary
[1-2 paragraph description of what happened, what was impacted, and how it was resolved]

## Timeline (UTC)
| Time | Event |
|------|-------|
| HH:MM | Alert triggered: [alert name] |
| HH:MM | Engineer acknowledged |
| HH:MM | SEV[X] declared — war room opened |
| HH:MM | Containment action: [action] |
| HH:MM | Root cause identified |
| HH:MM | Fix deployed |
| HH:MM | Monitoring confirms recovery |
| HH:MM | Incident resolved |

## Root Cause
[Detailed technical explanation of why the incident occurred. Use 5 Whys technique.]

## Contributing Factors
- Factor 1: [description]
- Factor 2: [description]
- Factor 3: [description]

## Impact
- Failed requests: [N]
- Affected tenants: [N]
- Affected users (est.): [N]
- p99 latency during incident: [X]ms (baseline: [X]ms)
- Data loss: [Yes/No — quantify if yes]
- Revenue impact: $[estimated]

## What Went Well
- [ ] [Observation 1]
- [ ] [Observation 2]
- [ ] [Observation 3]

## What Went Wrong
- [ ] [Observation 1]
- [ ] [Observation 2]
- [ ] [Observation 3]

## Action Items
| ID | Action | Owner | Due Date | Priority | Jira |
|----|--------|-------|----------|----------|------|
| AI-1 | [Action description] | @owner | YYYY-MM-DD | P1 | [link] |
| AI-2 | [Action description] | @owner | YYYY-MM-DD | P1 | [link] |
| AI-3 | [Action description] | @owner | YYYY-MM-DD | P2 | [link] |
| AI-4 | [Action description] | @owner | YYYY-MM-DD | P2 | [link] |

## Runbook Updates Required
- [ ] Update [runbook name] with [specific change]
- [ ] Create new runbook for [scenario]

## Lessons Learned
[Key takeaways for the team and organization.]

---
*Blameless postmortem — focus on system improvements, not individual blame.*
```

---

## 5. Specific Scenario Runbooks

### 5.1 KYC Verification Pipeline Slow or Failing

**Severity:** SEV2 (default), SEV1 if complete pipeline failure

**Symptoms:**
- Customers reporting "stuck" verifications
- `usora_verification_duration_seconds` > p99 SLO (10s complex)
- Kafka consumer lag increasing for compute topics
- Error rate on `verification.events` topic increasing

**Step-by-step instructions:**

```bash
# 1. CHECK COMPUTE WORKER HEALTH
# ─────────────────────────────────
kubectl get pods -n usora-compute -o wide
kubectl top pods -n usora-compute
# Expected: All pods READY, CPU < 80%

# 2. CHECK KAFKA LAG
# ─────────────────────────────────
kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
  --describe --group compute-document-workers

kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
  --describe --group compute-biometric-workers

kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
  --describe --group compute-risk-workers

# Expected: LAG < 1000. If > 10000, workers are falling behind.

# 3. CHECK MODEL INFERENCE LATENCY
# ─────────────────────────────────
# PromQL query in Grafana:
#   histogram_quantile(0.99, rate(usora_compute_inference_duration_seconds_bucket[5m]))
# Expected: < 2s for document, < 500ms for biometric, < 100ms for risk

# 4. CHECK WORKER LOGS FOR ERRORS
# ─────────────────────────────────
kubectl logs -n usora-compute -l app=compute-document --tail=200 | grep -i "error\|exception\|panic"
kubectl logs -n usora-compute -l app=compute-biometric --tail=200 | grep -i "error\|exception\|panic"

# 5. CHECK DEAD LETTER QUEUE
# ─────────────────────────────────
kafka-console-consumer.sh --bootstrap-server kafka:9092 \
  --topic dead-letter-queue --from-beginning --max-messages 10

# Expected: Empty or near-empty DLQ

# 6. CHECK UPSTREAM DEPENDENCIES
# ─────────────────────────────────
# Document analysis depends on Tesseract OCR + ONNX model
kubectl logs -n usora-compute -l app=compute-document --tail=100 | grep -i "tesseract\|ocr\|model"

# Biometric matching depends on FAISS index
kubectl exec -it compute-biometric-xxx -n usora-compute -- ls -la /data/faiss/index/

# Risk scoring depends on feature store
curl -s http://compute-risk-metrics:9090/metrics | grep usora_compute_feature_store_latency
```

**Remediation actions:**

| Scenario | Action |
|----------|--------|
| Kafka lag > 10000 | Scale compute workers: `kubectl scale deployment compute-document --replicas=30 -n usora-compute` |
| Model inference latency > 5s p99 | Rollback to previous model version via ArgoCD |
| Worker OOMKilled | Increase memory limits: `kubectl edit deployment compute-document -n usora-compute` |
| Tesseract/OCR failing | Check Tesseract installation, restart pod |
| FAISS index corrupted | Restore from S3 backup: `aws s3 cp s3://usora-models/faiss/latest/ /data/faiss/index/ --recursive` |
| Feature store unreachable | Check feature store service health, restart if needed |
| Pipeline stuck on specific document | Identify poison pill in DLQ, skip and replay |
| All workers healthy but pipeline still slow | Check orchestrator: restart `orchestration` deployment |

**Escalation:** If pipeline not recovering within 20 minutes, page ML Team Lead.

---

### 5.2 Database Primary Failure

**Severity:** SEV1 (primary failure), SEV2 (replica failure without primary impact)

**Symptoms:**
- Connection errors to PostgreSQL primary
- `pg_stat_replication` shows primary as unreachable
- Application errors: `org.postgresql.util.PSQLException: Connection refused`
- Failed verifications due to DB write failures

**Step-by-step instructions:**

```bash
# 1. VERIFY REPLICATION LAG (if primary is still partially responsive)
# ─────────────────────────────────
kubectl exec -it postgres-primary-0 -n usora-data -- psql -U postgres \
  -c "SELECT client_addr, state, sent_lsn, write_lsn, flush_lsn, replay_lsn, \
  pg_size_pretty(pg_wal_lsn_diff(sent_lsn, replay_lsn)) as lag \
  FROM pg_stat_replication;"

# 2. CHECK PRIMARY STATUS
# ─────────────────────────────────
kubectl exec -it postgres-primary-0 -n usora-data -- pg_isready
# If timeout or connection refused, primary is down.

# 3. VERIFY REPLICA STATUS (to promote)
# ─────────────────────────────────
kubectl exec -it postgres-replica-0 -n usora-data -- pg_isready
kubectl exec -it postgres-replica-0 -n usora-data -- psql -U postgres \
  -c "SELECT pg_last_wal_receive_lsn(), pg_last_wal_replay_lsn(), \
  pg_last_xact_replay_timestamp();"

# 4. CHECK WAL ARCHIVE (if recovery is needed)
# ─────────────────────────────────
aws s3 ls s3://usora-backups/postgres/wal-archive/ --human-readable | tail -20

# 5. INITIATE FAILOVER TO REPLICA
# ─────────────────────────────────
# Promote replica to primary:
kubectl exec -it postgres-replica-0 -n usora-data -- pg_ctl promote \
  -D /var/lib/postgresql/data

# Verify promoted:
kubectl exec -it postgres-replica-0 -n usora-data -- psql -U postgres \
  -c "SELECT pg_is_in_recovery();"
# Expected: false

# 6. UPDATE SERVICE ENDPOINTS
# ─────────────────────────────────
# Patch the primary service to point to the promoted replica:
kubectl patch service postgres-primary -n usora-data -p \
  '{"spec":{"selector":{"statefulset.kubernetes.io/pod-name":"postgres-replica-0"}}}'

# 7. VERIFY APPLICATION CONNECTIVITY
# ─────────────────────────────────
# Test from orchestration pod:
kubectl exec -it deployment/orchestration-xxx -n usora-orchestration -- \
  psql "$DATABASE_URL" -c "SELECT 1 AS test;"

# Test from compute pod (if direct DB access):
kubectl exec -it deployment/compute-document-xxx -n usora-compute -- \
  psql "$DATABASE_URL" -c "SELECT 1 AS test;"

# 8. UPDATE CONNECTION STRINGS (if using static config)
# ─────────────────────────────────
# If using Vault dynamic credentials:
vault write database/roles/usora-app \
  db_name=usora-prod \
  creation_statements="..." \
  default_ttl=1h

# Trigger pod restart to pick up new config:
kubectl rollout restart deployment orchestration -n usora-orchestration
kubectl rollout restart deployment gateway -n usora-gateway

# 9. REBUILD OLD PRIMARY AS REPLICA (after recovery)
# ─────────────────────────────────
# Once the old primary is back online:
kubectl exec -it postgres-primary-0 -n usora-data -- \
  rm -rf /var/lib/postgresql/data/*

kubectl exec -it postgres-primary-0 -n usora-data -- \
  pg_basebackup -h postgres-replica-0 -D /var/lib/postgresql/data \
  -U replicator -P -v --wal-method=stream

# 10. VERIFY REPLICATION IS WORKING
# ─────────────────────────────────
kubectl exec -it postgres-replica-0 -n usora-data -- psql -U postgres \
  -c "SELECT client_addr, state FROM pg_stat_replication;"
# Expected: old primary showing as streaming replica
```

**Escalation:** If failover takes > 10 minutes or data integrity is in question, page Data Team Lead + CTO.

---

### 5.3 Redis Cluster Degraded

**Severity:** SEV2 (degraded), SEV1 (cluster unavailable)

**Symptoms:**
- Rate limiting failures (allowing or blocking incorrectly)
- Session cache misses
- `usora_redis_request_duration_seconds` > 50ms p99
- Circuit breakers opening due to Redis timeouts
- Prometheus alert: `RedisClusterDown` or `RedisMemoryUsage > 80%`

**Step-by-step instructions:**

```bash
# 1. CHECK CLUSTER HEALTH
# ─────────────────────────────────
redis-cli -h redis-cluster -p 6379 -c CLUSTER INFO | grep -E "cluster_state|cluster_slots_assigned|cluster_known_nodes"

# Expected:
# cluster_state:ok
# cluster_slots_assigned:16384
# cluster_known_nodes:6

# 2. CHECK NODE HEALTH
# ─────────────────────────────────
redis-cli -h redis-cluster -p 6379 -c CLUSTER NODES
# Look for nodes in "fail" state or with high ping latency

for node in redis-node-0 redis-node-1 redis-node-2 redis-node-3 redis-node-4 redis-node-5; do
  kubectl exec -it $node -n usora-data -- redis-cli PING
done
# Expected: PONG from all nodes

# 3. CHECK MEMORY USAGE PER NODE
# ─────────────────────────────────
for node in 0 1 2 3 4 5; do
  echo "=== redis-node-$node ==="
  kubectl exec -it redis-node-$node -n usora-data -- redis-cli INFO memory \
    | grep -E "used_memory_human|used_memory_peak_human|maxmemory_human|mem_fragmentation_ratio"
done
# Expected: used_memory < 80% of maxmemory

# 4. CHECK FOR HOT KEYS
# ─────────────────────────────────
# Monitor keyspace events:
kubectl exec -it redis-node-0 -n usora-data -- redis-cli MONITOR | head -100
# Look for frequently accessed keys (indicates hot key)

# Use redis-cli --hotkeys (Redis 7+):
kubectl exec -it redis-node-0 -n usora-data -- redis-cli --hotkeys

# 5. CHECK SLOT DISTRIBUTION
# ─────────────────────────────────
redis-cli -h redis-cluster -p 6379 -c CLUSTER SLOTS
# Expected: Even distribution across nodes
# Each node should have approximately 2730 slots (16384 / 6)

# 6. CHECK FOR LATENCY ISSUES
# ─────────────────────────────────
kubectl exec -it redis-node-0 -n usora-data -- redis-cli --latency -h localhost -p 6379
# Expected: < 1ms average latency
```

**Remediation actions:**

| Scenario | Action |
|----------|--------|
| Cluster state is `fail` | Check which nodes are down. Restart failed nodes: `kubectl delete pod <node> -n usora-data` |
| Memory usage > 80% on a node | Increase maxmemory in Redis config, or scale cluster |
| Hot key detected | Identified hot key pattern (e.g., `tenant:acme123:session:*`). Consider local cache or sharding |
| High latency (> 10ms) | Check for network issues, large value sizes, slow commands |
| Node unreachable | Attempt failover: `kubectl exec -it <healthy-node> -- redis-cli CLUSTER FAILOVER` |
| Fragmentation ratio > 2.0 | Schedule maintenance window for memory defragmentation |

**Scaling the cluster:**

```bash
# Add new node:
kubectl scale statefulset redis-cluster --replicas=8 -n usora-data

# Rebalance slots:
kubectl exec -it redis-cluster-0 -n usora-data -- \
  redis-cli --cluster rebalance redis-cluster:6379 \
  --cluster-use-empty-masters

# Verify new slot distribution:
redis-cli -h redis-cluster -p 6379 -c CLUSTER SLOTS | wc -l
```

**Escalation:** If cluster health not restored within 15 minutes, page Data Team Lead.

---

### 5.4 Kafka Broker Failure

**Severity:** SEV2 (single broker), SEV1 (quorum loss or controller failure)

**Symptoms:**
- Consumer lag increasing across multiple topics
- Producer timeouts: `org.apache.kafka.common.errors.TimeoutException`
- Under-replicated partitions
- Prometheus alert: `KafkaUnderReplicatedPartitions > 0`

**Step-by-step instructions:**

```bash
# 1. VERIFY CONTROLLER STATUS
# ─────────────────────────────────
kubectl exec -it kafka-0 -n usora-data -- kafka-broker-api-versions.sh \
  --bootstrap-server kafka:9092 | head -5

kubectl exec -it kafka-0 -n usora-data -- \
  zookeeper-shell.sh zookeeper:2181 get /controller | grep brokerid

# 2. CHECK CLUSTER HEALTH
# ─────────────────────────────────
kubectl exec -it kafka-0 -n usora-data -- kafka-broker-api-versions.sh \
  --bootstrap-server kafka:9092

# List all brokers:
kubectl exec -it kafka-0 -n usora-data -- \
  zookeeper-shell.sh zookeeper:2181 ls /brokers/ids

# Expected: 3 broker IDs (0, 1, 2)

# 3. CHECK UNDER-REPLICATED PARTITIONS
# ─────────────────────────────────
kubectl exec -it kafka-0 -n usora-data -- kafka-topics.sh \
  --bootstrap-server kafka:9092 --describe --under-replicated-partitions

# 4. CHECK PARTITION LEADERSHIP
# ─────────────────────────────────
kubectl exec -it kafka-0 -n usora-data -- kafka-topics.sh \
  --bootstrap-server kafka:9092 --describe --topics-with-overrides

# 5. CHECK ISR (In-Sync Replica) COUNT
# ─────────────────────────────────
kubectl exec -it kafka-0 -n usora-data -- kafka-topics.sh \
  --bootstrap-server kafka:9092 --describe --unavailable-partitions

# 6. CHECK PRODUCER AND CONSUMER OFFSETS
# ─────────────────────────────────
kubectl exec -it kafka-0 -n usora-data -- kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 --describe --group compute-document-workers

kubectl exec -it kafka-0 -n usora-data -- kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 --describe --group compute-biometric-workers

kubectl exec -it kafka-0 -n usora-data -- kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 --describe --group orchestration-workers

# 7. CHECK BROKER LOGS
# ─────────────────────────────────
for broker in 0 1 2; do
  echo "=== kafka-$broker ==="
  kubectl logs kafka-$broker -n usora-data --tail=50 | grep -i "error\|exception\|failed\|leader"
done
```

**Remediation actions:**

| Scenario | Action |
|----------|--------|
| Single broker down | Restart failed broker: `kubectl delete pod kafka-<id> -n usora-data` |
| Controller failure | Force controller election: `kubectl exec kafka-0 -n usora-data -- kafka-leader-election.sh --bootstrap-server kafka:9092 --election-type preferred --all-topic-partitions` |
| Under-replicated partitions | Wait for broker recovery. If persistent, reassign partitions: `kafka-reassign-partitions.sh --bootstrap-server kafka:9092 --reassignment-json-file reassign.json --execute` |
| Partition leader unavailable | Trigger preferred leader election: `kafka-leader-election.sh --bootstrap-server kafka:9092 --election-type preferred --topic verification.events` |
| Disk full on broker | Clean up log segments (temporary), then increase disk size: `kubectl edit pvc kafka-data-<id> -n usora-data` |
| Consumer lag > 100K | Scale consumers: `kubectl scale deployment compute-document --replicas=20 -n usora-compute` |

**Kafka partition reassignment example:**

```json
// reassign.json
{
  "version": 1,
  "partitions": [
    {
      "topic": "verification.events",
      "partition": 0,
      "replicas": [0, 1, 2],
      "log_dirs": ["any", "any", "any"]
    },
    {
      "topic": "verification.events",
      "partition": 1,
      "replicas": [1, 2, 0]
    }
  ]
}
```

**Escalation:** If cluster health not restored within 20 minutes, page Data Team Lead.

---

### 5.5 API Gateway Latency Spike

**Severity:** SEV2 (p99 > 100ms), SEV1 (p99 > 500ms or complete gateway failure)

**Symptoms:**
- `usora_gateway_request_duration_seconds` p99 > 100ms
- Customer complaints about slow API responses
- Timeout errors from API clients
- Nginx ingress 502/504 errors

**Step-by-step instructions:**

```bash
# 1. CHECK RATE LIMITING CONFIG
# ─────────────────────────────────
# Check Redis-based rate limiter performance:
kubectl logs -n usora-gateway -l app=gateway --tail=200 | grep -i "rate.limit\|throttle\|429"

# Check Prometheus rate limit metrics:
# PromQL: rate(usora_gateway_rate_limit_checks_total[5m])
# PromQL: histogram_quantile(0.99, rate(usora_gateway_rate_limit_duration_seconds_bucket[5m]))

# Expected: rate limit checks < 1ms p99

# 2. CHECK UPSTREAM HEALTH
# ─────────────────────────────────
kubectl get pods -n usora-orchestration -o wide
kubectl get pods -n usora-compute -o wide

# Check upstream latency from gateway:
# PromQL: histogram_quantile(0.99, rate(usora_gateway_upstream_duration_seconds_bucket[5m]))
# Expected: < 50ms p99

# 3. CHECK TLS HANDSHAKE TIMES
# ─────────────────────────────────
# PromQL: histogram_quantile(0.99, rate(usora_gateway_tls_handshake_seconds_bucket[5m]))
# Expected: < 10ms p99

# If TLS handshake is slow, check certificate validity:
kubectl get certificate gateway-tls -n usora-gateway -o yaml | grep -E "notAfter|notBefore"

# 4. CHECK GATEWAY RESOURCE USAGE
# ─────────────────────────────────
kubectl top pods -n usora-gateway
# Expected: CPU < 70%, Memory < 80%

# 5. CHECK GATEWAY POD LOGS FOR SLOW REQUESTS
# ─────────────────────────────────
kubectl logs -n usora-gateway -l app=gateway --tail=500 | grep -i "slow\|stall\|timeout\|block"

# 6. CHECK FOR GC PAUSES (Java orchestration — cascading latency)
# ─────────────────────────────────
kubectl logs -n usora-orchestration -l app=orchestration --tail=200 | grep -i "gc\|pause\|stop-the-world"
```

**Remediation actions:**

| Scenario | Action |
|----------|--------|
| Rate limiting slow | Check Redis latency (Section 5.3). Consider reducing rate limit check frequency |
| Upstream (orchestration) slow | Check orchestration metrics. Restart if memory-leaking: `kubectl rollout restart deployment orchestration -n usora-orchestration` |
| TLS handshake > 50ms | Check for certificate chain issues. Ensure OCSP stapling is enabled |
| Gateway CPU > 80% | Scale horizontally: `kubectl scale deployment gateway --replicas=15 -n usora-gateway` |
| Connection pool exhausted | Increase max connections in gateway config. Check for connection leaks |
| WAF/rate limiting CPU overhead | Temporarily reduce WAF ruleset. Consider moving rate limiting to Redis Lua scripts |

**Escalation:** If latency not reduced within 15 minutes, page Engineering Lead.

---

### 5.6 Tenant Isolation Breach (SECURITY INCIDENT)

**Severity:** SEV1 — Immediate CISO notification required

**Symptoms:**
- Alert: `usora_isolation_violation_total > 0`
- Audit log showing cross-tenant data access
- Customer report of seeing another tenant's data
- Unexpected cross-schema database queries
- S3 cross-prefix access detected

**Step-by-step instructions:**

```bash
# ⚠️ STOP. DO NOT PROCEED WITHOUT CISO AUTHORIZATION FOR ACTIONS THAT DESTROY EVIDENCE.

# 1. SEAL AFFECTED SYSTEMS (do not destroy evidence)
# ────────────────────────────────────────────────────
# Quarantine the tenant namespace:
kubectl label namespace tenant-{tid} isolation=quarantine
kubectl apply -f - <<EOF
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: quarantine-deny-all
  namespace: tenant-{tid}
spec:
  podSelector: {}
  policyTypes:
  - Ingress
  - Egress
EOF

# 2. PRESERVE EVIDENCE
# ─────────────────────────────────
# Capture pod logs before any restarts:
kubectl logs --all-containers -n tenant-{tid} --since=24h > /evidence/quarantine-logs-$(date +%s).txt

# Capture Kubernetes events:
kubectl get events -n tenant-{tid} --sort-by='.lastTimestamp' > /evidence/events-$(date +%s).txt

# Capture database audit logs:
kubectl exec postgres-primary-0 -n usora-data -- psql -U postgres \
  -c "SELECT * FROM pg_stat_activity WHERE query LIKE '%tenant_%' \g /evidence/pg-queries-$(date +%s).txt"

# Capture network flow logs:
# (varies by CNI — example for Cilium)
kubectl exec -n kube-system -l app.kubernetes.io/name=cilium -- \
  cilium monitor -v --related-to pod/$(kubectl get pods -n tenant-{tid} -o name) \
  > /evidence/network-flows-$(date +%s).txt &

# 3. NOTIFY CISO IMMEDIATELY
# ─────────────────────────────────
# Call: [+1-555-SECURITY]
# Page: @evan.security via PagerDuty
# Message:
# "SEV1 Isolation Breach — tenant {tid} potentially accessed tenant {tid2} data.
#  Systems quarantined. Evidence preserved. Awaiting instructions."

# 4. ENGAGE FORENSICS TEAM
# ─────────────────────────────────
# Contact external forensics firm (pre-arranged retainer):
# - Call: [+1-555-FORENSICS]
# - Provide: incident ID, affected tenants, time window, evidence location

# 5. DETERMINE SCOPE
# ─────────────────────────────────
# Work with forensics team to determine:
# - Which tenants' data was accessed?
# - What data was accessed (PII, documents, credentials)?
# - Time window of the breach
# - Method of access (API, DB, storage, network?)
# - Was data exfiltrated?

# 6. NOTIFY AFFECTED TENANTS
# ─────────────────────────────────
# Legal team handles notification. Template:
# "We have identified a security incident affecting your tenant data.
#  [Description of scope]. We are taking [actions]. For assistance: [contact]."
# Timeline: Within 72 hours of confirmation (per GDPR)

# 7. CONTAIN AND ERADICATE
# ─────────────────────────────────
# After forensics evidence is collected:
# - Rotate all credentials for affected tenants
# - Revoke all active sessions
# - Apply security patches
# - Review and update access policies

# 8. RECOVER
# ─────────────────────────────────
# Restore tenant data from clean backup:
# (Only after confirming backup is not compromised)
kubectl exec postgres-primary-0 -n usora-data -- \
  pg_restore -d postgresql://... /backups/clean/tenant-{tid}.dump \
  --schema=tenant_{tid}

# 9. POST-INCIDENT ACTIONS
# ─────────────────────────────────
# - Full postmortem within 24 hours
# - Regulatory notification (GDPR: 72 hours)
# - Customer notification
# - Security controls review and update
# - New runbook entries for isolation-specific scenarios
```

**Escalation:** CISO is the Incident Commander for isolation breaches. All decisions go through CISO + Legal.

---

### 5.7 ML Model Drift

**Severity:** SEV3 (default), SEV2 if verification accuracy drops significantly

**Symptoms:**
- `usora_compute_accuracy_score` declining over 7-day window
- Increased manual review rate for a document type
- Customer complaints about false rejections
- Feature distribution shift detected by monitoring

**Step-by-step instructions:**

```bash
# 1. VERIFY FEATURE DISTRIBUTION
# ─────────────────────────────────
# Check feature store metrics:
curl -s http://compute-risk-metrics:9090/metrics | grep usora_compute_feature_distribution

# PromQL query for feature drift:
#   usora_compute_feature_distribution{feature="face_embedding_norm"} - on(version) 
#   usora_compute_feature_distribution{feature="face_embedding_norm", version="baseline"}

# 2. COMPARE TO BASELINE
# ─────────────────────────────────
# Check model version currently deployed:
kubectl get configmap compute-models -n usora-compute -o yaml | grep version

# Compare to previous known-good version:
kubectl get configmap compute-models -n usora-compute -o yaml | grep previous_stable_version

# 3. ALERT DATA SCIENCE TEAM
# ─────────────────────────────────
# Notify ML Team via Slack #ml-alerts:
# "Model drift detected for [model name]. Current accuracy: [X]% vs baseline: [Y]%.
#  Feature shift observed: [features]. Approx [N] verifications affected."

# 4. ROLLBACK TO PREVIOUS MODEL VERSION
# ─────────────────────────────────
# If accuracy drop is > 5% or customer-impacting:
kubectl set env deployment compute-document -n usora-compute \
  MODEL_VERSION=v2.3.1  # Rollback to previous version

# Verify rollback:
kubectl rollout status deployment compute-document -n usora-compute
kubectl logs -n usora-compute -l app=compute-document --tail=10 | grep "model loaded"

# 5. MONITOR POST-ROLLBACK
# ─────────────────────────────────
# Check accuracy metrics after 15 minutes:
# PromQL: avg_over_time(usora_compute_accuracy_score[15m])
# Expected: Return to baseline

# 6. NOTIFY DATA SCIENCE TEAM OF ACTIVE MODEL VERSION
# ─────────────────────────────────
# "Rollback to v2.3.1 complete. Accuracy returned to baseline.
#  v2.4.0 model is available at s3://usora-models/document/v2.4.0/ for offline analysis."

```

**Escalation:** If accuracy does not recover after rollback, page ML Team Lead.

---

### 5.8 Multi-Region Failover

**Severity:** SEV1

**Symptoms:**
- Complete region outage (all services down in us-east-1, eu-west-1, or ap-southeast-1)
- CloudWatch alarm: `AWS/Health` → `Service Health Issue` for the region
- All cross-region traffic failing

**Step-by-step instructions:**

```bash
# 1. VERIFY REGION HEALTH
# ─────────────────────────────────
# Check AWS Health Dashboard:
aws health describe-events --region us-east-1 --filter "eventTypeCategory=issue"

# Check all services in region:
for svc in gateway orchestration compute-document compute-biometric compute-risk; do
  kubectl get pods -n usora-${svc} --context=us-east-1 2>/dev/null || echo "$svc: UNREACHABLE"
done

# 2. UPDATE DNS (ROUTE53) — FAILOVER
# ─────────────────────────────────
# Switch Route53 from latency-based to failover routing:
aws route53 change-resource-record-sets \
  --hosted-zone-id ZONE_ID \
  --change-batch '{
    "Changes": [{
      "Action": "UPSERT",
      "ResourceRecordSet": {
        "Name": "api.usora.io",
        "Type": "A",
        "SetIdentifier": "primary",
        "Failover": "PRIMARY",
        "HealthCheckId": "hc-primary",
        "AliasTarget": {
          "HostedZoneId": "DR_ZONE_ID",
          "DNSName": "dr-load-balancer.usora.io",
          "EvaluateTargetHealth": true
        }
      }
    }]
  }'

# 3. REDIRECT TRAFFIC TO DR REGION
# ─────────────────────────────────
# Verify DR region is healthy:
kubectl get pods -n usora-gateway --context=eu-west-1
kubectl get pods -n usora-orchestration --context=eu-west-1

# Verify DR database is accepting writes:
kubectl exec -n usora-data postgres-primary-0 --context=eu-west-1 -- \
  psql -U postgres -c "SELECT 1;"

# 4. VERIFY DR REGION SERVING TRAFFIC
# ─────────────────────────────────
# Test API endpoint:
curl -s -o /dev/null -w "%{http_code}" https://api.usora.io/health
# Expected: 200

# Check error rate in DR region:
# PromQL: sum(rate(usora_gateway_requests_total{region="eu-west-1", status=~"5.."}[5m]))
# Expected: < 1%

# 5. DECLARE INCIDENT
# ─────────────────────────────────
# "Multi-region failover triggered. us-east-1 is degraded.
#  All traffic now served from eu-west-1 DR region.
#  Monitoring for stability."

# 6. MONITOR DR REGION PERFORMANCE
# ─────────────────────────────────
# Check latency impact:
# PromQL: histogram_quantile(0.99, rate(usora_gateway_request_duration_seconds{region="eu-west-1"}[5m]))
# Expected: < 2x baseline (latency from different region)

# Check compute pipeline health:
kubectl get pods -n usora-compute --context=eu-west-1
kafka-consumer-groups.sh --bootstrap-server kafka-eu-west-1:9092 \
  --describe --group compute-document-workers

# 7. POST-INCIDENT
# ─────────────────────────────────
# - Do NOT fail back until primary region is fully healthy
# - Document the failover timeline and lessons learned
# - Update DR runbook based on findings
# - Consider if multi-region active-active config needs adjustment
```

**Escalation:** CTO and VP Engineering are notified immediately upon SEV1 declaration.

---

## 6. Appendix

### 6.1 On-Call Checklist

**Before the shift:**

- [ ] Verify PagerDuty schedule is correct and notifications are enabled
- [ ] Verify Slack is installed and notifications are active
- [ ] Review recent incidents and postmortems from the past week
- [ ] Review any known issues or active maintenance windows
- [ ] Check that runbooks are accessible and up-to-date
- [ ] Verify access to all monitoring tools (Grafana, Loki, Tempo, CloudWatch)
- [ ] Verify access to Kubernetes clusters (contexts for all regions)
- [ ] Verify access to PagerDuty, Slack, and Zoom
- [ ] Check that mobile phone has sufficient battery / signal

**During the shift:**

- [ ] Acknowledge all PagerDuty notifications within 5 minutes
- [ ] Monitor #usora-alerts for non-paged notifications
- [ ] Keep laptop accessible and unlocked
- [ ] Respond to Slack mentions within 10 minutes
- [ ] Document all actions in incident channels
- [ ] Escalate early if uncertain

**After the shift:**

- [ ] Hand off active incidents to next on-call
- [ ] Ensure all runbooks are updated with any new procedures
- [ ] Report any recurring issues to team
- [ ] Verify incident documents are complete and postmortems scheduled

### 6.2 Escalation Contacts

| Role | Name | PagerDuty | Slack | Phone | Office Hours |
|------|------|-----------|-------|-------|-------------|
| SRE Primary | On-call rotation | @sre-primary | @sre-primary | PagerDuty | 24/7 |
| SRE Secondary | On-call rotation | @sre-secondary | @sre-secondary | PagerDuty | 24/7 |
| SRE Tertiary | On-call rotation | @sre-tertiary | @sre-tertiary | PagerDuty | 24/7 |
| Security Lead | Evan Wright | @evan.security | @evan | +1-555-0105 | 24/7 |
| Data Lead | Diana Ross | @diana.data | @diana | +1-555-0104 | 24/7 |
| ML Lead | Charlie Park | @charlie.ml | @charlie | +1-555-0103 | 08:00-20:00 UTC |
| Backend Lead | Bob Martinez | @bob.backend | @bob | +1-555-0102 | 08:00-20:00 UTC |
| Platform Lead | Alice Chen | @alice.platform | @alice | +1-555-0101 | 08:00-20:00 UTC |
| VP Engineering | Jordan Lee | @jordan.eng | @jordan | +1-555-0106 | 07:00-19:00 UTC |
| CTO | Morgan Smith | @cto | @morgan | +1-555-0199 | As needed |
| CISO | Taylor Reed | @ciso | @taylor | +1-555-0198 | 24/7 (security only) |
| Legal | Pat Jordan | — | @legal | +1-555-0197 | Business hours |
| Forensics (external) | CyberSponse Inc. | — | — | +1-555-FORENSICS | 24/7 retainer |

### 6.3 PagerDuty Rotation Schedule

| Week | Primary | Secondary | Tertiary |
|------|---------|-----------|----------|
| Week 1 | Alice Chen | Bob Martinez | Diana Ross |
| Week 2 | Bob Martinez | Diana Ross | Alice Chen |
| Week 3 | Diana Ross | Alice Chen | Bob Martinez |
| Week 4 | Charlie Park | Evan Wright | Alice Chen |
| *(Rotation repeats every 4 weeks)* |

### 6.4 Monitoring Dashboard URLs

| Dashboard | URL | Purpose |
|-----------|-----|---------|
| **USORA Overview** | https://grafana.usora.io/d/usora-overview | Global platform health |
| **Gateway** | https://grafana.usora.io/d/gateway | Request rate, latency, errors, circuit breakers |
| **Orchestration** | https://grafana.usora.io/d/orchestration | Workflow engine, BPMN metrics, Kafka lag |
| **Compute** | https://grafana.usora.io/d/compute | Worker pool, inference latency, model metrics |
| **PostgreSQL** | https://grafana.usora.io/d/postgresql | Connections, replication lag, query performance |
| **Redis** | https://grafana.usora.io/d/redis | Memory, hit rate, latency, cluster health |
| **Kafka** | https://grafana.usora.io/d/kafka | Broker health, consumer lag, partition distribution |
| **S3** | https://grafana.usora.io/d/s3 | Request rate, latency, error codes |
| **Elasticsearch** | https://grafana.usora.io/d/elasticsearch | Index health, query latency, disk usage |
| **ClickHouse** | https://grafana.usora.io/d/clickhouse | Query performance, storage, partitions |
| **Vault** | https://grafana.usora.io/d/vault | Seal status, request rate, latency |
| **Kubernetes** | https://grafana.usora.io/d/kubernetes | Pod health, resource usage, cluster events |
| **Network** | https://grafana.usora.io/d/network | Bandwidth, packet loss, connection tracking |
| **Tenant Isolation** | https://grafana.usora.io/d/tenant-isolation | Isolation violation alerts, per-tenant metrics |
| **Security** | https://grafana.usora.io/d/security | Auth failures, rate limit violations, WAF events |

### 6.5 Postmortem Action Item Tracking Template

| ID | Action | Owner | Due Date | Priority | Status | Jira |
|----|--------|-------|----------|----------|--------|------|
| PM-001 | Add Redis cluster partition alert | @alice | YYYY-MM-DD | P1 | Not Started | [JIRA-101] |
| PM-002 | Automate Kafka broker failover | @bob | YYYY-MM-DD | P1 | In Progress | [JIRA-102] |
| PM-003 | Update database failover runbook | @diana | YYYY-MM-DD | P2 | Done | [JIRA-103] |
| PM-004 | Add circuit breaker monitoring for compute | @charlie | YYYY-MM-DD | P2 | Not Started | [JIRA-104] |
| PM-005 | Schedule quarterly isolation review | @evan | YYYY-MM-DD | P2 | Not Started | [JIRA-105] |

### 6.6 Runbook Testing Schedule

| Runbook | Testing Method | Frequency | Owner | Last Tested | Next Test |
|---------|---------------|-----------|-------|-------------|-----------|
| Database Failover (5.2) | Game day — actual failover | Quarterly | SRE Team | 2026-07-01 | 2026-10-01 |
| Redis Degraded (5.3) | Game day — node kill | Quarterly | Data Team | 2026-06-15 | 2026-09-15 |
| Kafka Broker Failure (5.4) | Game day — broker kill | Quarterly | Data Team | 2026-07-10 | 2026-10-10 |
| Multi-Region Failover (5.8) | Game day — region failover | Semi-annual | SRE Team | 2026-06-01 | 2026-12-01 |
| Gateway Latency (5.5) | Tabletop exercise | Quarterly | SRE Team | 2026-07-20 | 2026-10-20 |
| KYC Pipeline Failure (5.1) | Tabletop exercise | Quarterly | ML Team | 2026-06-30 | 2026-09-30 |
| Isolation Breach (5.6) | Tabletop + simulated breach | Quarterly | Security Team | 2026-07-15 | 2026-10-15 |
| ML Model Drift (5.7) | Tabletop exercise | Quarterly | ML Team | 2026-06-20 | 2026-09-20 |
| Certificate Expiry | Game day — expire cert | Semi-annual | SRE Team | 2026-05-01 | 2026-11-01 |
| Vault Seal | Game day — seal vault | Semi-annual | Security Team | 2026-05-15 | 2026-11-15 |

### 6.7 Emergency Deployment Procedure

For SEV1/SEV2 incidents requiring an expedited code change:

```bash
# 1. CREATE HOTFIX BRANCH
git checkout -b hotfix/INC-YYYY-MM-DD-NNN-description

# 2. MAKE AND COMMIT THE FIX
git add .
git commit -m "fix: [description] — INC-YYYY-MM-DD-NNN"

# 3. BYPASS STANDARD CI (SRE Lead approval required)
# In ArgoCD, enable auto-sync and bypass test gates:
argocd app set usora-gateway --sync-policy automated

# 4. DEPLOY WITH OVERRIDE
argocd app sync usora-gateway --apply-out-of-sync-only

# 5. MONITOR
argocd app get usora-gateway --watch

# 6. AFTER INCIDENT: REVERT TO STANDARD PROCESS
# - Re-enable CI/CD gates
# - Create proper PR + review
# - Add regression tests
# - Schedule postmortem
```

### 6.8 Command Quick Reference

| Task | Command |
|------|---------|
| List pods by namespace | `kubectl get pods -n <namespace> -o wide` |
| View pod logs | `kubectl logs -n <namespace> -l app=<service> --tail=200` |
| Describe pod | `kubectl describe pod <pod> -n <namespace>` |
| Restart deployment | `kubectl rollout restart deployment <name> -n <namespace>` |
| Scale deployment | `kubectl scale deployment <name> --replicas=N -n <namespace>` |
| Get events sorted | `kubectl get events --sort-by='.lastTimestamp' -n <namespace>` |
| Get resource usage | `kubectl top pods -n <namespace>` |
| PSQL query | `kubectl exec <pod> -n <namespace> -- psql -U postgres -c "<query>"` |
| Redis CLI | `kubectl exec <pod> -n <namespace> -- redis-cli <command>` |
| Kafka consumer lag | `kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group <group>` |
| Kafka topic info | `kafka-topics.sh --bootstrap-server kafka:9092 --describe --topic <topic>` |

---

## Document Information

| Field | Value |
|-------|-------|
| **Document Version** | 1.0.0 |
| **Owner** | SRE Team |
| **Last Updated** | 2026-07-25 |
| **Classification** | Internal — Confidential |
| **Review Cycle** | Monthly |
| **Next Review** | 2026-08-25 |
| **Author** | USORA SRE Team |

---

*USORA — Trust at Scale. Always be prepared.*
