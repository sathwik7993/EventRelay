# EventRelay — Functional Requirements

> This document defines the comprehensive functional requirements for EventRelay, organized by subsystem and prioritized using MoSCoW (Must, Should, Could, Won't).

---

## MoSCoW Priority Legend

| Priority | Meaning | Phase |
|----------|---------|-------|
| **Must** | Non-negotiable for launch. System cannot function without it. | Phase 1-2 |
| **Should** | Important for production readiness. Expected by users. | Phase 2-3 |
| **Could** | Desirable enhancement. Delivers value but not critical. | Phase 3-5 |
| **Won't** (this version) | Out of scope for v1. Documented for future consideration. | Beyond v1 |

---

## FR-1: Tenant Management

Tenants represent organizations or applications that use EventRelay to deliver webhooks to their consumers.

### Requirements

| ID | Requirement | Priority | Notes |
|----|-------------|----------|-------|
| FR-1.1 | Create a new tenant with name, description, and configuration | **Must** | `POST /api/v1/tenants` |
| FR-1.2 | Retrieve tenant details by ID | **Must** | `GET /api/v1/tenants/{id}` |
| FR-1.3 | Update tenant name, description, and configuration | **Must** | `PUT /api/v1/tenants/{id}` |
| FR-1.4 | Deactivate a tenant (soft delete) | **Must** | Sets `active=false`, stops new deliveries |
| FR-1.5 | List all tenants with pagination and filtering | **Should** | `GET /api/v1/tenants?status=active&limit=50` |
| FR-1.6 | Generate API key for tenant authentication | **Must** | Returns key once; stored as hash |
| FR-1.7 | Rotate API key with grace period (old key valid for 24h) | **Should** | `POST /api/v1/tenants/{id}/api-keys/rotate` |
| FR-1.8 | Generate HMAC signing secret for tenant | **Must** | Auto-generated on tenant creation |
| FR-1.9 | Rotate signing secret with dual-key support | **Should** | Both old and new secrets valid during transition |
| FR-1.10 | Configure per-tenant rate limits | **Should** | Part of tenant configuration object |
| FR-1.11 | Configure per-tenant retry policy | **Could** | Override default retry schedule |
| FR-1.12 | Hard delete tenant and all associated data | **Won't** | Regulatory/compliance implications |

### Tenant Data Model

```json
{
  "tenant_id": "ten_a1b2c3d4",
  "name": "Acme Payments",
  "description": "Acme's payment notification system",
  "status": "ACTIVE",
  "config": {
    "rate_limit_rps": 100,
    "max_payload_bytes": 262144,
    "retry_policy": "DEFAULT",
    "webhook_timeout_ms": 30000
  },
  "api_key_hash": "$2a$12$...",
  "signing_secret": "whsec_...",
  "created_at": "2026-07-10T04:00:00Z",
  "updated_at": "2026-07-10T04:00:00Z"
}
```

### API Specification

```
POST   /api/v1/tenants                          → 201 Created
GET    /api/v1/tenants/{tenant_id}               → 200 OK
PUT    /api/v1/tenants/{tenant_id}               → 200 OK
DELETE /api/v1/tenants/{tenant_id}               → 204 No Content (soft delete)
GET    /api/v1/tenants                           → 200 OK (paginated list)
POST   /api/v1/tenants/{tenant_id}/api-keys/rotate → 200 OK
```

---

## FR-2: Subscription Management

Subscriptions define which event types should be delivered to which URLs for each tenant.

### Requirements

| ID | Requirement | Priority | Notes |
|----|-------------|----------|-------|
| FR-2.1 | Create a subscription with target URL and event types | **Must** | `POST /api/v1/tenants/{id}/subscriptions` |
| FR-2.2 | Retrieve subscription details by ID | **Must** | `GET /api/v1/subscriptions/{id}` |
| FR-2.3 | Update subscription target URL or event types | **Must** | `PUT /api/v1/subscriptions/{id}` |
| FR-2.4 | Delete a subscription | **Must** | Cancels pending deliveries |
| FR-2.5 | List subscriptions for a tenant | **Must** | `GET /api/v1/tenants/{id}/subscriptions` |
| FR-2.6 | Validate target URL on creation (HTTPS required, reachable) | **Should** | Send `ping` event on creation |
| FR-2.7 | Support wildcard event type matching (`order.*`) | **Should** | Glob-style pattern matching |
| FR-2.8 | Support catch-all subscription (`*`) | **Could** | Receives all event types |
| FR-2.9 | Enable/disable subscription without deleting | **Should** | `PATCH /api/v1/subscriptions/{id}` |
| FR-2.10 | Subscription health status (active, degraded, failing) | **Could** | Based on recent delivery success rate |
| FR-2.11 | Custom HTTP headers per subscription | **Could** | E.g., authorization headers |
| FR-2.12 | Content-type negotiation (JSON, form-encoded) | **Won't** | JSON only for v1 |

### Subscription Data Model

```json
{
  "subscription_id": "sub_x1y2z3",
  "tenant_id": "ten_a1b2c3d4",
  "target_url": "https://merchant.com/webhooks",
  "event_types": ["payment.created", "payment.completed", "payment.failed"],
  "status": "ACTIVE",
  "metadata": {
    "description": "Merchant payment notifications",
    "environment": "production"
  },
  "created_at": "2026-07-10T04:00:00Z",
  "updated_at": "2026-07-10T04:00:00Z"
}
```

### Validation Rules

| Rule | Enforcement |
|------|-------------|
| Target URL must be HTTPS | Reject HTTP URLs (400 Bad Request) |
| Target URL must be valid URI | Parse and validate format |
| Event types must be non-empty | At least one event type required |
| No duplicate subscriptions | Same tenant + URL + event type combo is rejected |
| Maximum 50 subscriptions per tenant | Configurable limit |
| URL must not point to localhost/private IPs | SSRF prevention |

---

## FR-3: Event Ingestion

The ingestion API accepts events from tenants and guarantees they are persisted for delivery.

### Requirements

| ID | Requirement | Priority | Notes |
|----|-------------|----------|-------|
| FR-3.1 | Accept events via REST API | **Must** | `POST /api/v1/events` |
| FR-3.2 | Authenticate requests using API key (Bearer token) | **Must** | `Authorization: Bearer <api_key>` |
| FR-3.3 | Validate event payload schema (type, data required) | **Must** | Return 400 with details on failure |
| FR-3.4 | Support idempotency keys to prevent duplicate ingestion | **Must** | `Idempotency-Key` header |
| FR-3.5 | Write event to PostgreSQL transactionally (outbox pattern) | **Must** | Single transaction: event + outbox |
| FR-3.6 | Return 202 Accepted with event ID on success | **Must** | Async processing, not synchronous delivery |
| FR-3.7 | Reject events exceeding max payload size (default 256KB) | **Must** | Return 413 Payload Too Large |
| FR-3.8 | Enforce per-tenant rate limiting on ingestion | **Should** | Token bucket via Redis |
| FR-3.9 | Support batch event ingestion (up to 100 events) | **Could** | `POST /api/v1/events/batch` |
| FR-3.10 | Assign monotonic sequence number per tenant | **Should** | For consumer-side ordering |
| FR-3.11 | Validate event type exists (or auto-register) | **Could** | Configurable behavior |
| FR-3.12 | Content-type enforcement (application/json only) | **Must** | Return 415 for other types |

### Event Payload Schema

```json
// Request
POST /api/v1/events
Authorization: Bearer api_key_tenant_abc123
Idempotency-Key: idk_20260710_pay_001
Content-Type: application/json

{
  "event_type": "payment.completed",
  "data": {
    "payment_id": "pay_001",
    "amount": 9999,
    "currency": "USD",
    "customer_id": "cust_123"
  },
  "metadata": {
    "source": "payment-service",
    "trace_id": "trace_abc"
  }
}

// Response
HTTP/1.1 202 Accepted

{
  "event_id": "evt_f7g8h9",
  "event_type": "payment.completed",
  "tenant_id": "ten_a1b2c3d4",
  "sequence_number": 14523,
  "status": "ACCEPTED",
  "created_at": "2026-07-10T04:00:00.123Z",
  "request_id": "req_m1n2o3"
}
```

### Error Responses

| Status | Code | Scenario |
|--------|------|----------|
| 400 | `INVALID_PAYLOAD` | Missing required fields, malformed JSON |
| 401 | `UNAUTHORIZED` | Missing or invalid API key |
| 409 | `DUPLICATE_EVENT` | Idempotency key already processed |
| 413 | `PAYLOAD_TOO_LARGE` | Event data exceeds 256KB limit |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Content-Type is not application/json |
| 422 | `UNKNOWN_EVENT_TYPE` | Event type not registered (if strict mode enabled) |
| 429 | `RATE_LIMIT_EXCEEDED` | Per-tenant ingestion rate limit hit |
| 500 | `INTERNAL_ERROR` | Server error — include request_id for debugging |

---

## FR-4: Delivery Engine

The delivery engine dispatches webhook events to subscriber endpoints via HTTP POST.

### Requirements

| ID | Requirement | Priority | Notes |
|----|-------------|----------|-------|
| FR-4.1 | Deliver events via HTTP POST to subscriber target URLs | **Must** | Content-Type: application/json |
| FR-4.2 | Include HMAC-SHA256 signature in delivery headers | **Must** | `X-EventRelay-Signature` header |
| FR-4.3 | Include timestamp in delivery headers | **Must** | `X-EventRelay-Timestamp` header |
| FR-4.4 | Include event ID in delivery headers | **Must** | `X-Event-ID` header |
| FR-4.5 | Include event type in delivery headers | **Must** | `X-Event-Type` header |
| FR-4.6 | Include delivery attempt number in headers | **Should** | `X-EventRelay-Attempt` header |
| FR-4.7 | Consider HTTP 2xx as successful delivery | **Must** | Any 2xx status code |
| FR-4.8 | Consider HTTP 4xx (except 429) as permanent failure | **Should** | Skip retry, move to DLQ |
| FR-4.9 | Consider HTTP 429 as rate limit — retry with backoff | **Must** | Respect `Retry-After` header if present |
| FR-4.10 | Consider HTTP 5xx as transient failure — retry | **Must** | Standard retry policy applies |
| FR-4.11 | Timeout after 30 seconds (configurable) | **Must** | Mark as timeout, retry |
| FR-4.12 | Do not follow HTTP redirects (3xx) | **Must** | Security: prevent SSRF |
| FR-4.13 | Record every delivery attempt with full metadata | **Must** | Status, latency, response code, error message |
| FR-4.14 | Support concurrent delivery to multiple subscribers | **Must** | Independent delivery per subscription |
| FR-4.15 | Circuit breaker per endpoint | **Should** | Open after N consecutive failures |
| FR-4.16 | Configurable User-Agent header | **Could** | `EventRelay/1.0` default |

### Delivery Headers

```http
POST /webhooks HTTP/1.1
Host: merchant.com
Content-Type: application/json
User-Agent: EventRelay/1.0
X-Event-ID: evt_f7g8h9
X-Event-Type: payment.completed
X-EventRelay-Signature: v1=K7gNU3sdo+OL0wNhqoVWhr3g6s1xYv72ol/pe/Unols=
X-EventRelay-Timestamp: 1720584000
X-EventRelay-Attempt: 1
X-Request-ID: dlv_p4q5r6

{
  "event_id": "evt_f7g8h9",
  "event_type": "payment.completed",
  "created_at": "2026-07-10T04:00:00.123Z",
  "data": {
    "payment_id": "pay_001",
    "amount": 9999,
    "currency": "USD"
  }
}
```

### Response Handling Matrix

| Response | Classification | Action |
|----------|---------------|--------|
| 200-299 | ✅ Success | Mark DELIVERED, delete SQS message |
| 301, 302, 307, 308 | ❌ Redirect | Mark FAILED, do not follow, log |
| 400, 401, 403, 404 | ❌ Permanent failure | Mark FAILED, move to DLQ (no retry) |
| 408 | ⚠️ Timeout (receiver-side) | Retry with backoff |
| 429 | ⚠️ Rate limited | Retry, respect `Retry-After` |
| 500, 502, 503, 504 | ⚠️ Transient failure | Retry with backoff |
| Connection timeout | ⚠️ Network failure | Retry with backoff |
| DNS failure | ⚠️ Network failure | Retry with backoff |
| TLS error | ❌ Configuration error | Retry (cert may be renewed) |

---

## FR-5: Retry Engine

The retry engine implements exponential backoff with jitter for failed delivery attempts.

### Requirements

| ID | Requirement | Priority | Notes |
|----|-------------|----------|-------|
| FR-5.1 | Retry failed deliveries with exponential backoff | **Must** | Base: 1s, multiplier: varies |
| FR-5.2 | Add random jitter to retry intervals (±20%) | **Must** | Prevents thundering herd |
| FR-5.3 | Cap maximum retry attempts (default: 8) | **Must** | Configurable per tenant |
| FR-5.4 | Cap maximum backoff interval (default: 4 hours) | **Must** | Prevent unbounded waits |
| FR-5.5 | Move to DLQ after max retries exhausted | **Must** | With full failure context |
| FR-5.6 | Track retry count and next retry time per event | **Must** | Queryable via API |
| FR-5.7 | Respect HTTP 429 `Retry-After` header | **Should** | Override backoff calculation |
| FR-5.8 | Skip retry for permanent failures (4xx except 429) | **Should** | Move directly to DLQ |
| FR-5.9 | Pause retries when circuit breaker is open | **Should** | Resume when half-open |
| FR-5.10 | Retry metrics per tenant and event type | **Should** | Prometheus counters |

### Retry Schedule (Default)

| Attempt | Delay (base) | Delay with Jitter (example) | Cumulative Time |
|---------|-------------|---------------------------|-----------------|
| 1 | Immediate | 0s | 0s |
| 2 | 1s | ~0.8-1.2s | ~1s |
| 3 | 5s | ~4-6s | ~6s |
| 4 | 30s | ~24-36s | ~36s |
| 5 | 5 min | ~4-6 min | ~6 min |
| 6 | 30 min | ~24-36 min | ~36 min |
| 7 | 1 hour | ~48-72 min | ~2 hours |
| 8 | 4 hours | ~3.2-4.8 hours | ~6 hours |
| **DLQ** | — | — | Max ~6 hours of retrying |

### Backoff Algorithm

```java
public Duration calculateBackoff(int attemptNumber) {
    // Predefined intervals
    long[] intervals = {0, 1, 5, 30, 300, 1800, 3600, 14400}; // seconds
    
    int index = Math.min(attemptNumber - 1, intervals.length - 1);
    long baseDelay = intervals[index];
    
    // Add ±20% jitter
    double jitter = 1.0 + (ThreadLocalRandom.current().nextDouble() * 0.4 - 0.2);
    long delayWithJitter = (long)(baseDelay * jitter);
    
    // Cap at max backoff
    long maxBackoff = 14400; // 4 hours
    return Duration.ofSeconds(Math.min(delayWithJitter, maxBackoff));
}
```

---

## FR-6: Dead-Letter Queue (DLQ)

The DLQ captures events that could not be delivered after all retry attempts are exhausted.

### Requirements

| ID | Requirement | Priority | Notes |
|----|-------------|----------|-------|
| FR-6.1 | Move events to DLQ after max retries exhausted | **Must** | With complete failure context |
| FR-6.2 | Store full event payload, all delivery attempt history | **Must** | For debugging |
| FR-6.3 | List DLQ events with pagination, filtering by tenant, type, date | **Must** | `GET /api/v1/dlq` |
| FR-6.4 | Inspect individual DLQ event with full delivery history | **Must** | `GET /api/v1/dlq/{id}` |
| FR-6.5 | Replay a single DLQ event | **Must** | `POST /api/v1/dlq/{id}/replay` |
| FR-6.6 | Bulk replay all DLQ events for a tenant | **Should** | `POST /api/v1/dlq/replay?tenant_id=xxx` |
| FR-6.7 | Purge DLQ events older than retention period | **Should** | Configurable (default 30 days) |
| FR-6.8 | Alert when DLQ depth exceeds threshold | **Should** | Prometheus alert rule |
| FR-6.9 | Move events to DLQ immediately for permanent failures (4xx) | **Should** | Bypass retry engine |
| FR-6.10 | DLQ event count per tenant via API | **Should** | `GET /api/v1/tenants/{id}/dlq/count` |
| FR-6.11 | Export DLQ events as JSON/CSV | **Could** | For offline analysis |

### DLQ Event Model

```json
{
  "dlq_entry_id": "dlq_a1b2c3",
  "event_id": "evt_f7g8h9",
  "tenant_id": "ten_a1b2c3d4",
  "subscription_id": "sub_x1y2z3",
  "event_type": "payment.completed",
  "target_url": "https://merchant.com/webhooks",
  "payload": { "...original event data..." },
  "failure_reason": "Max retries exhausted (8 attempts)",
  "last_http_status": 503,
  "last_error_message": "Service Unavailable",
  "total_attempts": 8,
  "first_attempt_at": "2026-07-10T04:00:00Z",
  "last_attempt_at": "2026-07-10T10:00:00Z",
  "dead_lettered_at": "2026-07-10T10:00:00Z",
  "delivery_attempts": [
    {
      "attempt_number": 1,
      "attempted_at": "2026-07-10T04:00:00Z",
      "http_status": 503,
      "latency_ms": 1234,
      "error": "Service Unavailable"
    },
    {
      "attempt_number": 2,
      "attempted_at": "2026-07-10T04:00:01Z",
      "http_status": 503,
      "latency_ms": 2345,
      "error": "Service Unavailable"
    }
  ]
}
```

---

## FR-7: Event Explorer and Dashboard

The dashboard provides visibility into event flow, delivery status, and system health.

### Requirements

| ID | Requirement | Priority | Notes |
|----|-------------|----------|-------|
| FR-7.1 | Query events by tenant, type, status, and date range | **Must** | `GET /api/v1/events` with filters |
| FR-7.2 | View event details including full payload | **Must** | `GET /api/v1/events/{id}` |
| FR-7.3 | View delivery attempts for an event | **Must** | `GET /api/v1/events/{id}/deliveries` |
| FR-7.4 | View delivery success/failure metrics per tenant | **Should** | Aggregated statistics |
| FR-7.5 | View real-time event throughput | **Should** | Prometheus + Grafana |
| FR-7.6 | View retry queue depth | **Should** | Per tenant and global |
| FR-7.7 | View DLQ depth and alert status | **Should** | Per tenant and global |
| FR-7.8 | Web-based dashboard UI | **Could** | Phase 3-4 |
| FR-7.9 | Event search by payload content | **Won't** | Requires full-text search infrastructure |

### Event Query API

```
GET /api/v1/events?tenant_id=ten_abc&event_type=payment.*&status=DELIVERED&since=2026-07-01&until=2026-07-10&limit=50&cursor=cur_xyz

Response:
{
  "events": [...],
  "pagination": {
    "cursor": "cur_next123",
    "has_more": true,
    "total_count": 1247
  }
}
```

---

## FR-8: Security

### Requirements

| ID | Requirement | Priority | Notes |
|----|-------------|----------|-------|
| FR-8.1 | Authenticate all API requests with API key | **Must** | Bearer token in Authorization header |
| FR-8.2 | Sign all webhook deliveries with HMAC-SHA256 | **Must** | Per-tenant signing secret |
| FR-8.3 | Include timestamp in signature for replay protection | **Must** | 5-minute tolerance window |
| FR-8.4 | Validate target URLs (HTTPS only, no private IPs) | **Must** | SSRF prevention |
| FR-8.5 | Store API keys as bcrypt hashes | **Must** | Never store plaintext |
| FR-8.6 | Rate limit authentication failures | **Should** | Prevent brute force |
| FR-8.7 | Audit log for all administrative actions | **Should** | Tenant CRUD, key rotation |
| FR-8.8 | Support API key scoping (read-only, write, admin) | **Could** | Role-based access |
| FR-8.9 | IP allowlisting for API access | **Could** | Per-tenant configuration |
| FR-8.10 | Payload encryption at rest | **Should** | PostgreSQL RDS encryption |
| FR-8.11 | mTLS for internal service communication | **Won't** | Out of scope for v1 |

### HMAC Signature Verification (Consumer Reference)

```java
// Consumer-side verification example
public boolean verifySignature(String payload, String signature, 
                                String secret, String timestamp) {
    // 1. Check timestamp (replay protection)
    long eventTime = Long.parseLong(timestamp);
    long now = Instant.now().getEpochSecond();
    if (Math.abs(now - eventTime) > 300) { // 5 minutes
        return false; // Possible replay attack
    }
    
    // 2. Compute expected signature
    String signedContent = timestamp + "." + payload;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
    byte[] hash = mac.doFinal(signedContent.getBytes(UTF_8));
    String expected = "v1=" + Base64.getEncoder().encodeToString(hash);
    
    // 3. Constant-time comparison (prevent timing attacks)
    return MessageDigest.isEqual(
        expected.getBytes(UTF_8), 
        signature.getBytes(UTF_8)
    );
}
```

---

## FR-9: Health and Operations

### Requirements

| ID | Requirement | Priority | Notes |
|----|-------------|----------|-------|
| FR-9.1 | Health check endpoint (`/actuator/health`) | **Must** | Checks DB, Redis, SQS connectivity |
| FR-9.2 | Readiness probe endpoint | **Must** | For ECS/load balancer |
| FR-9.3 | Liveness probe endpoint | **Must** | For ECS container health |
| FR-9.4 | Prometheus metrics endpoint (`/actuator/prometheus`) | **Should** | All application metrics |
| FR-9.5 | Graceful shutdown (drain in-flight requests) | **Must** | 30-second drain period |
| FR-9.6 | Configuration via environment variables | **Must** | 12-factor app compliance |
| FR-9.7 | Database migration management (Flyway) | **Must** | Version-controlled schema changes |
| FR-9.8 | Request ID tracing (correlation ID) | **Should** | `X-Request-ID` header propagation |

---

## Requirements Traceability Matrix

| Requirement | Use Case | Test Type | Phase |
|-------------|----------|-----------|-------|
| FR-1.1 (Tenant CRUD) | All | Unit + API | 1 |
| FR-2.1 (Subscription CRUD) | All | Unit + API | 1 |
| FR-3.1 (Event ingestion) | UC-1 through UC-6 | Unit + API + Load | 1 |
| FR-3.4 (Idempotency) | UC-1, UC-3, UC-4 | Unit + Integration | 1 |
| FR-4.1 (HTTP delivery) | All | Integration | 1 |
| FR-4.2 (HMAC signing) | UC-1, UC-5, UC-6 | Unit + Integration | 2 |
| FR-5.1 (Retry backoff) | All | Unit + Integration | 2 |
| FR-6.1 (DLQ) | All | Integration | 2 |
| FR-4.15 (Circuit breaker) | UC-1, UC-3, UC-4, UC-5 | Integration | 3 |
| FR-7.1 (Event explorer) | All | API + UI | 3 |
| FR-8.4 (SSRF prevention) | UC-5 | Security | 2 |
| FR-9.1 (Health check) | Ops | Integration | 1 |

---

> [!IMPORTANT]
> All **Must** requirements are blocking for the respective phase milestone. No phase can be considered complete until all Must requirements for that phase pass acceptance testing.
