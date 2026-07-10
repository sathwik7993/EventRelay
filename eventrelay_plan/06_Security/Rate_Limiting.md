# EventRelay — Ingestion & Tenant Rate Limiting

This document details the design and implementation of the rate-limiting system in EventRelay, protecting the ingestion API and ensuring tenant resource isolation.

---

## 1. Rate Limiting Strategy: Token Bucket

EventRelay uses the **Token Bucket algorithm** to enforce rate limits.

```
       [ Rate Limit Token Refill Rate ]
                     │
                     ▼
             ┌───────────────┐
             │  Token Bucket │ (Max Capacity)
             └───────┬───────┘
                     │ (Request arrives: consumes token)
                     ▼
       ┌───────────────────────────┐
       │   Token Available?        │
       └─────┬───────────────┬─────┘
             │ (Yes)         │ (No)
             ▼               ▼
      [ Allow Request ]   [ Reject: HTTP 429 ]
```

- **Capacity**: The maximum burst size allowed for a tenant.
- **Refill Rate**: The constant rate at which tokens are added back to the bucket (e.g., 100 tokens per second).
- **Redis Storage**: For high-performance evaluation, rate limiting state is stored in Redis.

---

## 2. Redis Token Bucket Lua Script

To prevent race conditions under high concurrency, token checking and consuming are executed as a single atomic operation using a Redis Lua script:

```lua
-- KEYS[1]: Token bucket rate limit key (e.g., "rate:tenant-123")
-- ARGV[1]: Max bucket capacity
-- ARGV[2]: Refill rate per millisecond
-- ARGV[3]: Request time (current epoch millisecond)
-- ARGV[4]: Tokens to consume (default 1)

local bucket = redis.call('hgetall', KEYS[1])
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

local last_update = now
local tokens = capacity

if #bucket > 0 then
    for i = 1, #bucket, 2 do
        if bucket[i] == 'last_update' then
            last_update = tonumber(bucket[i+1])
        elseif bucket[i] == 'tokens' then
            tokens = tonumber(bucket[i+1])
        end
    end
    -- Calculate refilled tokens based on time elapsed
    local elapsed = now - last_update
    tokens = math.min(capacity, tokens + (elapsed * refill_rate))
else
    -- Bucket doesn't exist, initialize
    redis.call('hset', KEYS[1], 'capacity', capacity)
end

if tokens >= requested then
    tokens = tokens - requested
    redis.call('hset', KEYS[1], 'tokens', tokens, 'last_update', now)
    return 1 -- Allowed
else
    return 0 -- Rejected
end
```

---

## 3. Rate Limit Response Headers

When EventRelay validates incoming requests, it attaches RFC-compliant headers to the response:

| Header | Description |
|--------|-------------|
| `X-RateLimit-Limit` | The maximum number of requests allowed in the current window (e.g., `100`). |
| `X-RateLimit-Remaining` | The number of tokens remaining in the bucket. |
| `X-RateLimit-Reset` | The number of seconds remaining until the bucket refills to full capacity. |

If a tenant exceeds their limit, EventRelay returns `429 Too Many Requests` with a JSON body and a `Retry-After` header:

```json
{
  "error": {
    "code": "TENANT_RATE_LIMIT_EXCEEDED",
    "message": "You have exceeded your request rate limit of 100 requests/second. Please slow down."
  }
}
```
