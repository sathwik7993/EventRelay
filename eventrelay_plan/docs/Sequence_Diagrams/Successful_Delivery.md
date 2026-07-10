# Successful Delivery — Sequence Diagram

> **Document Version:** 1.0  
> **Last Updated:** 2026-07-10  
> **Status:** Production Reference

## Overview

This document traces the **complete delivery pipeline** for a successfully delivered event — from the Outbox Poller reading a pending outbox entry, through SQS enqueue, Dispatcher processing, HMAC signing, HTTP delivery, and final success recording. This is the **happy path** that the vast majority (~99%) of deliveries should follow.

---

## End-to-End Successful Delivery Sequence

```mermaid
sequenceDiagram
    autonumber
    participant PG as PostgreSQL
    participant OP as Outbox Poller
    participant SQS as AWS SQS<br/>(Delivery Queue)
    participant DW as Dispatcher Worker
    participant RD as Redis
    participant HS as HMAC Signer
    participant TARGET as Target URL<br/>(https://hooks.example.com)

    Note over OP: Scheduled every 500ms<br/>Leader-elected instance

    rect rgb(240, 248, 255)
        Note over OP,PG: Phase 1: Outbox Polling & SQS Enqueue
        OP->>+PG: SELECT id, event_id, subscription_id,<br/>payload, attempt_count<br/>FROM outbox<br/>WHERE status = 'PENDING'<br/>ORDER BY created_at ASC<br/>LIMIT 100<br/>FOR UPDATE SKIP LOCKED
        PG-->>-OP: [outbox_entry_1, outbox_entry_2, ...]<br/>(batch of up to 100 entries)

        Note over OP: Build SQS messages from<br/>outbox entries (batch of 10)
        OP->>+SQS: SendMessageBatch({<br/>  messages: [{<br/>    id: "outbox_entry_1",<br/>    body: {<br/>      event_id: "evt_abc",<br/>      subscription_id: "sub_123",<br/>      tenant_id: "t_abc123",<br/>      target_url: "https://hooks.example.com/webhook",<br/>      payload: {...},<br/>      attempt_number: 1,<br/>      max_attempts: 15<br/>    },<br/>    messageGroupId: null<br/>  }]<br/>})
        SQS-->>-OP: SendMessageBatchResult<br/>{successful: [outbox_entry_1], failed: []}

        Note over OP: Mark outbox entries as processed
        OP->>+PG: UPDATE outbox<br/>SET status = 'PROCESSED',<br/>    processed_at = NOW(),<br/>    sqs_message_id = 'msg_...'<br/>WHERE id IN ('outbox_entry_1', ...)
        PG-->>-OP: Updated 10 rows
    end

    rect rgb(240, 255, 240)
        Note over DW,SQS: Phase 2: SQS Message Consumption
        DW->>+SQS: ReceiveMessage({<br/>  queueUrl: "...delivery-queue",<br/>  maxNumberOfMessages: 10,<br/>  waitTimeSeconds: 20,<br/>  visibilityTimeout: 60<br/>})
        SQS-->>-DW: [Message {<br/>  messageId: "msg_...",<br/>  body: { event_id, subscription_id,<br/>          tenant_id, target_url,<br/>          payload, attempt_number: 1 },<br/>  receiptHandle: "rh_..."<br/>}]
    end

    rect rgb(255, 248, 240)
        Note over DW,RD: Phase 3: Pre-delivery Checks
        Note over DW: Step 3a: Rate Limit Check
        DW->>+RD: EVALSHA token_bucket.lua<br/>key = "rl:t_abc123:hooks.example.com"<br/>capacity = 50, rate = 50/s
        RD-->>-DW: ALLOWED (tokens: 42)

        Note over DW: Step 3b: Circuit Breaker Check
        DW->>+RD: GET cb:t_abc123:hooks.example.com
        RD-->>-DW: { state: "CLOSED", failures: 0 }
        Note over DW: Circuit is CLOSED → proceed
    end

    rect rgb(240, 240, 255)
        Note over DW,TARGET: Phase 4: HMAC Signing & HTTP Delivery
        Note over DW: Step 4a: Retrieve signing secret
        DW->>+RD: GET tenant_secret:t_abc123
        RD-->>-DW: "whsec_base64encoded..."

        Note over DW,HS: Step 4b: Compute HMAC signature
        DW->>+HS: sign(timestamp, payload, secret)
        Note over HS: signing_string = "1720584045.{\"event_type\":\"order.completed\",...}"<br/>signature = HMAC-SHA256(signing_string, secret)<br/>encoded = Base64(signature)
        HS-->>-DW: "v1=kX9bN2qR7vP..."

        Note over DW: Step 4c: HTTP POST to target
        DW->>+TARGET: POST /webhook HTTP/1.1<br/>Host: hooks.example.com<br/>Content-Type: application/json<br/>X-EventRelay-Signature: v1=kX9bN2qR7vP...<br/>X-EventRelay-Timestamp: 1720584045<br/>X-EventRelay-Event-Id: evt_abc<br/>X-EventRelay-Event-Type: order.completed<br/>User-Agent: EventRelay/1.0<br/><br/>{<br/>  "event_id": "evt_abc",<br/>  "event_type": "order.completed",<br/>  "created_at": "2026-07-10T04:00:45Z",<br/>  "payload": { "order_id": "ord_12345", ... }<br/>}
        TARGET-->>-DW: HTTP 200 OK<br/>{ "received": true }
        Note over DW: Response received in 85ms
    end

    rect rgb(240, 255, 240)
        Note over DW,PG: Phase 5: Record Success & Cleanup
        Note over DW: Step 5a: Record delivery attempt
        DW->>+PG: INSERT INTO delivery_attempts<br/>(id, event_id, subscription_id,<br/>tenant_id, attempt_number,<br/>status, response_code,<br/>response_body, latency_ms,<br/>created_at)<br/>VALUES<br/>(uuid, 'evt_abc', 'sub_123',<br/>'t_abc123', 1, 'SUCCESS',<br/>200, '{"received":true}',<br/>85, NOW())
        PG-->>-DW: Inserted ✓

        Note over DW: Step 5b: Reset circuit breaker failures
        DW->>+RD: SET cb:t_abc123:hooks.example.com<br/>{ state: "CLOSED", failures: 0 }
        RD-->>-DW: OK

        Note over DW: Step 5c: Delete SQS message
        DW->>+SQS: DeleteMessage({<br/>  queueUrl: "...delivery-queue",<br/>  receiptHandle: "rh_..."<br/>})
        SQS-->>-DW: Deleted ✓

        Note over DW: Step 5d: Emit metrics
        Note over DW: Counter: delivery.success +1<br/>Histogram: delivery.latency = 85ms<br/>Gauge: delivery.queue.depth -= 1
    end
```

