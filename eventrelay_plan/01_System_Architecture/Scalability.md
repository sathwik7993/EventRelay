# EventRelay — Scalability Strategy

This document details the scalability design of the EventRelay platform, outlining how the system scales to handle 10,000+ events per second (eps) across ingestion and delivery workloads.

---

## 1. Architectural Scaling Dimensions

EventRelay scales along three independent dimensions, matching the three axes of the Scale Cube (X, Y, Z axes):

```
                       Y-Axis: Service Decoupling
                       (Ingest vs. Dispatch Services)
                                  ▲
                                  │   / Z-Axis: Tenant Partitioning
                                  │  /  (Separate Database Schemas)
                                  │ /
                                  │/
                                  └──────────────► X-Axis: Horizontal Scaling
                                                     (Fargate Container Replicas)
```

1. **X-Axis (Horizontal Scaling)**: Running multiple stateless container replicas of the Ingestion Service and Dispatcher Workers behind load balancers and queue consumers.
2. **Y-Axis (Functional Decomposition)**: Separating the write-heavy Ingest API from the read/write-heavy Dispatcher Workers. They run as independent ECS services and scale using different metrics.
3. **Z-Axis (Data Partitioning)**: Grouping database tables by monthly partitions and routing traffic based on `tenant_id` hash ranges if database clustering becomes necessary.

---

## 2. Ingestion Service Scaling (Write Path)

The Ingestion API is purely stateless, making scaling straightforward.

- **Load Balancer Routing**: AWS Application Load Balancer distributes incoming HTTPS POST requests across active ECS containers using round-robin distribution with active health check monitoring.
- **Connection Pool Tuning (HikariCP)**:
  - Database connection pool sizes are optimized to prevent database CPU exhaustion.
  - Formula: $\text{Pool Size} = ((\text{Core Count} \times 2) + \text{Effective Spindle Count})$.
  - In production, each ECS task is configured with a HikariCP pool of `max-size: 20`, keeping the total connections to the RDS cluster under 500 connections.
- **Asynchronous Handoff**: The Ingest API writes to PostgreSQL and terminates the client connection immediately with a `202 Accepted` response. It does *not* wait for SQS publishing or delivery, keeping request latency $<15\text{ms}$.

---

## 3. Dispatcher Workers Scaling (Read & Delivery Path)

The dispatch engine is a queue-based consumer pattern, which is highly elastic.

- **SQS Parallelism**:
  - AWS SQS standard queues support virtually unlimited throughput.
  - SQS messages are polled in batches of 10 messages per request by dispatcher workers.
  - Each dispatcher task runs an optimized thread pool (configured to `20` worker threads), allowing a single worker task to manage up to 20 concurrent HTTP deliveries.
- **Auto-Scaling Policy**:
  - The ECS worker service scales based on SQS queue depth (`ApproximateNumberOfMessagesVisible`).
  - **Scale-Out Trigger**: If the number of visible messages exceeds 1,000 for more than 1 minute, ECS launches additional tasks (up to 50 replicas maximum).
  - **Scale-In Cooldown**: A cooldown period of 300 seconds prevents premature termination of tasks while temporary spikes are cleared.

---

## 4. Database Scaling & Partitioning

The relational database is the primary bottleneck in high-throughput webhook platforms. EventRelay mitigates this through targetted database design:

### Table Partitioning (Monthly)
The `events` and `delivery_attempts` tables are partitioned using **PostgreSQL Declarative Range Partitioning** based on the `created_at` timestamp.
- Old partitions (older than 90 days) are archived to AWS S3 and detached from PostgreSQL to keep indexes small enough to fit entirely in memory.
- Ingestion queries target only the active partition, keeping insert throughput fast and constant.

### Outbox Poller Scalability
A naive poller query like `SELECT * FROM outbox WHERE processed = false LIMIT 100` causes lock contention and serial bottlenecks. EventRelay uses:
```sql
SELECT id, event_id, payload 
FROM outbox 
WHERE status = 'PENDING' 
ORDER BY created_at ASC 
LIMIT 100 
FOR UPDATE SKIP LOCKED;
```
- **`FOR UPDATE SKIP LOCKED`** allows multiple instances of the Outbox Poller service to run concurrently without locking the same rows. Each poller node processes a distinct batch of outbox rows, allowing database throughput to scale linearly with the number of poller processes.

---

## 5. Redis Scaling (Rate Limiting & Cache)

Redis handles high-frequency reads (API keys, Tenant configurations) and writes (idempotency checks, token buckets).

- **Cache-Aside Pattern**: Tenant configs are cached with a 5-minute TTL. This prevents database hits on every event validation request.
- **Atomic Operations**: Redis rate limit evaluations use a single, compiled Lua script executing in-memory, avoiding round-trip latency and distributed locks.
- **Cluster Deployment**: For environments exceeding 20,000 eps, Redis ElastiCache is configured in **Cluster Mode** with 3 master shards, partitioning keys using `{tenant_id}` hashtags to guarantee that rate-limiting commands for a single tenant hash to the same Redis node.
