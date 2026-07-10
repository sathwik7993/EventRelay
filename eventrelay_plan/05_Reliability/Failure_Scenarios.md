# EventRelay — Failure Scenario Catalog

This document cataloges major infrastructure failure scenarios, detailing their impact, detection mechanisms, automated recoveries, and remediation guidelines for engineers.

---

## 1. System Failures and Recovery Matrix

| Scenario ID | Impacted Component | Root Cause | System Behavior | Remediation / Verification |
|-------------|--------------------|------------|-----------------|----------------------------|
| **FS-001** | **Ingest API** | PostgreSQL database crash. | Ingestion endpoints fail validation; HTTP 500 error spike. | Wait for Multi-AZ RDS promotion. Check HikariCP connection recovery logs. |
| **FS-002** | **Dispatcher Workers**| Redis cache outage. | Token-bucket validation fails; configuration lookups hit DB directly. | Fall back to local memory rate limiting. Verify Redis cluster status. |
| **FS-003** | **SQS Queue** | SQS service throttling. | Message polling stalls; outbox Poller logs warnings. | Monitor AWS SQS API limits. Adjust long polling receive durations. |
| **FS-004** | **DNS / Networking** | Target DNS lookup failure. | Workers cannot resolve target domains; HTTP delivery aborts. | Confirm network routing through NAT. Verify SSRF validation is not overly restrictive. |
| **FS-005** | **Webhook Endpoint** | Receiver server returns 502/504. | Workers trigger exponential backoff. Event goes to retry queue. | Check receiver health. Verify circuit breaker opens if failures continue. |

---

## 2. In-Depth Failure Case Studies

### FS-001: Ingest Database Crash (PostgreSQL)
- **Impact**: All write operations fail. Because of the dual-write problem, if the database is down, events cannot be safely saved in the outbox table.
- **Mitigation**:
  - The Ingest Service automatically rejects incoming requests with a `503 Service Unavailable` response, prompting producers to hold their requests.
  - AWS RDS initiates Multi-AZ failover, updating DNS endpoints to target the hot-standby database instance in AZ-B.
  - ECS tasks recover automatically once the database connection endpoint becomes writeable again.

### FS-005: Target Endpoint Outage
- **Impact**: Heavy load on dispatcher thread pools.
- **Mitigation**:
  - Instead of blocking threads for 30 seconds on every request, the **Resilience4j Circuit Breaker** opens after 50 consecutive connection failures to a specific destination.
  - Once open, the worker short-circuits the pipeline, bypasses the socket call, and routes events directly to the retry scheduler, preserving worker capacity for healthy endpoints.

---

## 3. Playbook: Investigating Worker Thread Pools

When queue latency spikes, check for thread pool starvation:

1. **Query Metrics**: Check `eventrelay_consumer_active_threads` in Grafana. If it stays at maximum (e.g., 20/20 threads) and queue age is rising, the threads are blocked on slow deliveries.
2. **Analyze Latency Profile**: Run Prometheus query:
   `histogram_quantile(0.95, sum(rate(eventrelay_delivery_latency_seconds_bucket[5m])) by (le))`
   If p95 latency is $>30$ seconds, identify the slow endpoints:
   ```sql
   SELECT target_url, avg(duration_ms) 
   FROM delivery_attempts 
   WHERE created_at > now() - interval '1 hour' 
   GROUP BY target_url 
   ORDER BY 2 DESC LIMIT 10;
   ```
3. **Action**: Pause the slow subscription URL or route it to the slow-delivery queue to clear the backlog.
