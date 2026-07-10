# Event Ingestion — Sequence Diagram

> **Document Version:** 1.0  
> **Last Updated:** 2026-07-10  
> **Status:** Production Reference

## Overview

This document details the **complete request lifecycle** for event ingestion — from the client's HTTP POST through authentication, validation, idempotency checking, transactional outbox write, and response. All error paths are documented with their corresponding HTTP status codes.

---

## Happy Path — Successful Event Ingestion

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant ALB as API Gateway (ALB)
    participant AF as ApiKeyAuthFilter
    participant RL as RateLimitFilter
    participant EC as EventController
    participant EV as EventValidator
    participant IS as IdempotencyService
    participant ES as EventService
    participant PG as PostgreSQL
    participant RD as Redis

    C->>+ALB: POST /api/v1/events<br/>Headers: X-API-Key, Content-Type: application/json<br/>Body: { event_type, payload, idempotency_key }
    ALB->>+AF: Forward request (internal HTTP)

    Note over AF: Validate API Key
    AF->>+PG: SELECT tenant_id FROM api_keys<br/>WHERE key_hash = hash($apiKey)<br/>AND status = 'ACTIVE'
    PG-->>-AF: tenant_id = "t_abc123"
    Note over AF: Set SecurityContext<br/>with tenant_id

    AF->>+RL: Pass to RateLimitFilter
    Note over RL: Check per-tenant rate limit
    RL->>+RD: EVALSHA token_bucket.lua<br/>key=ratelimit:t_abc123<br/>capacity=100, rate=100/s
    RD-->>-RL: tokens_remaining = 87<br/>ALLOWED
    Note over RL: Add X-RateLimit-Remaining: 87<br/>X-RateLimit-Limit: 100

    RL->>+EC: Forward to EventController
    EC->>+EV: validate(eventRequest)

    Note over EV: Validation Checks:<br/>1. event_type matches pattern<br/>2. payload ≤ 256KB<br/>3. Required fields present<br/>4. JSON well-formed
    EV-->>-EC: Validation passed ✓

    EC->>+ES: ingestEvent(tenantId, eventRequest)

    Note over ES: Step 1: Idempotency Check
    ES->>+IS: checkIdempotency(tenantId, idempotencyKey)
    IS->>+RD: GET idemp:t_abc123:key_xyz789
    RD-->>-IS: null (not found — first attempt)
    IS-->>-ES: NOT_DUPLICATE

    Note over ES: Step 2: Transactional Write<br/>@Transactional
    ES->>+PG: BEGIN TRANSACTION

    Note over PG: Insert Event
    ES->>PG: INSERT INTO events<br/>(id, tenant_id, event_type,<br/>payload, idempotency_key,<br/>created_at)<br/>VALUES (uuid, 't_abc123',<br/>'order.completed', {...},<br/>'key_xyz789', NOW())

    Note over PG: Fan-out: Find matching subscriptions
    ES->>PG: SELECT id, target_url<br/>FROM subscriptions<br/>WHERE tenant_id = 't_abc123'<br/>AND 'order.completed' = ANY(event_types)<br/>AND status = 'ACTIVE'

    PG-->>ES: [{sub_1, url_1}, {sub_2, url_2}]

    Note over PG: Insert Outbox Entries<br/>(one per subscription)
    ES->>PG: INSERT INTO outbox<br/>(id, event_id, subscription_id,<br/>payload, status, created_at)<br/>VALUES<br/>(uuid1, event_id, sub_1, {...}, 'PENDING', NOW()),<br/>(uuid2, event_id, sub_2, {...}, 'PENDING', NOW())

    ES->>PG: COMMIT TRANSACTION
    PG-->>-ES: Committed ✓

    Note over ES: Step 3: Cache idempotency key
    ES->>+RD: SETEX idemp:t_abc123:key_xyz789<br/>86400 (24h TTL)<br/>value: event_id
    RD-->>-ES: OK

    ES-->>-EC: EventResult { eventId, status: ACCEPTED }

    EC-->>-RL: 202 Accepted
    RL-->>-AF: 202 Accepted
    AF-->>-ALB: 202 Accepted
    ALB-->>-C: HTTP 202 Accepted<br/>{ "event_id": "evt_...",<br/>"status": "accepted",<br/>"subscriptions_matched": 2 }
