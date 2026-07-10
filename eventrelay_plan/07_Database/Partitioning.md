# EventRelay — Database Partitioning Strategy

This document details the PostgreSQL table partitioning design implemented in EventRelay, explaining how monthly partitioning maintains fast query response times on billions of delivery logs.

---

## 1. Why Table Partitioning?

As a webhook delivery platform, EventRelay generates millions of events and delivery log rows daily.
- Without partitioning, database indexes grow too large to fit in memory (RAM), causing queries to fall back to disk I/O and degrading performance.
- Table partitioning splits tables into smaller, logical child tables.
- Query planning uses **Partition Pruning** to scan only the child tables matching the query conditions.

---

## 2. Partition Configuration

The `events` and `delivery_attempts` tables are partitioned using PostgreSQL range partitioning:

```
[ events (Partition Root) ]
        ├── events_y2026m07 (July 2026)
        ├── events_y2026m08 (August 2026)
        └── events_y2026m09 (September 2026)
```

- **Partition Key**: `created_at` timestamp.
- **Partition Range**: 1 Month per partition.

### Schema Definition (Flyway Migration)
```sql
-- Create Parent Table
CREATE TABLE events (
    id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    subscription_id UUID NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    idempotency_key VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (id, created_at) -- Partition key MUST be part of primary key
) PARTITION BY RANGE (created_at);

-- Example Partition Creation
CREATE TABLE events_y2026m07 PARTITION OF events
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');
```

---

## 3. Partition Creation Automation

Partitions must be created before the new month starts. EventRelay uses a scheduled Spring Boot cron task to automate this:

```java
@Service
@RequiredArgsConstructor
public class PartitionManager {

    private final JdbcTemplate jdbcTemplate;

    @Scheduled(cron = "0 0 0 25 * *") // Run on the 25th of every month
    public void createNextMonthPartition() {
        LocalDate nextMonth = LocalDate.now().plusMonths(1);
        String partitionName = String.format("events_y%dm%02d", nextMonth.getYear(), nextMonth.getMonthValue());
        
        LocalDate start = nextMonth.withDayOfMonth(1);
        LocalDate end = nextMonth.plusMonths(1).withDayOfMonth(1);
        
        String sql = String.format(
            "CREATE TABLE IF NOT EXISTS %s PARTITION OF events FOR VALUES FROM ('%s') TO ('%s')",
            partitionName, start.toString(), end.toString()
        );
        
        jdbcTemplate.execute(sql);
        log.info("Successfully created database partition: {}", partitionName);
    }
}
```

---

## 4. Query Pruning Verification

To verify that queries target only the active partition, run `EXPLAIN` on database queries:

```sql
EXPLAIN SELECT * FROM events 
WHERE tenant_id = '8f3c4d21-a1b2-4c3d-9d8e-5f6a7b8c9d0e' 
  AND created_at >= '2026-07-10 00:00:00+00' 
  AND created_at < '2026-07-11 00:00:00+00';
```

- In the query output, verify that the planner performs a **Seq Scan** or **Index Scan** only on `events_y2026m07` and ignores all other partitions, keeping index lookups extremely fast.
