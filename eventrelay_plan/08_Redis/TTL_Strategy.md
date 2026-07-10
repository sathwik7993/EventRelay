# TTL Strategy for All Redis Keys

## Overview

Every key stored in EventRelay's Redis instances has an explicit **Time-To-Live (TTL)** policy. TTLs serve three purposes:

1. **Memory management** — Automatic cleanup prevents unbounded growth.
2. **Data freshness** — Cached data is guaranteed to be at most `TTL` seconds stale.
3. **Security** — Sensitive data (tokens, sessions) is automatically purged after a defined window.

> [!IMPORTANT]
> **No key in EventRelay is stored without a TTL.** Keys without TTL are considered a production bug. The Redis eviction policy (`allkeys-lru`) provides a safety net but should never be the primary cleanup mechanism.

---

## TTL Reference Table

| Key Pattern                         | TTL            | Type          | Purpose                                     |
|-------------------------------------|----------------|---------------|---------------------------------------------|
| `dedup:{tenantId}:{idempotencyKey}` | 24 hours       | Deduplication | Reject duplicate event submissions          |
| `delivery:{eventId}:{endpointId}`   | 7 days         | Deduplication | Prevent duplicate webhook deliveries        |
| `rate:{tenantId}`                   | 60 seconds     | Rate Limiting | Token bucket state for per-tenant limiting  |
| `rate:{tenantId}:{endpointId}`      | 60 seconds     | Rate Limiting | Token bucket state for per-endpoint limiting|
| `tenant:{tenantId}:config`          | 5 minutes      | Cache         | Tenant configuration cache                  |
| `sub:{tenantId}:{eventType}`        | 5 minutes      | Cache         | Subscription lookup cache                   |
| `apikey:{hashedKey}`                | 10 minutes     | Cache         | API key → tenant mapping cache              |
| `endpoint:{endpointId}`            | 5 minutes      | Cache         | Endpoint details cache                      |
| `ratelimit:config:{tenantId}`      | 5 minutes      | Cache         | Rate limit configuration cache              |
| `cb:{endpointId}`                  | Configurable   | Circuit Breaker| Circuit breaker state for endpoints        |
| `jwt:blacklist:{jti}`              | Match token exp| Security      | Blacklisted/revoked JWT tokens              |
| `session:{sessionId}`              | 30 minutes     | Session       | Admin dashboard session data                |
| `lock:{resource}`                  | 30 seconds     | Dist. Lock    | Distributed lock for coordination           |
| `lock:outbox-poller`               | 60 seconds     | Dist. Lock    | Outbox poller leader election lock          |

---

## TTL Rationale — Detailed Breakdown

### 1. Idempotency / Dedup Keys — 24 Hours

```
Key:  dedup:{tenantId}:{idempotencyKey}
TTL:  86,400 seconds (24 hours)
```

**Why 24 hours:**
- Covers the typical retry window of webhook clients (most retry for 4–8 hours)
- Aligns with industry standards (Stripe idempotency: 24h, Svix: 24h)
- Provides a 3× safety margin over the expected retry duration
- Long enough for batch processing jobs that run daily

**Trade-offs:**
- Shorter TTL (1h) → Risks accepting duplicates if client retries after hours
- Longer TTL (72h) → Higher memory usage without proportional benefit

```mermaid
gantt
    title Idempotency Key Lifecycle
    dateFormat HH:mm
    section Event Lifecycle
    Event received           :a1, 00:00, 1h
    Client retry window      :a2, 00:00, 8h
    Safety margin            :a3, 08:00, 16h
    Key expires (24h TTL)    :crit, a4, 24:00, 1h
```

### 2. Rate Limit Buckets — 60 Seconds

```
Key:  rate:{tenantId}
TTL:  60 seconds
```

**Why 60 seconds:**
- Token bucket state only needs to persist while the tenant is actively sending requests
- Inactive tenants' buckets auto-expire, recovering memory
- On next request after expiry, the bucket re-initializes at max capacity (which is correct — the tenant earned refills during inactivity)
- The Lua script renews the TTL on every request, so active tenants never lose state

**Why NOT shorter (10s):**
- Could cause bucket state to be lost between bursts, artificially granting extra tokens

**Why NOT longer (5m):**
- Wastes memory for tenants that send a single burst and go silent

### 3. Tenant Config Cache — 5 Minutes

```
Key:  tenant:{tenantId}:config
TTL:  300 seconds (5 minutes)
```

**Why 5 minutes:**
- Tenant configuration changes infrequently (typically admin operations)
- 5 minutes bounds the maximum staleness after a config change
- Combined with event-driven invalidation, actual staleness is typically <1 second
- Short enough that disabled tenants stop working within minutes
- Long enough to provide meaningful cache hit rates (>95% for active tenants)