```

---

## Error Path 1 — Invalid API Key (401 Unauthorized)

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant ALB as API Gateway
    participant AF as ApiKeyAuthFilter
    participant PG as PostgreSQL

    C->>+ALB: POST /api/v1/events<br/>Headers: X-API-Key: invalid_key_123
    ALB->>+AF: Forward request

    AF->>+PG: SELECT tenant_id FROM api_keys<br/>WHERE key_hash = hash('invalid_key_123')<br/>AND status = 'ACTIVE'
    PG-->>-AF: Empty result set

    Note over AF: API key not found<br/>or revoked

    AF-->>-ALB: 401 Unauthorized
    ALB-->>-C: HTTP 401 Unauthorized<br/>{ "error": "invalid_api_key",<br/>"message": "The provided API key<br/>is invalid or has been revoked" }
```

---

## Error Path 2 — Rate Limit Exceeded (429 Too Many Requests)

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant AF as ApiKeyAuthFilter
    participant RL as RateLimitFilter
    participant RD as Redis

    C->>+AF: POST /api/v1/events (authenticated)
    AF->>+RL: Pass to RateLimitFilter

    RL->>+RD: EVALSHA token_bucket.lua<br/>key=ratelimit:t_abc123
    RD-->>-RL: tokens_remaining = 0<br/>DENIED, retry_after = 1.2s

    Note over RL: Rate limit exceeded<br/>for tenant t_abc123

    RL-->>-AF: 429 Too Many Requests
    AF-->>-C: HTTP 429 Too Many Requests<br/>Headers:<br/>  X-RateLimit-Limit: 100<br/>  X-RateLimit-Remaining: 0<br/>  Retry-After: 2<br/>{ "error": "rate_limit_exceeded",<br/>"message": "Rate limit exceeded.<br/>Retry after 2 seconds." }
```

---

## Error Path 3 — Validation Failure (400 Bad Request)

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant EC as EventController
    participant EV as EventValidator

    C->>+EC: POST /api/v1/events<br/>{ "event_type": "", "payload": null }

    EC->>+EV: validate(eventRequest)

    Note over EV: Validation failures:<br/>1. event_type is blank<br/>2. payload is null

    EV-->>-EC: ValidationException<br/>[{field: "event_type", msg: "must not be blank"},<br/> {field: "payload", msg: "must not be null"}]

    EC-->>-C: HTTP 400 Bad Request<br/>{ "error": "validation_failed",<br/>"errors": [<br/>  {"field": "event_type",<br/>   "message": "must not be blank"},<br/>  {"field": "payload",<br/>   "message": "must not be null"}<br/>]}
```

---

## Error Path 4 — Duplicate Event (200 OK — Idempotent Return)

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant EC as EventController
    participant ES as EventService
    participant IS as IdempotencyService
    participant RD as Redis

    C->>+EC: POST /api/v1/events<br/>{ idempotency_key: "key_xyz789", ... }

    EC->>+ES: ingestEvent(tenantId, eventRequest)
    ES->>+IS: checkIdempotency(tenantId, "key_xyz789")

    IS->>+RD: GET idemp:t_abc123:key_xyz789
    RD-->>-IS: "evt_previously_created_id"

    Note over IS: Duplicate detected!<br/>Same idempotency key was<br/>used within the last 24h

    IS-->>-ES: DUPLICATE(existingEventId)
    ES-->>-EC: EventResult { eventId: existing, status: DUPLICATE }

    EC-->>-C: HTTP 200 OK<br/>{ "event_id": "evt_previously_created_id",<br/>"status": "duplicate",<br/>"message": "Event already processed<br/>with this idempotency key" }

    Note over C: Client receives same event_id<br/>as the original request.<br/>Safe to retry without side effects.
