# EventRelay — Failure Recovery & Runbooks

This document outlines the failure recovery protocols, disaster recovery runbooks, and mitigation strategies for major infrastructure failures in the EventRelay platform.

---

## 1. High-Level Failure Matrix

| Component | Failure Scenario | Impact | Auto-Mitigation | Manual Action (Runbook) |
|-----------|------------------|--------|-----------------|-------------------------|
| **PostgreSQL DB** | Database goes down. | Ingest API fails; Outbox writes fail. | RDS Multi-AZ automatic failover. | None if auto-failover succeeds; verify connection recovery in ECS tasks. |
| **AWS SQS** | SQS service is unreachable. | Poller cannot send; Workers cannot read. | Outbox poller pauses, database buffers incoming events. | None. Ingest continues. When SQS recovers, outbox poller catches up automatically. |
| **Redis Cache** | Redis node goes down. | Key cache lost; Rate limiting fails. | Redis Sentinel/Cluster auto-promotion. Ingest service falls back to DB query. | Verify memory usage. Reset rate-limit tables if Redis was completely wiped. |
| **ECS Workers** | Worker crashes mid-delivery. | Message delivery is interrupted. | SQS Visibility Timeout expires; message is redelivered. | Investigate memory leaks or OOM exceptions in ECS logs. |
| **Receiver URL** | Receiver is down or flaky. | Failed requests, high retry rates. | Exponential backoff retry; Resilience4j Circuit Breaker opens. | Customer-side review. Outages trigger DLQ movement where events wait for replay. |

---

## 2. Runbook: Ingestion Database Unavailability

If the PostgreSQL database becomes unavailable and Multi-AZ failover fails to recover in 60 seconds:

### Detection
- Ingest Service logs show `JDBC Connection Timeout` or `CannotGetJdbcConnectionException`.
- CloudWatch alarm for `ALB 5XX Errors` triggers.

### Immediate Mitigation Steps
1. **Enable API Gatekeeper Mode**: Route incoming traffic on the ALB to a static maintenance response page returning `429 Too Many Requests` or `503 Service Unavailable` with a `Retry-After: 300` header, shielding downstream clients and preventing connection pool exhaustion.
2. **Increase Ingestion Thread Cache**: If the database is back up but running slow:
   - Temporarily increase the maximum connection pool size in Spring Boot via AWS AppConfig.
3. **Trigger Ingestion Recovery**: Once the database is online, monitor database connection counts and CPU load. Gradually remove the maintenance page at the ALB level (e.g., $10\%$ of traffic, then $50\%$, then $100\%$).

---

## 3. Runbook: SQS Outage Recovery

While SQS is a highly durable service with a $99.99\%$ SLA, a service disruption can occur.

### Ingest Stability (No Data Loss)
- EventRelay guarantees zero data loss because of the **Transactional Outbox Pattern**.
- If SQS goes down, the Outbox Poller fails to publish messages. The poller catches the exception, pauses execution, and leaves the outbox records marked as `PENDING`.
- Clients continue posting events successfully because the Ingest API writes to PostgreSQL first, buffering the events securely in the database.

### Recovery Execution Steps
1. Monitor the SQS service status on the AWS Service Health Dashboard.
2. Once SQS recovers, restart the Outbox Poller service in ECS (this forces database connections to re-establish).
3. The Poller will begin reading `PENDING` outbox entries from PostgreSQL and publishing them to SQS.
4. **Monitor PostgreSQL CPU**: Because of the accumulated buffer, database activity will spike. Use `SELECT count(*) FROM outbox WHERE status = 'PENDING'` to monitor the drain rate. If database CPU exceeds $85\%$, temporarily scale down the outbox poller replicas to throttle database reads.

---

## 4. Runbook: Webhook Replay after Receiver Recovery

When a major customer endpoint goes down for several hours, thousands of their events will land in the Dead-Letter Queue (DLQ). Once the customer resolves their outage, follow these steps to replay their events:

### Replay Execution via CLI/API
Trigger a batch replay for the specific subscription:
```bash
curl -X POST https://api.eventrelay.internal/api/v1/replay/batch \
  -H "Authorization: Bearer er_live_..." \
  -H "Content-Type: application/json" \
  -d '{
    "subscription_id": "8f3c4d21-a1b2-4c3d-9d8e-5f6a7b8c9d0e",
    "status_filter": "DEAD_LETTERED",
    "failed_after": "2026-07-10T00:00:00Z",
    "failed_before": "2026-07-10T08:00:00Z"
  }'
```

### Post-Replay Verification
1. Open the Grafana dashboard.
2. Filter the view by the customer's `tenant_id`.
3. Verify that the **Replay Delivery Rate** spikes, and the **Replay Success Rate** matches or exceeds $98\%$.
4. Check that no new messages are entering the DLQ for this tenant.
