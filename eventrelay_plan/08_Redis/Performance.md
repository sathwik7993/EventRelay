# EventRelay — Redis Performance Tuning

This document outlines the performance configurations, Lettuce pool tuning, key naming conventions, and cluster guidelines used to optimize Redis in EventRelay.

---

## 1. Key Naming & TTL Strategy

To keep memory footprint low and avoid fragmentation, EventRelay enforces key structures:

| Namespace | Key Pattern | Data Structure | TTL | Rationale |
|-----------|-------------|----------------|-----|-----------|
| **Deduplication** | `dedup:{tenantId}:{key}` | String | `24 Hours` | Prevents double processing within a day. |
| **Rate Limit** | `rate:{tenantId}` | Hash | `1 Minute` | Tracks token bucket details. |
| **Cache Config** | `tenant:{id}:config` | String (JSON) | `5 Minutes` | Avoids database lookups on every request. |
| **Blacklist** | `jwt:blacklist:{tokenId}` | String | Matches JWT expiry | Tracks revoked security tokens. |

---

## 2. Lettuce Connection Pool Configuration

EventRelay uses the Lettuce Redis client, optimized with connection pooling using **Apache Commons Pool 2**:

```yaml
spring:
  data:
    redis:
      host: eventrelay-redis.cache.amazonaws.com
      port: 6379
      lettuce:
        pool:
          max-active: 50     # Max concurrent connections to Redis
          max-idle: 20       # Max idle connections
          min-idle: 5        # Min active connections
          max-wait: 1000ms   # Max block time for connection
```

- **Connection Reuse**: Lettuce is thread-safe and shares a single connection for standard operations. The pool is dedicated to blocking commands and high-throughput transaction pipelines.

---

## 3. Pipelining & Lua Script Optimization

- **Pipelining**: When updating batch statuses, workers use Redis pipelines. This groups commands and executes them in a single network round-trip, increasing throughput from 5,000 to 80,000 operations per second.
- **Eviction Mode**: ElastiCache Redis is configured with `maxmemory-policy: allkeys-lru`. If memory is exhausted, Redis evicts the least recently used cached configurations first, preserving token buckets and deduplication keys which are marked as non-evictable.
- **Compression**: JSON structures cached in Redis are serialized using Jackson with gzip compression if the payload size exceeds `10 KB`.