**Freshness guarantee:**

```
Without event-driven invalidation: max staleness = 5 minutes
With event-driven invalidation:    max staleness = 0 seconds (immediate eviction)
```

### 4. Subscription Cache — 5 Minutes

```
Key:  sub:{tenantId}:{eventType}
TTL:  300 seconds (5 minutes)
```

**Why 5 minutes:**
- Same rationale as tenant config cache
- Subscriptions change less frequently than events arrive
- Critical for performance — subscription lookups happen for **every event**
- A cache miss triggers a JOIN query across subscriptions + endpoints tables

### 5. API Key Lookup Cache — 10 Minutes

```
Key:  apikey:{hashedKey}
TTL:  600 seconds (10 minutes)
```

**Why 10 minutes (longer than other caches):**
- API keys change extremely rarely (creation/rotation/revocation)
- Every single API request performs a key lookup — higher TTL reduces DB load significantly
- Key revocation triggers immediate cache eviction (event-driven), so security is not compromised
- The hashed key prevents credential exposure even if Redis is compromised

### 6. Circuit Breaker State — Configurable

```
Key:  cb:{endpointId}
TTL:  Configurable (default: 5 minutes for OPEN state)
```

**Configuration:**

| Circuit State | TTL            | Behavior                                                  |
|---------------|----------------|-----------------------------------------------------------|
| CLOSED        | No key exists  | Normal operation — no Redis key needed                    |
| OPEN          | 5 minutes      | Requests to this endpoint are blocked                     |
| HALF_OPEN     | 30 seconds     | Allow a single probe request to test recovery             |

```java
public enum CircuitBreakerTtl {
    OPEN(Duration.ofMinutes(5)),
    HALF_OPEN(Duration.ofSeconds(30));

    private final Duration ttl;

    CircuitBreakerTtl(Duration ttl) { this.ttl = ttl; }
    public Duration getTtl() { return ttl; }
}
```

**Why configurable:**
- Different endpoints have different recovery profiles
- A flaky endpoint might need 1 minute; a completely down endpoint needs 10 minutes
- Allows operators to tune per-endpoint without code changes

### 7. JWT Blacklist — Match Token Expiry

```
Key:  jwt:blacklist:{jti}
TTL:  token_expiry_time - current_time
```

**Why match token expiry:**
- A revoked JWT only needs to be blacklisted until it would have expired naturally
- After the original expiry, the token is invalid regardless of the blacklist
- This prevents the blacklist from growing indefinitely

```java
public void blacklistToken(String jti, Instant tokenExpiry) {
    Duration ttl = Duration.between(Instant.now(), tokenExpiry);
    if (ttl.isPositive()) {
        String key = "jwt:blacklist:" + jti;
        redisTemplate.opsForValue().set(key, "revoked", ttl);
    }
    // If token is already expired, no need to blacklist
}

public boolean isBlacklisted(String jti) {
    return Boolean.TRUE.equals(
        redisTemplate.hasKey("jwt:blacklist:" + jti));
}
```

### 8. Session Data — 30 Minutes

```
Key:  session:{sessionId}
TTL:  1,800 seconds (30 minutes)
```

**Why 30 minutes:**
- Standard session timeout for admin dashboard access
- Balances security (shorter = less exposure) with usability (longer = fewer re-logins)
- Sliding expiration: TTL resets on each request, so active sessions persist

```java
// Sliding session TTL renewal
public void touchSession(String sessionId) {
    String key = "session:" + sessionId;
    redisTemplate.expire(key, Duration.ofMinutes(30));
}
```

### 9. Distributed Locks — 30–60 Seconds

```
Key:  lock:{resource}
TTL:  30 seconds (default), 60 seconds (outbox poller)
```

**Why short TTLs:**
- Locks must auto-expire if the holder crashes without releasing
- 30 seconds is long enough for most critical sections
- Outbox poller leader election uses 60 seconds with a 20-second renewal interval
- Never use TTL > 5 minutes for locks — it blocks other processes too long on holder failure

---

## Memory Estimation

### Per-Key Memory Overhead

Redis stores each key with overhead for the key string, value, expiry metadata, and internal data structures:

```
Base overhead per key:    ~70 bytes (Redis hash table entry + SDS headers)
Average key length:       ~40 bytes
Average value length:     ~50 bytes (simple), ~2,000 bytes (cached JSON)
Expiry metadata:          ~16 bytes
```

### Estimation by Key Type

