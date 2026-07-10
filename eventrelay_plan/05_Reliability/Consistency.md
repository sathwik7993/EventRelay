# EventRelay — Consistency & Coordination

This document details the consistency model of EventRelay, explaining how state synchronization is maintained between PostgreSQL, Redis, and SQS without using slow distributed transactions.

---

## 1. System Ingestion Flow (Transactional Outbox)

EventRelay prevents the "dual-write" problem (where database write succeeds but SQS publish fails, or vice-versa) by utilizing a **Transactional Outbox pattern**:

```
[ Client Request ] ──► [ Start Transaction ]
                              │
                              ├──► INSERT INTO events (Log)
                              ├──► INSERT INTO outbox (Pending task)
                              │
                       [ Commit Transaction ]
                              │
                              ▼ (HTTP 202 Accepted returned)
```

- **Database Consistency**: Because both writes are coupled in a single PostgreSQL transaction, the outbox record is guaranteed to exist if the event is logged.
- **Eventually Consistent Publishing**: The Outbox Poller runs in a background thread, constantly polling for `PENDING` outbox entries and publishing them to SQS. Once SQS acknowledges receipt, the poller updates the database status to `PROCESSED`.
- **At-Least-Once Guarantee**: If the poller crashes after publishing to SQS but before marking the outbox as `PROCESSED`, the replacement poller will read the row again and publish it, resulting in a duplicate SQS message. This is resolved via consumer-side deduplication.

---

## 2. Redis and PostgreSQL State Coordination

Redis stores volatile caches (rate limits, tenant configs, idempotency checks) while PostgreSQL is the source of truth:

| Cache Key | PostgreSQL Source | Invalidation Trigger | Sync Strategy |
|-----------|-------------------|----------------------|---------------|
| `tenant:{id}:config` | `tenants` | Tenant config updated via API. | **Cache Eviction**: The API handler updates PostgreSQL and deletes the Redis key. The next request reads the new values from PostgreSQL and refills the cache. |
| `dedup:{tenant_id}:{key}` | `events` (idempotency_key) | Event ingestion. | **Write-Through Cache**: Written to Redis upon ingestion. Persisted to database log asynchronously. |

---

## 3. Distributed Lock Strategy (Leader Election)

To prevent multiple instances of the Outbox Poller from scanning the database concurrently and creating lock contention, EventRelay uses a Redis-based distributed lock:

- **Lock Mechanism**: The poller service tries to acquire a lock using `SET outbox:poller:leader "node-id" NX PX 30000` (expires in 30 seconds).
- **Lock Renewal**: The leader node runs a background thread (watchdog) that renews the lock TTL every 10 seconds.
- **Failover**: If the leader node crashes, the lock expires in 30 seconds, and a standby poller node acquires it, resuming outbox polling with minimal delay.