---

## Timing Breakdown

```mermaid
gantt
    title Delivery Latency Breakdown (Typical Successful Delivery)
    dateFormat X
    axisFormat %L ms

    section Outbox Poll
    Poll query (SELECT)         :0, 15
    Send to SQS                 :15, 25
    Mark processed (UPDATE)     :25, 30

    section Queue Wait
    SQS propagation delay       :30, 35

    section Dispatcher
    Receive from SQS            :35, 37
    Rate limit check (Redis)    :37, 38
    Circuit breaker check       :38, 39
    Fetch signing secret        :39, 40
    HMAC computation            :40, 41
    HTTP POST to target         :41, 126
    Record delivery attempt     :126, 131
    Delete SQS message          :131, 133
```

| Phase | Component | Typical Duration | Notes |
|---|---|---|---|
| Outbox polling | Poller → PostgreSQL | 10–20ms | Batched `SELECT FOR UPDATE SKIP LOCKED` |
| SQS enqueue | Poller → SQS | 5–15ms | `SendMessageBatch` API call |
| Mark processed | Poller → PostgreSQL | 3–8ms | Batch `UPDATE` |
| SQS propagation | SQS internal | 1–10ms | Standard queue — usually sub-5ms |
| SQS receive | Dispatcher → SQS | 1–2ms | Long poll returns immediately if messages exist |
| Rate limit check | Dispatcher → Redis | < 1ms | Redis Lua script execution |
| Circuit breaker | Dispatcher → Redis | < 1ms | Simple `GET` |
| HMAC signing | Dispatcher (in-memory) | < 1ms | CPU-bound, negligible |
| **HTTP POST** | **Dispatcher → Target** | **50–200ms** | **Dominates total latency** |
| Record attempt | Dispatcher → PostgreSQL | 3–8ms | Single `INSERT` |
| Delete SQS msg | Dispatcher → SQS | 2–5ms | `DeleteMessage` API call |
| **Total (end-to-end)** | | **~100–300ms** | From outbox poll to success recorded |

