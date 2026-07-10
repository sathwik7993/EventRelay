# Indexing Strategy

> Comprehensive PostgreSQL indexing strategy for EventRelay's query patterns and performance requirements.

## Table of Contents

- [Overview](#overview)
- [Indexing Principles](#indexing-principles)
- [Complete Index Inventory](#complete-index-inventory)
- [Table-Specific Index Design](#table-specific-index-design)
  - [Tenants](#tenants)
  - [API Keys](#api-keys)
  - [Subscriptions](#subscriptions)
  - [Events](#events)
  - [Outbox](#outbox)
  - [Delivery Attempts](#delivery-attempts)
  - [Dead Letter Events](#dead-letter-events)
- [Partial Indexes](#partial-indexes)
- [Composite Index Design](#composite-index-design)
- [GIN Indexes for JSONB and Arrays](#gin-indexes-for-jsonb-and-arrays)
- [EXPLAIN ANALYZE Examples](#explain-analyze-examples)
- [Index Maintenance](#index-maintenance)
  - [Index Bloat Detection](#index-bloat-detection)
  - [Index Rebuild (REINDEX CONCURRENTLY)](#index-rebuild)
  - [Unused Index Detection](#unused-index-detection)
- [Anti-Patterns](#anti-patterns)
- [Production Considerations](#production-considerations)

---

## Overview

EventRelay's indexing strategy balances **read performance** (fast queries for dashboards and debugging) against **write overhead** (every index slows down INSERTs). The guiding principle: index for the queries you run, not the queries you might run.

**Key constraints:**
- The `events` and `delivery_attempts` tables have very high write throughput (thousands of INSERTs/sec)
- The `outbox` table has extremely high churn (INSERT → poll → DELETE within seconds)
- Dashboard and debugging queries must return in < 50ms
- Tenant-scoped queries dominate; cross-tenant queries are admin-only

---

## Indexing Principles

| Principle | Rationale |
|---|---|
| **Lead with `tenant_id`** | Every query filters by tenant; leading with `tenant_id` enables efficient scoping |
| **Use partial indexes** | Index only the rows you query (e.g., `WHERE status = 'PENDING'`) to reduce size |
| **Composite over single-column** | `(tenant_id, created_at)` serves both "by tenant" and "by tenant + time" queries |
| **Minimize indexes on hot tables** | Each index on `events` and `outbox` adds write latency |
| **Leverage partition pruning** | On partitioned tables, include `created_at` bounds to eliminate irrelevant partitions |
| **Monitor and prune unused indexes** | Run `pg_stat_user_indexes` monthly to identify dead indexes |

---

## Complete Index Inventory

| Table | Index Name | Columns | Type | Partial? | Purpose |
|---|---|---|---|---|---|
| **tenants** | `pk_tenants` | `id` | B-tree (PK) | No | Primary key |
| | `uq_tenants_slug` | `slug` | B-tree (Unique) | No | Slug uniqueness |
| | `idx_tenants_slug_active` | `slug` | B-tree | `WHERE deleted_at IS NULL` | Auth flow lookup |
| **api_keys** | `pk_api_keys` | `id` | B-tree (PK) | No | Primary key |
| | `uq_api_keys_prefix` | `key_prefix` | B-tree (Unique) | No | Prefix uniqueness |
| | `idx_api_keys_tenant` | `tenant_id` | B-tree | `WHERE revoked_at IS NULL` | List keys by tenant |
| | `idx_api_keys_prefix_active` | `key_prefix` | B-tree | `WHERE revoked_at IS NULL` | Auth: validate key |
| **subscriptions** | `pk_subscriptions` | `id` | B-tree (PK) | No | Primary key |
| | `idx_subs_tenant` | `tenant_id` | B-tree | `WHERE deleted_at IS NULL` | List subs by tenant |
| | `idx_subs_event_types` | `event_types` | GIN | `WHERE deleted_at IS NULL` | Event routing |
| **events** | `pk_events` | `id` | B-tree (PK) | No | Primary key |
| | `uq_events_idempotency` | `(tenant_id, idempotency_key)` | B-tree (Unique) | No | Dedup enforcement |
| | `idx_events_tenant_created` | `(tenant_id, created_at DESC)` | B-tree | No | Tenant dashboard |
| | `idx_events_tenant_type` | `(tenant_id, event_type)` | B-tree | No | Filter by type |
| | `idx_events_idempotency` | `(tenant_id, idempotency_key)` | B-tree | `WHERE idempotency_key IS NOT NULL` | Dedup check |
| **outbox** | `pk_outbox` | `id` | B-tree (PK) | No | Primary key |
| | `idx_outbox_pending` | `created_at ASC` | B-tree | `WHERE status = 'PENDING'` | Poller hot path |
| | `idx_outbox_processed` | `processed_at` | B-tree | `WHERE status = 'PROCESSED'` | Cleanup job |
| **delivery_attempts** | `pk_delivery_attempts` | `id` | B-tree (PK) | No | Primary key |
| | `idx_delivery_event_id` | `event_id` | B-tree | No | Attempts per event |
| | `idx_delivery_tenant_created` | `(tenant_id, created_at DESC)` | B-tree | No | Tenant history |
| | `idx_delivery_subscription` | `(subscription_id, created_at DESC)` | B-tree | No | Sub health |
| | `idx_delivery_status` | `(status, created_at DESC)` | B-tree | `WHERE status != 'SUCCESS'` | Failed delivery investigation |
| **dead_letter_events** | `pk_dead_letter_events` | `id` | B-tree (PK) | No | Primary key |
| | `idx_dlq_tenant_status` | `(tenant_id, status)` | B-tree | `WHERE status = 'PENDING'` | DLQ dashboard |
| | `idx_dlq_tenant_failed` | `(tenant_id, failed_at DESC)` | B-tree | No | DLQ timeline |
| | `idx_dlq_event_id` | `event_id` | B-tree | No | Event lookup |

**Total: 24 indexes across 7 tables.**

---

## Table-Specific Index Design

### Tenants

Low-volume table (~thousands of rows). Indexes are minimal:

```sql
-- Auth flow: resolve tenant from API key → tenant slug
CREATE INDEX idx_tenants_slug_active ON tenants(slug) WHERE deleted_at IS NULL;
```

### API Keys

Queried on every API request during authentication:

```sql
-- Hot path: look up key by prefix, check if active
CREATE INDEX idx_api_keys_prefix_active ON api_keys(key_prefix) WHERE revoked_at IS NULL;

-- Admin: list all active keys for a tenant
CREATE INDEX idx_api_keys_tenant ON api_keys(tenant_id) WHERE revoked_at IS NULL;
```

> [!TIP]
> Cache API key lookups in Redis (TTL: 5 min) to avoid hitting PostgreSQL on every request. The index serves as a fallback for cache misses.

### Subscriptions

Queried during event routing (for every ingested event):

```sql
-- Event routing: find all active subscriptions for a tenant that match an event type
CREATE INDEX idx_subs_tenant ON subscriptions(tenant_id) WHERE deleted_at IS NULL;

-- GIN index for array containment: "which subscriptions listen for 'order.completed'?"
CREATE INDEX idx_subs_event_types ON subscriptions USING GIN (event_types)
    WHERE deleted_at IS NULL;
```

**Routing query:**

```sql
SELECT id, target_url, signing_secret, event_types, config
FROM subscriptions
WHERE tenant_id = :tenantId
  AND deleted_at IS NULL
  AND status = 'ACTIVE'
  AND (event_types = '{}' OR event_types @> ARRAY[:eventType]);
```

### Events

High-write table (thousands of INSERTs/sec). Every index costs write performance:

```sql
-- PRIMARY: tenant dashboard — list recent events
CREATE INDEX idx_events_tenant_created ON events(tenant_id, created_at DESC);

-- SECONDARY: filter by event type
CREATE INDEX idx_events_tenant_type ON events(tenant_id, event_type);

-- DEDUP: idempotency check (partial — only rows with a key)
CREATE INDEX idx_events_idempotency ON events(tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
```

> [!IMPORTANT]
> Do NOT add more indexes to the events table without benchmarking write performance impact. Each additional index can add 0.5-1ms per INSERT at high throughput.

### Outbox

The outbox has extreme churn (rows live for seconds). Only two indexes:

```sql
-- HOT PATH: the poller grabs PENDING rows ordered by creation
CREATE INDEX idx_outbox_pending ON outbox(created_at ASC)
    WHERE status = 'PENDING';

-- CLEANUP: find old processed rows for deletion
CREATE INDEX idx_outbox_processed ON outbox(processed_at)
    WHERE status = 'PROCESSED';
```

### Delivery Attempts

High-volume read+write table. Indexes support debugging queries:

```sql
-- "Show me all delivery attempts for event X"
CREATE INDEX idx_delivery_event_id ON delivery_attempts(event_id);

-- Tenant dashboard: recent delivery history
CREATE INDEX idx_delivery_tenant_created ON delivery_attempts(tenant_id, created_at DESC);

-- Subscription health: attempts per subscription
CREATE INDEX idx_delivery_subscription ON delivery_attempts(subscription_id, created_at DESC);

-- Failed delivery investigation (partial: excludes SUCCESS rows)
CREATE INDEX idx_delivery_status ON delivery_attempts(status, created_at DESC)
    WHERE status != 'SUCCESS';
```

### Dead Letter Events

Low-to-medium volume. Indexes support the DLQ dashboard:

```sql
-- DLQ dashboard: pending items per tenant
CREATE INDEX idx_dlq_tenant_status ON dead_letter_events(tenant_id, status)
    WHERE status = 'PENDING';

-- DLQ timeline: recent failures
CREATE INDEX idx_dlq_tenant_failed ON dead_letter_events(tenant_id, failed_at DESC);

-- Event lookup: find DLQ entries for a specific event
CREATE INDEX idx_dlq_event_id ON dead_letter_events(event_id);
```

---

## Partial Indexes

Partial indexes are critical for performance on high-volume tables. They index only the rows that match a WHERE predicate, dramatically reducing index size and write overhead.

### EventRelay's Partial Indexes

```sql
-- 1. Outbox PENDING: typically < 1% of rows are PENDING
--    Full index: 10M entries. Partial index: ~100K entries.
CREATE INDEX idx_outbox_pending ON outbox(created_at ASC)
    WHERE status = 'PENDING';

-- 2. Outbox PROCESSED: intermediate state during cleanup window
CREATE INDEX idx_outbox_processed ON outbox(processed_at)
    WHERE status = 'PROCESSED';

-- 3. Delivery failures: typically 2-5% of all delivery attempts
--    Full index: 100M entries. Partial index: ~5M entries.
CREATE INDEX idx_delivery_status ON delivery_attempts(status, created_at DESC)
    WHERE status != 'SUCCESS';

-- 4. DLQ pending: only actionable items
CREATE INDEX idx_dlq_tenant_status ON dead_letter_events(tenant_id, status)
    WHERE status = 'PENDING';

-- 5. Active API keys: revoked keys don't need fast lookup
CREATE INDEX idx_api_keys_prefix_active ON api_keys(key_prefix)
    WHERE revoked_at IS NULL;

-- 6. Active subscriptions: soft-deleted subscriptions excluded
CREATE INDEX idx_subs_tenant ON subscriptions(tenant_id)
    WHERE deleted_at IS NULL;

-- 7. Non-null idempotency keys: many events have no key
CREATE INDEX idx_events_idempotency ON events(tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
```

### Size Impact

| Table | Full Index Size | Partial Index Size | Reduction |
|---|---|---|---|
| outbox (pending) | ~500 MB | ~5 MB | 99% |
| delivery_attempts (failures) | ~2 GB | ~100 MB | 95% |
| dead_letter_events (pending) | ~50 MB | ~5 MB | 90% |

---

## Composite Index Design

### Column Ordering in Composite Indexes

The column order in a composite index matters. Place the **most selective filter first**, followed by **sort columns**:

```sql
-- ✅ GOOD: tenant_id first (equality filter), then created_at (range/sort)
CREATE INDEX idx_events_tenant_created ON events(tenant_id, created_at DESC);

-- ❌ BAD: created_at first — can't efficiently filter by tenant within a time range
CREATE INDEX idx_events_created_tenant ON events(created_at DESC, tenant_id);
```

### Index-Only Scans

For the most frequent queries, design indexes that cover all returned columns to enable index-only scans:

```sql
-- Covering index for the outbox poller (includes payload)
-- Note: this trades disk space for I/O savings
CREATE INDEX idx_outbox_pending_covering ON outbox(created_at ASC)
    INCLUDE (aggregate_type, aggregate_id, event_type, payload)
    WHERE status = 'PENDING';
```

> [!NOTE]
> Covering indexes with `INCLUDE` are available in PostgreSQL 11+. Use them judiciously — they increase index size but eliminate heap fetches for index-only scans.

---

## GIN Indexes for JSONB and Arrays

### Array Containment (Subscriptions)

```sql
-- Find subscriptions that listen for a specific event type
CREATE INDEX idx_subs_event_types ON subscriptions USING GIN (event_types)
    WHERE deleted_at IS NULL;

-- Query pattern: @> (contains) operator
SELECT * FROM subscriptions
WHERE event_types @> ARRAY['order.completed']
  AND deleted_at IS NULL;
```

### JSONB Queries (Optional)

If tenants need to query event payloads:

```sql
-- GIN index on event payload for arbitrary JSONB queries
-- WARNING: expensive to maintain on high-write tables
CREATE INDEX idx_events_payload ON events USING GIN (payload jsonb_path_ops);

-- Query: find events where payload contains a specific key-value
SELECT * FROM events
WHERE tenant_id = :tenantId
  AND payload @> '{"order_id": "ORD-12345"}'::jsonb;
```

> [!WARNING]
> GIN indexes on the `events.payload` column are expensive to maintain at high write throughput. Only create them if you have a concrete use case for payload-level queries.

---

## EXPLAIN ANALYZE Examples

### 1. Outbox Polling Query

```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT id, aggregate_type, aggregate_id, event_type, payload, created_at
FROM outbox
WHERE status = 'PENDING'
ORDER BY created_at ASC, id ASC
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

**Expected plan (with partial index):**

```
Limit  (cost=0.42..12.56 rows=100 width=523) (actual time=0.031..0.187 rows=100 loops=1)
  ->  LockRows  (cost=0.42..1245.67 rows=10234 width=523) (actual time=0.029..0.178 rows=100 loops=1)
        ->  Index Scan using idx_outbox_pending on outbox  (cost=0.42..1143.33 rows=10234 width=523)
              Index Cond: (status = 'PENDING'::outbox_status)
              Buffers: shared hit=15
Planning Time: 0.089 ms
Execution Time: 0.214 ms
```

### 2. Events Dashboard Query

```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT id, event_type, payload, created_at
FROM events
WHERE tenant_id = 'a1b2c3d4-e5f6-7890-abcd-ef0123456789'
  AND created_at >= '2026-07-01'
  AND created_at < '2026-07-11'
ORDER BY created_at DESC
LIMIT 50;
```

**Expected plan (with partition pruning):**

```
Limit  (cost=0.56..8.23 rows=50 width=512) (actual time=0.045..0.312 rows=50 loops=1)
  ->  Index Scan Backward using events_y2026m07_tenant_created on events_y2026m07  (cost=0.56..892.45 rows=5678 width=512)
        Index Cond: (tenant_id = 'a1b2c3d4-...'::uuid AND created_at >= '2026-07-01' AND created_at < '2026-07-11')
        Buffers: shared hit=23
Planning Time: 1.234 ms  (partition pruning time)
Execution Time: 0.389 ms
```

> [!TIP]
> Look for `Buffers: shared hit=XX` in EXPLAIN output. High `shared hit` means data is in the buffer cache. `shared read` indicates disk I/O.

### 3. Delivery Attempts for Event

```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT attempt_number, status, http_status_code, duration_ms, error_message, created_at
FROM delivery_attempts
WHERE event_id = '11111111-2222-3333-4444-555555555555'
ORDER BY attempt_number ASC;
```

**Expected plan:**

```
Sort  (cost=8.17..8.18 rows=5 width=128) (actual time=0.045..0.047 rows=3 loops=1)
  Sort Key: attempt_number
  Sort Method: quicksort  Memory: 25kB
  ->  Index Scan using idx_delivery_event_id on delivery_attempts_y2026m07  (cost=0.43..8.15 rows=5 width=128)
        Index Cond: (event_id = '11111111-...'::uuid)
        Buffers: shared hit=4
Execution Time: 0.067 ms
```

### 4. Failed Delivery Analysis

```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT http_status_code, count(*) AS occurrences
FROM delivery_attempts
WHERE tenant_id = :tenantId
  AND status != 'SUCCESS'
  AND created_at >= now() - INTERVAL '24 hours'
GROUP BY http_status_code
ORDER BY occurrences DESC;
```

**Expected plan (with partial index on status):**

```
Sort  (cost=45.23..45.25 rows=8 width=12) (actual time=2.156..2.158 rows=4 loops=1)
  Sort Key: (count(*)) DESC
  ->  HashAggregate  (cost=44.89..45.05 rows=8 width=12) (actual time=2.134..2.140 rows=4 loops=1)
        ->  Bitmap Heap Scan on delivery_attempts_y2026m07  (cost=4.56..44.12 rows=312 width=4)
              Recheck Cond: (status <> 'SUCCESS' AND created_at >= ...)
              Filter: (tenant_id = '...'::uuid)
              ->  Bitmap Index Scan on idx_delivery_status  (cost=0.00..4.48 rows=312 width=0)
Execution Time: 2.234 ms
```

---

## Index Maintenance

### Index Bloat Detection

```sql
-- Check index bloat using pg_stat_user_indexes
SELECT
    schemaname,
    relname AS table_name,
    indexrelname AS index_name,
    pg_size_pretty(pg_relation_size(indexrelid)) AS index_size,
    idx_scan AS index_scans,
    idx_tup_read AS tuples_read,
    idx_tup_fetch AS tuples_fetched
FROM pg_stat_user_indexes
WHERE schemaname = 'public'
ORDER BY pg_relation_size(indexrelid) DESC;

-- Estimated index bloat (using pgstattuple extension)
CREATE EXTENSION IF NOT EXISTS pgstattuple;

SELECT
    indexrelname,
    pg_size_pretty(pg_relation_size(indexrelid)) AS index_size,
    100 - (avg_leaf_density) AS bloat_pct
FROM pg_stat_user_indexes psi
JOIN LATERAL pgstatindex(psi.indexrelid) ON true
WHERE schemaname = 'public'
  AND pg_relation_size(indexrelid) > 10 * 1024 * 1024  -- Only indexes > 10MB
ORDER BY bloat_pct DESC;
```

### Index Rebuild

When bloat exceeds 30%, rebuild the index:

```sql
-- CONCURRENTLY: rebuilds without locking the table (requires extra disk space)
REINDEX INDEX CONCURRENTLY idx_outbox_pending;
REINDEX INDEX CONCURRENTLY idx_delivery_event_id;

-- Rebuild all indexes on a table
REINDEX TABLE CONCURRENTLY outbox;
```

> [!IMPORTANT]
> `REINDEX CONCURRENTLY` (PostgreSQL 12+) does not block reads or writes, but requires roughly 2x the disk space temporarily. Schedule during low-traffic windows.

### Unused Index Detection

```sql
-- Find indexes with zero scans (candidates for removal)
SELECT
    schemaname,
    relname AS table_name,
    indexrelname AS index_name,
    pg_size_pretty(pg_relation_size(indexrelid)) AS index_size,
    idx_scan AS scans_since_reset
FROM pg_stat_user_indexes
WHERE idx_scan = 0
  AND schemaname = 'public'
  AND indexrelname NOT LIKE '%pkey'     -- Keep primary keys
  AND indexrelname NOT LIKE 'uq_%'     -- Keep unique constraints
ORDER BY pg_relation_size(indexrelid) DESC;

-- Check when stats were last reset
SELECT stats_reset FROM pg_stat_database WHERE datname = current_database();
```

> [!WARNING]
> Wait at least 30 days after a stats reset before concluding an index is unused. Some queries run on monthly schedules.

---

## Anti-Patterns

| Anti-Pattern | Why It's Bad | Better Approach |
|---|---|---|
| Index on every column | Write amplification; wasted disk | Index only for measured query patterns |
| Leading with low-selectivity column | `(status, tenant_id)` when status has 4 values | Lead with high-selectivity: `(tenant_id, status)` |
| Full index on boolean column | Only 2 values; basically a full table scan | Partial index: `WHERE is_active = true` |
| GIN index on high-write JSONB | Extremely expensive to maintain | Use B-tree on extracted fields, or query-time filter |
| Duplicate indexes | `(tenant_id)` + `(tenant_id, created_at)` — the first is redundant | Drop the single-column index |
| Not using partial indexes | Outbox: indexing all rows when 99% are PROCESSED | `WHERE status = 'PENDING'` |

---

## Production Considerations

1. **Benchmark before adding indexes** — use `pg_test_timing` and `EXPLAIN (ANALYZE, BUFFERS)` on production-like data
2. **Create indexes concurrently** — always use `CREATE INDEX CONCURRENTLY` in migrations to avoid table locks
3. **Monitor index hit ratio** — target > 99% cache hit ratio for indexes: `SELECT pg_stat_get_idx_blks_hit(oid) / (pg_stat_get_idx_blks_hit(oid) + pg_stat_get_idx_blks_read(oid))::float FROM pg_class WHERE relname = 'idx_name'`
4. **Size tracking** — alert when any index exceeds 1 GB (possible bloat)
5. **Partition-local indexes** — on partitioned tables, indexes are created per-partition. Monitor per-partition index sizes
6. **Autovacuum tuning** — aggressive autovacuum on high-churn tables (outbox, delivery_attempts) to prevent index bloat

---

## Related Documents

- [PostgreSQL_Schema.md](./PostgreSQL_Schema.md) — Full schema DDL with inline index definitions
- [Outbox_Table.md](./Outbox_Table.md) — Outbox polling query performance
- [Delivery_Attempts.md](./Delivery_Attempts.md) — Debugging queries and their index usage
- [Partitioning.md](./Partitioning.md) — Partition-local indexing
- [Migrations.md](./Migrations.md) — How to add indexes via Flyway migrations