| Key Type              | Est. Active Keys | Avg Size (bytes) | Total Memory   |
|-----------------------|------------------|-------------------|----------------|
| Dedup keys            | 864,000          | 120               | **99 MB**      |
| Delivery dedup keys   | 500,000          | 100               | **48 MB**      |
| Rate limit buckets    | 10,000           | 150               | **1.4 MB**     |
| Tenant config cache   | 5,000            | 2,100             | **10 MB**      |
| Subscription cache    | 50,000           | 5,100             | **243 MB**     |
| API key cache         | 5,000            | 500               | **2.4 MB**     |
| Endpoint cache        | 20,000           | 1,100             | **21 MB**      |
| Circuit breaker state | 500              | 150               | **0.07 MB**    |
| JWT blacklist         | 1,000            | 100               | **0.1 MB**     |
| Session data          | 200              | 5,100             | **1 MB**       |
| Distributed locks     | 10               | 120               | **0.001 MB**   |
| **TOTAL**             | **~1,455,710**   |                   | **~426 MB**    |

### Assumptions

- **Dedup keys**: 10,000 events/hour × 24h TTL = 240,000 keys (scaled 3.6× for padding)
- **Subscription cache**: 5,000 tenants × 10 event types average = 50,000 keys
- **Delivery dedup**: Based on 3,000 deliveries/hour × 7-day TTL, with overlap

> [!TIP]
> For a production deployment processing 10,000 events/second, budget **2–4 GB** for Redis. An `r6g.large` ElastiCache instance (13.07 GB) provides ample headroom.

---

## Memory Configuration

### `application.yml` — TTL Defaults

```yaml
eventrelay:
  redis:
    ttl:
      dedup-seconds: 86400           # 24 hours
      delivery-dedup-seconds: 604800 # 7 days
      rate-limit-seconds: 60         # 1 minute
      tenant-cache-seconds: 300      # 5 minutes
      subscription-cache-seconds: 300 # 5 minutes
      api-key-cache-seconds: 600     # 10 minutes
      endpoint-cache-seconds: 300    # 5 minutes
      circuit-breaker-open-seconds: 300   # 5 minutes
      circuit-breaker-halfopen-seconds: 30
      session-seconds: 1800          # 30 minutes
      lock-default-seconds: 30
      lock-outbox-poller-seconds: 60
```

### TTL Properties Class

```java
package com.eventrelay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "eventrelay.redis.ttl")
public class RedisTtlProperties {

    private long dedupSeconds = 86400;
    private long deliveryDedupSeconds = 604800;
    private long rateLimitSeconds = 60;
    private long tenantCacheSeconds = 300;
    private long subscriptionCacheSeconds = 300;
    private long apiKeyCacheSeconds = 600;
    private long endpointCacheSeconds = 300;
    private long circuitBreakerOpenSeconds = 300;
    private long circuitBreakerHalfopenSeconds = 30;
    private long sessionSeconds = 1800;
    private long lockDefaultSeconds = 30;
    private long lockOutboxPollerSeconds = 60;

    // Getters and setters for all fields
    public long getDedupSeconds() { return dedupSeconds; }
    public void setDedupSeconds(long v) { this.dedupSeconds = v; }
    public long getDeliveryDedupSeconds() { return deliveryDedupSeconds; }
    public void setDeliveryDedupSeconds(long v) { this.deliveryDedupSeconds = v; }
    public long getRateLimitSeconds() { return rateLimitSeconds; }
    public void setRateLimitSeconds(long v) { this.rateLimitSeconds = v; }
    public long getTenantCacheSeconds() { return tenantCacheSeconds; }
    public void setTenantCacheSeconds(long v) { this.tenantCacheSeconds = v; }
    public long getSubscriptionCacheSeconds() { return subscriptionCacheSeconds; }
    public void setSubscriptionCacheSeconds(long v) { this.subscriptionCacheSeconds = v; }
    public long getApiKeyCacheSeconds() { return apiKeyCacheSeconds; }
    public void setApiKeyCacheSeconds(long v) { this.apiKeyCacheSeconds = v; }
    public long getEndpointCacheSeconds() { return endpointCacheSeconds; }
    public void setEndpointCacheSeconds(long v) { this.endpointCacheSeconds = v; }
    public long getCircuitBreakerOpenSeconds() { return circuitBreakerOpenSeconds; }
    public void setCircuitBreakerOpenSeconds(long v) { this.circuitBreakerOpenSeconds = v; }
    public long getCircuitBreakerHalfopenSeconds() { return circuitBreakerHalfopenSeconds; }
    public void setCircuitBreakerHalfopenSeconds(long v) { this.circuitBreakerHalfopenSeconds = v; }
    public long getSessionSeconds() { return sessionSeconds; }
    public void setSessionSeconds(long v) { this.sessionSeconds = v; }
    public long getLockDefaultSeconds() { return lockDefaultSeconds; }
    public void setLockDefaultSeconds(long v) { this.lockDefaultSeconds = v; }
    public long getLockOutboxPollerSeconds() { return lockOutboxPollerSeconds; }
    public void setLockOutboxPollerSeconds(long v) { this.lockOutboxPollerSeconds = v; }
}
```