> [!IMPORTANT]
> The HTTP POST to the target URL dominates total delivery latency. The EventRelay overhead (everything except the HTTP call) is typically **< 50ms**.

---

## Outbox Poller — Detailed Behavior

### Polling Loop

```mermaid
flowchart TD
    A["Start: Scheduled Trigger<br/>(every 500ms)"] --> B{"Acquired<br/>advisory lock?"}
    B -- "No" --> Z["Skip this cycle<br/>(another poller is leader)"]
    B -- "Yes" --> C["SELECT outbox entries<br/>WHERE status = 'PENDING'<br/>LIMIT 100<br/>FOR UPDATE SKIP LOCKED"]
    C --> D{"Any entries<br/>found?"}
    D -- "No" --> E["Release lock<br/>Sleep until next cycle"]
    D -- "Yes" --> F["Group into batches<br/>of 10 (SQS limit)"]
    F --> G["SendMessageBatch<br/>to SQS"]
    G --> H{"All sent<br/>successfully?"}
    H -- "Yes" --> I["UPDATE outbox<br/>SET status = 'PROCESSED'"]
    H -- "Partial failure" --> J["UPDATE successful entries<br/>Leave failed as 'PENDING'"]
    I --> K["Emit metrics:<br/>outbox.processed.count<br/>outbox.batch.size"]
    J --> K
    K --> L{"More entries<br/>in outbox?"}
    L -- "Yes" --> C
    L -- "No" --> E

    style A fill:#e3f2fd
    style Z fill:#fff9c4
    style E fill:#c8e6c9
```

### `SELECT FOR UPDATE SKIP LOCKED` — Why It Matters

```sql
-- This query ensures:
-- 1. Row-level locking prevents duplicate processing
-- 2. SKIP LOCKED means concurrent pollers don't block each other
-- 3. ORDER BY created_at ensures FIFO ordering
-- 4. LIMIT 100 caps batch size for predictable throughput

SELECT id, event_id, subscription_id, payload, attempt_count, created_at
FROM outbox
WHERE status = 'PENDING'
ORDER BY created_at ASC
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

---

## SQS Message Format

```json
{
  "event_id": "evt_a1b2c3d4e5f6",
  "event_type": "order.completed",
  "tenant_id": "t_abc123",
  "subscription_id": "sub_s1t2u3v4w5",
  "target_url": "https://hooks.example.com/webhook",
  "attempt_number": 1,
  "max_attempts": 15,
  "payload": {
    "event_id": "evt_a1b2c3d4e5f6",
    "event_type": "order.completed",
    "created_at": "2026-07-10T04:00:45.123Z",
    "payload": {
      "order_id": "ord_12345",
      "customer_id": "cust_67890",
      "total": 99.99,
      "currency": "USD"
    }
  },
  "metadata": {
    "outbox_id": "ob_x1y2z3",
    "enqueued_at": "2026-07-10T04:00:45.200Z",
    "trace_id": "tr_m1n2o3p4"
  }
}
```

---

## Success Criteria

A delivery is considered **successful** when the target URL responds with any **2xx HTTP status code** within the configured timeout:

| Response Code | Interpretation | Action |
|---|---|---|
| 200 OK | Standard success | Record success, delete SQS message |
| 201 Created | Resource created | Record success, delete SQS message |
| 202 Accepted | Accepted for processing | Record success, delete SQS message |
| 204 No Content | Success, no body | Record success, delete SQS message |

> [!NOTE]
> **Response body truncation**: We store up to **4KB** of the response body in `delivery_attempts.response_body` for debugging purposes. Larger responses are truncated with a `[TRUNCATED]` suffix.

---

## Related Documents

- [Event Ingestion](../Sequence_Diagrams/Event_Ingestion.md) — What happens before delivery
- [Failed Delivery & Retry](../Sequence_Diagrams/Failed_Delivery_Retry.md) — What happens when delivery fails
- [HMAC Signing](../Sequence_Diagrams/HMAC_Signing.md) — Detailed signing process
- [Database Schema](../ER_Diagrams/Database_Schema.md) — Outbox and delivery_attempts tables
