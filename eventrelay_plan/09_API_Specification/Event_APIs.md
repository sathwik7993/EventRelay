# Event APIs

> [!NOTE]
> Event APIs are the core of EventRelay. They handle event ingestion, querying, and batch operations. Events submitted through these endpoints are persisted to the outbox table and asynchronously dispatched to all matching subscriptions.

---

## Table of Contents

- [Event Lifecycle](#event-lifecycle)
- [POST /api/v1/events — Submit Event](#post-apiv1events--submit-event)
- [GET /api/v1/events/{id} — Get Event](#get-apiv1eventsid--get-event)
- [GET /api/v1/events — List Events](#get-apiv1events--list-events)
- [POST /api/v1/events/batch — Batch Submit Events](#post-apiv1eventsbatch--batch-submit-events)
- [Event Schema Reference](#event-schema-reference)
- [Validation Rules](#validation-rules)
- [Idempotency](#idempotency)
- [Error Responses](#error-responses)
- [Production Considerations](#production-considerations)

---

## Event Lifecycle

```mermaid
stateDiagram-v2
    [*] --> pending: Event submitted
    pending --> queued: Written to outbox, polled by dispatcher
    queued --> delivering: Dispatcher picks up from SQS
    delivering --> delivered: HTTP 2xx from target
    delivering --> retrying: HTTP 4xx/5xx or timeout
    retrying --> delivering: Retry attempt
    retrying --> failed: Max retries exhausted
    failed --> dead_letter: Moved to DLQ
    dead_letter --> delivering: Manual replay
    delivered --> [*]
```

### Event Statuses

| Status | Description |
|---|---|
| `pending` | Event accepted, written to outbox, awaiting dispatch |
| `queued` | Event published to SQS |
| `delivering` | Dispatcher is attempting HTTP delivery |
| `delivered` | Successfully delivered (HTTP 2xx received) |
| `retrying` | Delivery failed, scheduled for retry |
| `failed` | All retry attempts exhausted |
| `dead_letter` | Moved to dead-letter queue for manual inspection |

---

## POST /api/v1/events — Submit Event

Submits a single event for delivery to all matching subscriptions. The event is written to the transactional outbox and dispatched asynchronously.

**Authorization:** Bearer token with `events:write` scope

### Request

```http
POST /api/v1/events HTTP/1.1
Host: api.eventrelay.io
Authorization: Bearer sk_live_a1B2c3D4e5F6g7H8i9J0...
Content-Type: application/json
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
X-Request-Id: req_client_12345
```

```json
{
  "event_type": "order.created",
  "payload": {
    "order_id": "ord_98765",
    "customer_id": "cust_12345",
    "total_amount": 149.99,
    "currency": "USD",
    "items": [
      {
        "product_id": "prod_001",
        "name": "Widget Pro",
        "quantity": 2,
        "unit_price": 74.99
      }
    ],
    "created_at": "2026-07-10T04:00:00Z"
  },
  "metadata": {
    "source": "checkout-service",
    "correlation_id": "corr_abc123"
  },
  "channels": ["webhook", "email"]
}
```

### Request Schema

| Field | Type | Required | Constraints | Description |
|---|---|---|---|---|
| `event_type` | `string` | Yes | 3–256 chars, dot-separated pattern | Event type identifier (e.g., `order.created`) |
| `payload` | `object` | Yes | Max 256 KB JSON | Event payload delivered to subscribers |
| `metadata` | `object` | No | Max 10 keys, 256 chars each value | Arbitrary metadata (not delivered to subscribers) |
| `channels` | `string[]` | No | Valid channel types | Delivery channels (default: `["webhook"]`) |
| `scheduled_at` | `ISO 8601` | No | Must be in the future, max 30 days | Delayed delivery timestamp |
| `dedup_key` | `string` | No | Max 256 chars | Application-level deduplication key |

### Event Type Naming Convention

```
<domain>.<action>
<domain>.<resource>.<action>

Examples:
  order.created
  order.updated
  order.payment.completed
  user.subscription.cancelled
  inventory.item.low_stock
```

> [!TIP]
> Use hierarchical dot-notation for event types. Subscribers can use wildcard matching: `order.*` matches `order.created`, `order.updated`, etc.

### Response — `201 Created`

```json
{
  "data": {
    "id": "evt_01H5K3MNOPQR",
    "event_type": "order.created",
    "status": "pending",
    "payload": {
      "order_id": "ord_98765",
      "customer_id": "cust_12345",
      "total_amount": 149.99,
      "currency": "USD",
      "items": [
        {
          "product_id": "prod_001",
          "name": "Widget Pro",
          "quantity": 2,
          "unit_price": 74.99
        }
      ],
      "created_at": "2026-07-10T04:00:00Z"
    },
    "metadata": {
      "source": "checkout-service",
      "correlation_id": "corr_abc123"
    },
    "tenant_id": "tenant_abc123",
    "idempotency_key": "550e8400-e29b-41d4-a716-446655440000",
    "matching_subscriptions": 3,
    "created_at": "2026-07-10T04:00:01Z"
  },
  "_links": {
    "self": { "href": "/api/v1/events/evt_01H5K3MNOPQR" },
    "deliveries": { "href": "/api/v1/events/evt_01H5K3MNOPQR/deliveries" },
    "replay": { "href": "/api/v1/events/evt_01H5K3MNOPQR/replay", "method": "POST" }
  }
}
```

### curl Example

```bash
curl -X POST https://api.eventrelay.io/api/v1/events \
  -H "Authorization: Bearer sk_live_a1B2c3D4e5F6g7H8i9J0..." \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "event_type": "order.created",
    "payload": {
      "order_id": "ord_98765",
      "total_amount": 149.99
    }
  }'
```

### Controller Implementation

```java
@V1Api
@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @PostMapping
    public ResponseEntity<ApiResponse<EventResponse>> submitEvent(
            @Valid @RequestBody EventCreateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal TenantPrincipal principal) {

        Event event = eventService.submit(request, principal.getTenantId(), idempotencyKey);

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.of(EventResponse.from(event)));
    }
}
```

---

## GET /api/v1/events/{id} — Get Event

Retrieves a single event by ID, including its current delivery status across all matching subscriptions.

**Authorization:** Bearer token with `events:read` scope

### Request

```http
GET /api/v1/events/evt_01H5K3MNOPQR HTTP/1.1
Host: api.eventrelay.io
Authorization: Bearer sk_live_a1B2c3D4e5F6g7H8i9J0...
```

### Response — `200 OK`

```json
{
  "data": {
    "id": "evt_01H5K3MNOPQR",
    "event_type": "order.created",
    "status": "delivered",
    "payload": {
      "order_id": "ord_98765",
      "total_amount": 149.99
    },
    "metadata": {
      "source": "checkout-service"
    },
    "tenant_id": "tenant_abc123",
    "created_at": "2026-07-10T04:00:01Z",
    "deliveries": [
      {
        "id": "dlv_001",
        "subscription_id": "sub_aaa111",
        "target_url": "https://acme.com/webhooks/orders",
        "status": "delivered",
        "http_status": 200,
        "response_time_ms": 145,
        "attempt_number": 1,
        "delivered_at": "2026-07-10T04:00:02Z"
      },
      {
        "id": "dlv_002",
        "subscription_id": "sub_bbb222",
        "target_url": "https://partner.io/hooks",
        "status": "delivered",
        "http_status": 200,
        "response_time_ms": 312,
        "attempt_number": 2,
        "delivered_at": "2026-07-10T04:00:35Z",
        "attempts": [
          {
            "attempt_number": 1,
            "status": "failed",
            "http_status": 503,
            "error": "Service Unavailable",
            "attempted_at": "2026-07-10T04:00:02Z"
          },
          {
            "attempt_number": 2,
            "status": "delivered",
            "http_status": 200,
            "attempted_at": "2026-07-10T04:00:35Z"
          }
        ]
      }
    ],
    "delivery_summary": {
      "total_subscriptions": 2,
      "delivered": 2,
      "failed": 0,
      "pending": 0
    }
  },
  "_links": {
    "self": { "href": "/api/v1/events/evt_01H5K3MNOPQR" },
    "replay": { "href": "/api/v1/events/evt_01H5K3MNOPQR/replay", "method": "POST" }
  }
}
```

### curl Example

```bash
curl -s "https://api.eventrelay.io/api/v1/events/evt_01H5K3MNOPQR" \
  -H "Authorization: Bearer sk_live_a1B2c3D4e5F6g7H8i9J0..." | jq
```

---

## GET /api/v1/events — List Events

Lists events for the authenticated tenant with filtering, sorting, and pagination.

**Authorization:** Bearer token with `events:read` scope

### Request

```http
GET /api/v1/events?event_type=order.created,order.updated&status=delivered&created_at.gte=2026-07-01T00:00:00Z&sort=created_at:desc&limit=50 HTTP/1.1
Host: api.eventrelay.io
Authorization: Bearer sk_live_a1B2c3D4e5F6g7H8i9J0...
```

### Query Parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `cursor` | `string` | `null` | Pagination cursor |
| `limit` | `integer` | `20` | Items per page (1–100) |
| `sort` | `string` | `created_at:desc` | Sort field and direction |
| `event_type` | `string` | — | Comma-separated event types to filter |
| `status` | `string` | — | Filter by status: `pending`, `delivered`, `failed`, etc. |
| `created_at.gte` | `ISO 8601` | — | Events created at or after this timestamp |
| `created_at.lt` | `ISO 8601` | — | Events created before this timestamp |
| `dedup_key` | `string` | — | Filter by deduplication key |
| `metadata.source` | `string` | — | Filter by metadata field |

### Response — `200 OK`

```json
{
  "data": [
    {
      "id": "evt_01H5K3MNOPQR",
      "event_type": "order.created",
      "status": "delivered",
      "matching_subscriptions": 2,
      "delivery_summary": {
        "total_subscriptions": 2,
        "delivered": 2,
        "failed": 0,
        "pending": 0
      },
      "created_at": "2026-07-10T04:00:01Z"
    },
    {
      "id": "evt_02H5L4STUVWX",
      "event_type": "order.updated",
      "status": "retrying",
      "matching_subscriptions": 1,
      "delivery_summary": {
        "total_subscriptions": 1,
        "delivered": 0,
        "failed": 0,
        "pending": 1
      },
      "created_at": "2026-07-10T03:55:00Z"
    }
  ],
  "pagination": {
    "has_more": true,
    "next_cursor": "eyJpZCI6ImV2dF8wMkg1TDRTV...",
    "previous_cursor": null,
    "limit": 50
  }
}
```

> [!NOTE]
> List responses include a compact `delivery_summary` instead of full delivery details. Use `GET /api/v1/events/{id}` for complete delivery information.

### curl Example

```bash
# Events from the last 24 hours, failed only
curl -s "https://api.eventrelay.io/api/v1/events?\
status=failed&\
created_at.gte=2026-07-09T04:00:00Z&\
sort=created_at:desc&\
limit=50" \
  -H "Authorization: Bearer sk_live_a1B2c3D4..." | jq
```

---

## POST /api/v1/events/batch — Batch Submit Events

Submits up to 100 events in a single request. Each event is processed independently — partial failures are possible and reported per-event.

**Authorization:** Bearer token with `events:write` scope

### Request

```http
POST /api/v1/events/batch HTTP/1.1
Host: api.eventrelay.io
Authorization: Bearer sk_live_a1B2c3D4e5F6g7H8i9J0...
Content-Type: application/json
Idempotency-Key: batch_550e8400-e29b-41d4-a716-446655440000
```

```json
{
  "events": [
    {
      "event_type": "order.created",
      "payload": { "order_id": "ord_001", "total": 99.99 },
      "dedup_key": "order_001_created"
    },
    {
      "event_type": "order.created",
      "payload": { "order_id": "ord_002", "total": 149.99 },
      "dedup_key": "order_002_created"
    },
    {
      "event_type": "inventory.low_stock",
      "payload": { "product_id": "prod_X", "remaining": 3 }
    }
  ]
}
```

### Request Schema

| Field | Type | Required | Constraints | Description |
|---|---|---|---|---|
| `events` | `EventCreateRequest[]` | Yes | 1–100 items | Array of event objects (same schema as single submit) |

### Response — `202 Accepted`

```json
{
  "data": {
    "batch_id": "batch_01H5K3YZABCD",
    "total": 3,
    "accepted": 3,
    "rejected": 0,
    "results": [
      {
        "index": 0,
        "status": "accepted",
        "event_id": "evt_03H5M5EFGHIJ",
        "event_type": "order.created"
      },
      {
        "index": 1,
        "status": "accepted",
        "event_id": "evt_04H5N6KLMNOP",
        "event_type": "order.created"
      },
      {
        "index": 2,
        "status": "accepted",
        "event_id": "evt_05H5O7QRSTUV",
        "event_type": "inventory.low_stock"
      }
    ]
  }
}
```

### Partial Failure Response — `202 Accepted`

```json
{
  "data": {
    "batch_id": "batch_02H5K4EFGHIJ",
    "total": 3,
    "accepted": 2,
    "rejected": 1,
    "results": [
      {
        "index": 0,
        "status": "accepted",
        "event_id": "evt_06H5P8WXYZ01"
      },
      {
        "index": 1,
        "status": "rejected",
        "error": {
          "code": "VALIDATION_ERROR",
          "message": "payload must not be null"
        }
      },
      {
        "index": 2,
        "status": "accepted",
        "event_id": "evt_07H5Q9234567"
      }
    ]
  }
}
```

> [!WARNING]
> Batch requests return `202 Accepted` even with partial failures. Always check the `rejected` count and individual `results[].status` fields.

### curl Example

```bash
curl -X POST https://api.eventrelay.io/api/v1/events/batch \
  -H "Authorization: Bearer sk_live_a1B2c3D4..." \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "events": [
      {"event_type": "order.created", "payload": {"order_id": "ord_001"}},
      {"event_type": "order.created", "payload": {"order_id": "ord_002"}}
    ]
  }'
```

---

## Event Schema Reference

### Event Object (Full)

```json
{
  "id": "evt_01H5K3MNOPQR",
  "event_type": "order.created",
  "status": "delivered",
  "payload": {},
  "metadata": {},
  "tenant_id": "tenant_abc123",
  "idempotency_key": "550e8400-...",
  "dedup_key": "order_98765_created",
  "matching_subscriptions": 3,
  "channels": ["webhook"],
  "scheduled_at": null,
  "created_at": "2026-07-10T04:00:01Z",
  "updated_at": "2026-07-10T04:00:02Z"
}
```

### Delivery Object

```json
{
  "id": "dlv_001",
  "event_id": "evt_01H5K3MNOPQR",
  "subscription_id": "sub_aaa111",
  "target_url": "https://acme.com/webhooks/orders",
  "status": "delivered",
  "http_status": 200,
  "response_body": "{\"received\":true}",
  "response_time_ms": 145,
  "attempt_number": 1,
  "next_retry_at": null,
  "max_attempts": 10,
  "delivered_at": "2026-07-10T04:00:02Z",
  "created_at": "2026-07-10T04:00:01Z"
}
```

### Database Schema

```sql
CREATE TABLE events (
    id              VARCHAR(26) PRIMARY KEY,
    tenant_id       VARCHAR(26) NOT NULL,
    event_type      VARCHAR(256) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'pending',
    payload         JSONB NOT NULL,
    metadata        JSONB,
    idempotency_key VARCHAR(256),
    dedup_key       VARCHAR(256),
    channels        TEXT[] DEFAULT ARRAY['webhook'],
    scheduled_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_events_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT uq_events_dedup UNIQUE (tenant_id, dedup_key)
);

CREATE INDEX idx_events_tenant_type ON events (tenant_id, event_type);
CREATE INDEX idx_events_tenant_status ON events (tenant_id, status);
CREATE INDEX idx_events_tenant_created ON events (tenant_id, created_at DESC);
CREATE INDEX idx_events_outbox ON events (status, created_at)
    WHERE status = 'pending';

CREATE TABLE event_deliveries (
    id              VARCHAR(26) PRIMARY KEY,
    event_id        VARCHAR(26) NOT NULL REFERENCES events(id),
    subscription_id VARCHAR(26) NOT NULL,
    target_url      TEXT NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'pending',
    http_status     SMALLINT,
    response_body   TEXT,
    response_time_ms INTEGER,
    attempt_number  SMALLINT NOT NULL DEFAULT 0,
    max_attempts    SMALLINT NOT NULL DEFAULT 10,
    next_retry_at   TIMESTAMPTZ,
    error_message   TEXT,
    delivered_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_deliveries_event ON event_deliveries (event_id);
CREATE INDEX idx_deliveries_retry ON event_deliveries (next_retry_at)
    WHERE status = 'retrying';
```

---

## Validation Rules

### Event Type Validation

| Rule | Constraint | Example |
|---|---|---|
| Length | 3–256 characters | ✓ `order.created` |
| Pattern | `^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)*$` | ✗ `Order.Created` |
| Dots | At least one segment, max 5 levels | ✓ `a.b.c.d.e` |
| Reserved prefixes | Cannot start with `_` or `system.` | ✗ `system.internal` |

### Payload Validation

| Rule | Constraint |
|---|---|
| Required | Must not be null or empty |
| Max size | 256 KB (after JSON serialization) |
| Depth | Max 10 levels of nesting |
| Type | Must be a JSON object (not array, string, etc.) |

### Metadata Validation

| Rule | Constraint |
|---|---|
| Max keys | 10 |
| Key length | 1–64 characters, alphanumeric + underscore |
| Value length | 1–256 characters |
| Value type | String only |

### Validation Implementation

```java
@Validated
public class EventCreateRequest {

    @NotBlank(message = "event_type is required")
    @Size(min = 3, max = 256, message = "event_type must be 3-256 characters")
    @Pattern(regexp = "^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*$",
             message = "event_type must be lowercase dot-separated (e.g., order.created)")
    private String eventType;

    @NotNull(message = "payload is required")
    @ValidPayloadSize(max = 256 * 1024)
    private Map<String, Object> payload;

    @Valid
    @Size(max = 10, message = "metadata may have at most 10 entries")
    private Map<@Size(max = 64) String, @Size(max = 256) String> metadata;

    @Size(max = 5)
    private List<@Pattern(regexp = "^(webhook|email)$") String> channels;

    @Future(message = "scheduled_at must be in the future")
    private Instant scheduledAt;

    @Size(max = 256)
    private String dedupKey;
}
```

---

## Idempotency

### How Idempotency Works for Events

```mermaid
sequenceDiagram
    participant C as Client
    participant A as API
    participant R as Redis
    participant DB as PostgreSQL

    C->>A: POST /events<br/>Idempotency-Key: abc-123
    A->>R: GET idempotency:tenant:abc-123
    R-->>A: null (first request)
    A->>DB: INSERT INTO events (...)
    DB-->>A: Event created
    A->>R: SET idempotency:tenant:abc-123 = response (TTL 24h)
    A-->>C: 201 Created

    Note over C,A: Client retries (network timeout)

    C->>A: POST /events<br/>Idempotency-Key: abc-123
    A->>R: GET idempotency:tenant:abc-123
    R-->>A: Cached response
    A-->>C: 201 Created (cached, no duplicate)
```

### Idempotency vs. Dedup Key

| Feature | `Idempotency-Key` (header) | `dedup_key` (body) |
|---|---|---|
| **Scope** | Request-level deduplication | Business-level deduplication |
| **Storage** | Redis (24h TTL) | PostgreSQL (permanent unique constraint) |
| **Purpose** | Safe retry of the same request | Prevent duplicate business events |
| **Behavior on conflict** | Returns cached original response | Returns `409 Conflict` |
| **Example** | Retry after network timeout | Prevent duplicate `order.created` for same order |

---

## Error Responses

### Event-Specific Error Codes

| HTTP Status | Error Code | Description |
|---|---|---|
| `400` | `INVALID_EVENT_TYPE` | Event type doesn't match required pattern |
| `400` | `INVALID_PAYLOAD` | Payload is not valid JSON or exceeds constraints |
| `404` | `EVENT_NOT_FOUND` | Event ID does not exist for this tenant |
| `409` | `DUPLICATE_EVENT` | Event with same `dedup_key` already exists |
| `409` | `IDEMPOTENCY_CONFLICT` | Same `Idempotency-Key` used with different request body |
| `413` | `EVENT_PAYLOAD_TOO_LARGE` | Payload exceeds 256 KB limit |
| `422` | `NO_MATCHING_SUBSCRIPTIONS` | No active subscriptions match the event type |
| `422` | `BATCH_EMPTY` | Batch request contains zero events |
| `422` | `BATCH_TOO_LARGE` | Batch request exceeds 100 events |

### Validation Error Example

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Request validation failed",
    "details": {
      "fields": [
        {
          "field": "event_type",
          "message": "event_type must be lowercase dot-separated (e.g., order.created)",
          "rejected_value": "Order.Created"
        },
        {
          "field": "payload",
          "message": "payload is required",
          "rejected_value": null
        }
      ]
    },
    "request_id": "req_550e8400"
  }
}
```

---

## Production Considerations

### Performance Characteristics

| Metric | Target | Notes |
|---|---|---|
| Single event submit latency (p99) | < 50ms | Outbox write only; delivery is async |
| Batch submit latency (p99) | < 200ms | For 100 events |
| Events per second (per tenant) | 1,000 | Configurable per tenant |
| Events per second (system) | 50,000 | Across all tenants |
| Payload size limit | 256 KB | Per event |
| Batch size limit | 100 events | Per request |

### Best Practices

1. **Always use `Idempotency-Key`** for POST requests — network failures happen
2. **Use `dedup_key`** for business-level deduplication (e.g., `{order_id}_{event_type}`)
3. **Prefer batch endpoint** for high-throughput scenarios — reduces HTTP overhead
4. **Keep payloads small** — store references (IDs, URLs) instead of large objects
5. **Use structured `event_type` names** — enables wildcard subscription matching
6. **Include `metadata.source`** — aids debugging across microservices
7. **Monitor `NO_MATCHING_SUBSCRIPTIONS`** warnings — may indicate misconfiguration

### Webhook Payload Delivered to Subscribers

When an event is delivered to a subscriber's target URL, the HTTP request looks like:

```http
POST /your-webhook-endpoint HTTP/1.1
Host: your-app.com
Content-Type: application/json
X-EventRelay-Event-Id: evt_01H5K3MNOPQR
X-EventRelay-Event-Type: order.created
X-EventRelay-Timestamp: 1720576001
X-EventRelay-Signature: v1=5257a869e7ecebeda32affa62cdca3fa51cad7e77a0e56ff536d0ce8e108d8bd
X-EventRelay-Delivery-Id: dlv_001
X-EventRelay-Attempt: 1
User-Agent: EventRelay/1.0

{
  "order_id": "ord_98765",
  "total_amount": 149.99,
  "currency": "USD"
}
```

> [!IMPORTANT]
> The webhook body is the event `payload` only — not the full event envelope. Metadata and event ID are in headers.

---

## Cross-References

| Document | Description |
|---|---|
| [REST_APIs.md](./REST_APIs.md) | Pagination, filtering, sorting, and common headers |
| [Subscription_APIs.md](./Subscription_APIs.md) | Managing event subscriptions |
| [Replay_APIs.md](./Replay_APIs.md) | Replaying failed events |
| [Error_Codes.md](./Error_Codes.md) | Complete error code catalog |
