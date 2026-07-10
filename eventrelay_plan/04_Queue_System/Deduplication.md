# EventRelay — Message Deduplication

This document details the deduplication strategy of the EventRelay queue system, combining Redis caches, database primary keys, and SQS FIFO options to guarantee at-least-once delivery while minimizing duplicate processing.

---

## 1. The Deduplication Lifecycle

Deduplication occurs at both the Ingestion layer (inbound) and the Webhook Dispatcher layer (outbound):

```
[ Incoming Event ] ──► [ Redis Deduplication Cache ] ──► [ PostgreSQL Unique Constraints ]
                              │
                              ▼ (If Duplicate Detected)
                       [ Return Cached HTTP 202 ]
```

1. **Client Submission**: Client sends an event with an `X-Idempotency-Key` and `X-Tenant-Id` header.
2. **Redis Cache Verification**: The ingestion service checks if the key `dedup:{tenant_id}:{idempotency_key}` exists in Redis. If found, it returns the cached HTTP response immediately without processing.
3. **Database Guard**: If the Redis key has expired or Redis is down, the database constraint `unique_tenant_id_idempotency_key` acts as a fail-safe. If an insert fails due to constraint violation, the system fetches the existing event status and returns it.

---

## 2. Ingestion-Path Deduplication (Redis Lua Script)

To ensure thread-safe deduplication under high concurrency, EventRelay uses Redis atomic operations:

```java
@Service
@RequiredArgsConstructor
public class IdempotencyValidator {

    private final RedisTemplate<String, String> redisTemplate;
    private static final String DEDUP_KEY_PREFIX = "dedup:";
    private static final long DEDUP_TTL_SECONDS = 86400; // 24 Hours

    public boolean isDuplicate(String tenantId, String idempotencyKey) {
        String key = DEDUP_KEY_PREFIX + tenantId + ":" + idempotencyKey;
        
        // Use Redis SETNX (Set if Not Exists) with a TTL
        Boolean success = redisTemplate.opsForValue().setIfAbsent(
            key, 
            "PROCESSING", 
            Duration.ofSeconds(DEDUP_TTL_SECONDS)
        );
        
        return Boolean.FALSE.equals(success);
    }
    
    public void recordSuccess(String tenantId, String idempotencyKey, String responseBody) {
        String key = DEDUP_KEY_PREFIX + tenantId + ":" + idempotencyKey;
        // Save the response payload for future duplicate calls
        redisTemplate.opsForValue().set(
            key, 
            responseBody, 
            Duration.ofSeconds(DEDUP_TTL_SECONDS)
        );
    }
}
```

---

## 3. Queue-Path Deduplication (SQS FIFO Options)

While EventRelay uses SQS Standard queues by default for high throughput, some tenants require strict order and zero duplicates:

- **SQS FIFO Deduplication**: For subscriptions that require it, messages are sent to an SQS FIFO queue with a `MessageDeduplicationId` set to a SHA-256 hash of the payload, combined with a `MessageGroupId` matching `{tenant_id}:{subscription_id}`.
- **5-Minute Deduplication Window**: AWS SQS FIFO queues automatically deduplicate messages sent with the same `MessageDeduplicationId` within a rolling 5-minute window.

---

## 4. Consumer-Side Deduplication

Even with server-side deduplication, distributed systems can experience network failures that trigger retries on successfully received webhooks. EventRelay provides consumers with headers to perform deduplication:

- **`X-EventRelay-Delivery-Id`**: A unique UUID generated for every single delivery attempt.
- **`X-EventRelay-Event-Id`**: A persistent UUID assigned to the event upon ingestion. This remains identical across all retries.
- **Deduplication Strategy for Webhook Receivers**:
  - The receiver should store processed `X-EventRelay-Event-Id` values in a local database or Redis cache with a 24-hour TTL.
  - If a request arrives with an already-processed event ID, the receiver should immediately return an HTTP `200 OK` (acknowledging receipt) without re-processing the business logic.