```

---

## Error Path 5 — Payload Too Large (413)

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant EC as EventController
    participant EV as EventValidator

    C->>+EC: POST /api/v1/events<br/>{ payload: "... 300KB JSON ..." }

    EC->>+EV: validate(eventRequest)
    Note over EV: payload size = 307,200 bytes<br/>max allowed = 262,144 bytes (256KB)
    EV-->>-EC: PayloadTooLargeException

    EC-->>-C: HTTP 413 Payload Too Large<br/>{ "error": "payload_too_large",<br/>"message": "Event payload exceeds<br/>maximum size of 256KB",<br/>"max_size_bytes": 262144,<br/>"actual_size_bytes": 307200 }
```

---

## Error Path 6 — Internal Server Error (500)

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant EC as EventController
    participant ES as EventService
    participant PG as PostgreSQL

    C->>+EC: POST /api/v1/events<br/>{ ... valid request ... }

    EC->>+ES: ingestEvent(tenantId, eventRequest)
    ES->>+PG: BEGIN TRANSACTION<br/>INSERT INTO events ...

    Note over PG: Database connection<br/>timeout / deadlock

    PG-->>-ES: DataAccessException

    Note over ES: Transaction rolled back<br/>automatically by Spring

    ES-->>-EC: InternalServerException

    EC-->>-C: HTTP 500 Internal Server Error<br/>{ "error": "internal_error",<br/>"message": "An internal error occurred.<br/>Please retry with the same<br/>idempotency key.",<br/>"request_id": "req_..." }

    Note over C: Client can safely retry<br/>with same idempotency_key.<br/>If original write succeeded,<br/>they'll get the duplicate response.
```

---

## Request/Response Specification

### Request

```http
POST /api/v1/events HTTP/1.1
Host: api.eventrelay.io
Content-Type: application/json
X-API-Key: ak_live_7f3d9a2b1c4e8f...
X-Idempotency-Key: ord_12345_completed_v1
X-Request-Id: req_abc123def456

{
  "event_type": "order.completed",
  "payload": {
    "order_id": "ord_12345",
    "customer_id": "cust_67890",
    "total": 99.99,
    "currency": "USD",
    "items": [
      {"sku": "ITEM-001", "qty": 2, "price": 49.99}
    ],
    "completed_at": "2026-07-10T04:00:00Z"
  }
}
```

### Response (202 Accepted)

```http
HTTP/1.1 202 Accepted
Content-Type: application/json
X-Request-Id: req_abc123def456
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 87

{
  "event_id": "evt_a1b2c3d4e5f6",
  "status": "accepted",
  "event_type": "order.completed",
  "subscriptions_matched": 2,
  "created_at": "2026-07-10T04:00:45.123Z"
}
```

### Error Response Format

```json
{
  "error": "error_code",
  "message": "Human-readable error description",
  "request_id": "req_abc123def456",
  "errors": [
    {
      "field": "field_name",
      "message": "Specific field-level error"
    }
  ]
}
```

---

## HTTP Status Code Summary

| Status Code | Condition | Idempotent? | Client Action |
|---|---|---|---|
| **200 OK** | Duplicate idempotency key | Yes | Use returned event_id |
| **202 Accepted** | Event accepted for delivery | Yes (with key) | Store event_id |
| **400 Bad Request** | Validation failure | N/A | Fix request body |
| **401 Unauthorized** | Invalid/missing API key | N/A | Check API key |
| **413 Payload Too Large** | Payload > 256KB | N/A | Reduce payload size |
| **429 Too Many Requests** | Rate limit exceeded | N/A | Retry after `Retry-After` header |
| **500 Internal Error** | Database/system failure | Safe to retry | Retry with same idempotency key |

---

## Related Documents

- [System Overview](../Architecture_Diagrams/System_Overview.md) — Where ingestion fits in the overall architecture
- [Successful Delivery](../Sequence_Diagrams/Successful_Delivery.md) — What happens after ingestion
- [Database Schema](../ER_Diagrams/Database_Schema.md) — Event and outbox table structures
- [Postman Collection](../API_Collections/Postman_Collection.md) — Ready-to-use API requests