---

## Eviction Policy

### Configuration

```redis
# redis.conf
maxmemory 4gb
maxmemory-policy allkeys-lru
```

### Why `allkeys-lru`?

| Policy              | Behavior                                               | Suitable? |
|---------------------|--------------------------------------------------------|-----------|
| `noeviction`        | Returns error when memory is full                      | ❌ Risky   |
| `volatile-lru`      | Evicts only keys with TTL set (LRU among those)        | ⚠️ OK     |
| `allkeys-lru`       | **Evicts least recently used keys regardless of TTL**  | ✅ Best   |
| `volatile-ttl`      | Evicts keys with shortest remaining TTL                | ⚠️ OK     |
| `allkeys-random`    | Randomly evicts any key                                | ❌ Poor    |

**`allkeys-lru` is preferred because:**

1. **All keys have TTL** in EventRelay, so `volatile-lru` would behave identically — but `allkeys-lru` provides a safety net if a key is accidentally created without TTL.
2. LRU eviction naturally targets stale, low-value keys first.
3. Under memory pressure, the system degrades gracefully by evicting cold cache entries before hot ones.

> [!WARNING]
> If eviction kicks in, it means Redis is under memory pressure. This should trigger an alert — either scale the instance or reduce TTLs. Eviction is a **safety net**, not normal operation.

### Monitoring Eviction

```yaml
# Prometheus alert
groups:
  - name: redis_memory
    rules:
      - alert: RedisEvictionActive
        expr: redis_evicted_keys_total > 0
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Redis is evicting keys — memory pressure detected"

      - alert: RedisMemoryHigh
        expr: redis_memory_used_bytes / redis_memory_max_bytes > 0.85
        for: 10m
        labels:
          severity: critical
        annotations:
          summary: "Redis memory usage above 85% ({{ $value | humanizePercentage }})"
```

---

## TTL Enforcement Validation

Implement a startup check that verifies all Redis key creation paths include a TTL:

```java
package com.eventrelay.config;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Development-only aspect that warns if Redis keys are set without TTL.
 * This catches accidental immortal keys during development.
 */
@Aspect
@Component
@Profile("dev")
public class RedisTtlEnforcementAspect {

    private static final Logger log = LoggerFactory.getLogger(
        RedisTtlEnforcementAspect.class);

    @Around("execution(* org.springframework.data.redis.core.ValueOperations.set(..)) && args(key, value)")
    public Object checkTtl(ProceedingJoinPoint joinPoint,
                           Object key, Object value) throws Throwable {
        // Two-argument set() has no TTL — warn
        log.warn("Redis SET without TTL detected for key '{}'. " +
            "All keys must have explicit TTLs in production.", key);
        return joinPoint.proceed();
    }
}
```

---

## Production Considerations

1. **TTL Jitter**: Add ±10% random jitter to TTLs to prevent synchronized expiry (cache stampede). For example, a 5-minute TTL becomes 270–330 seconds:

   ```java
   private Duration withJitter(Duration baseTtl) {
       long baseSeconds = baseTtl.getSeconds();
       long jitter = (long) (baseSeconds * 0.1 * (Math.random() * 2 - 1));
       return Duration.ofSeconds(baseSeconds + jitter);
   }
   ```

2. **TTL Monitoring Dashboard**: Track `redis_keyspace_hits` vs `redis_keyspace_misses` per key prefix to validate TTL effectiveness. Low hit rates suggest TTLs are too short.

3. **Lazy Expiration**: Redis expires keys lazily (on access) and actively (periodic sampling). Under heavy load, some expired keys may persist briefly. This is expected behavior and doesn't affect correctness.

4. **Key Scan for TTL Audit**: Periodically scan for keys without TTL in non-production environments:

   ```redis
   # Find keys without TTL (should return empty in EventRelay)
   redis-cli --scan --pattern '*' | while read key; do
     ttl=$(redis-cli TTL "$key")
     if [ "$ttl" -eq "-1" ]; then
       echo "NO TTL: $key"
     fi
   done
   ```

5. **Cross-Region Replication**: If using ElastiCache Global Datastore, TTLs replicate with the keys. Ensure TTLs are consistent across regions.
