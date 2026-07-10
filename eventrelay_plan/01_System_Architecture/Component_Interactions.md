# EventRelay — Component Interactions

> **Document Status:** Living Document · **Last Updated:** 2026-07-10 · **Owner:** Platform Engineering

## 1. Communication Patterns Overview

EventRelay uses four distinct communication patterns:

| Pattern | Technology | Use Case | Latency | Coupling |
|---|---|---|---|---|
| **Synchronous REST** | HTTP/JSON | API requests, Dashboard queries | Low (< 100ms) | Tight |
| **Asynchronous Messaging** | AWS SQS | Event dispatch, DLQ processing | Variable (50ms–hours) | Loose |
| **Shared Database** | PostgreSQL | State management, audit trail | Low (< 10ms) | Medium |
| **Shared Cache** | Redis | Rate limiting, dedup, circuit breaker state | Very Low (< 1ms) | Low |

```mermaid
graph LR
    subgraph Sync["Synchronous (REST)"]
        S1["Producer → Ingest API"]
        S2["Dashboard UI → Dashboard API"]
        S3["Dispatcher → Consumer Endpoint"]
    end

    subgraph Async["Asynchronous (SQS)"]
        A1["Outbox Poller → SQS → Dispatcher"]
        A2["SQS → SQS DLQ → Dead-Letter Manager"]
    end

    subgraph SharedState["Shared State"]
        D1["All Services → PostgreSQL"]
        D2["Ingest API + Dispatcher → Redis"]
    end
```

---

## 2. Sequence Diagrams

### 2.1 Event Ingestion (Happy Path)

```mermaid
sequenceDiagram
    actor Producer
    participant ALB as AWS ALB
    participant Ingest as Ingest API
    participant Redis as Redis
    participant PG as PostgreSQL

    Producer->>ALB: POST /v1/events<br/>(API Key in header)
    ALB->>Ingest: Forward request<br/>(TLS terminated)

    Note over Ingest: Validate API key<br/>Parse & validate payload

    Ingest->>Redis: CHECK rate limit<br/>(token bucket)
    Redis-->>Ingest: OK (tokens remaining: 742)

    Ingest->>Redis: CHECK idempotency key
    Redis-->>Ingest: NOT FOUND (not a duplicate)

    Note over Ingest: Find matching subscriptions

    Ingest->>PG: BEGIN TRANSACTION
    Ingest->>PG: INSERT INTO events (...)
    Ingest->>PG: INSERT INTO outbox (entry per subscription)
    Ingest->>PG: COMMIT

    Ingest->>Redis: SET idempotency key (TTL: 24h)

    Ingest-->>ALB: 202 Accepted<br/>{"event_id": "evt_...", "status": "RECEIVED"}
    ALB-->>Producer: 202 Accepted
```

### 2.2 Outbox Polling and SQS Publishing

```mermaid
sequenceDiagram
    participant Poller as Outbox Poller
    participant PG as PostgreSQL
    participant SQS as AWS SQS

    loop Every 500ms (leader only)
        Poller->>PG: SELECT * FROM outbox<br/>WHERE status = 'PENDING'<br/>ORDER BY created_at<br/>LIMIT 100<br/>FOR UPDATE SKIP LOCKED
        PG-->>Poller: [outbox_entries]

        alt No entries
            Note over Poller: Sleep until next poll
        else Entries found
            loop For each batch of 10
                Poller->>SQS: SendMessageBatch<br/>(up to 10 messages)
                SQS-->>Poller: BatchResult

                alt All successful
                    Poller->>PG: UPDATE outbox<br/>SET status = 'QUEUED'<br/>WHERE id IN (...)
                else Partial failure
                    Poller->>PG: UPDATE outbox<br/>SET status = 'QUEUED'<br/>WHERE id IN (successful_ids)
                    Note over Poller: Failed entries remain PENDING<br/>Picked up on next poll
                end
            end
        end
    end
```

### 2.3 Successful Webhook Delivery

```mermaid
sequenceDiagram
    participant SQS as AWS SQS
    participant Worker as Dispatcher Worker
    participant Redis as Redis
    participant PG as PostgreSQL
    participant Consumer as Consumer Endpoint

    SQS->>Worker: ReceiveMessage<br/>(visibility timeout: 60s)

    Worker->>PG: Load subscription details<br/>(cached in local memory)
    Worker->>PG: Load event payload

    Worker->>Redis: CHECK circuit breaker<br/>(endpoint: consumer URL)
    Redis-->>Worker: CLOSED (healthy)

    Worker->>Redis: ACQUIRE rate limit token<br/>(tenant + subscription)
    Redis-->>Worker: OK

    Note over Worker: Build HTTP request<br/>Compute HMAC-SHA256 signature<br/>Set delivery headers

    Worker->>Consumer: POST /webhooks<br/>Content-Type: application/json<br/>X-EventRelay-Signature: sha256=abc...<br/>X-EventRelay-Event-ID: evt_...<br/>X-EventRelay-Timestamp: 1720584000

    Consumer-->>Worker: 200 OK

    Worker->>PG: INSERT INTO delivery_attempts<br/>(status: SUCCESS, http_status: 200)
    Worker->>PG: UPDATE event_deliveries<br/>SET status = 'DELIVERED'

    Note over SQS: Message deleted<br/>(acknowledged)
```

### 2.4 Failed Delivery with Retry

```mermaid
sequenceDiagram
    participant SQS as AWS SQS
    participant Worker as Dispatcher Worker
    participant Redis as Redis
    participant PG as PostgreSQL
    participant Consumer as Consumer Endpoint

    SQS->>Worker: ReceiveMessage (attempt #1)

    Worker->>Redis: CHECK circuit breaker
    Redis-->>Worker: CLOSED

    Worker->>Consumer: POST /webhooks<br/>(HMAC signed)
    Consumer-->>Worker: 500 Internal Server Error

    Worker->>PG: INSERT INTO delivery_attempts<br/>(status: FAILED, http_status: 500, attempt: 1)

    Worker->>Redis: Record failure for circuit breaker

    Note over Worker: Calculate retry delay:<br/>backoff = min(base * 2^attempt + jitter, max_delay)<br/>= min(1 * 2^1 + 0.3, 3600) = 2.3s

    Worker->>SQS: SendMessage with DelaySeconds=2<br/>(re-queue for retry)

    Note over SQS: Message invisible for 2 seconds

    SQS->>Worker: ReceiveMessage (attempt #2)
    Worker->>Consumer: POST /webhooks
    Consumer-->>Worker: 503 Service Unavailable

    Worker->>PG: INSERT INTO delivery_attempts<br/>(status: FAILED, http_status: 503, attempt: 2)

    Note over Worker: backoff = min(1 * 2^2 + 0.7, 3600) = 4.7s

    Worker->>SQS: SendMessage with DelaySeconds=5

    Note over SQS: ...continues until max attempts (8) reached...

    SQS->>Worker: ReceiveMessage (attempt #8)
    Worker->>Consumer: POST /webhooks
    Consumer-->>Worker: Connection timeout

    Worker->>PG: INSERT INTO delivery_attempts<br/>(status: FAILED, error: "timeout", attempt: 8)
    Worker->>PG: UPDATE event_deliveries<br/>SET status = 'DEAD_LETTERED'

    Note over Worker: Max attempts exhausted<br/>Do NOT re-queue<br/>SQS moves to DLQ after maxReceiveCount
```

### 2.5 Dead-Letter Queue Processing

```mermaid
sequenceDiagram
    participant DLQ as SQS Dead-Letter Queue
    participant DLM as Dead-Letter Manager
    participant PG as PostgreSQL

    DLQ->>DLM: ReceiveMessage<br/>(batch of up to 10)

    loop For each DLQ message
        DLM->>PG: SELECT event, delivery_attempts<br/>WHERE event_id = ?

        DLM->>PG: INSERT INTO dead_letter_events<br/>(event_id, subscription_id, tenant_id,<br/>total_attempts, last_error, error_category)

        DLM->>PG: UPDATE event_deliveries<br/>SET status = 'DEAD_LETTERED'

        Note over DLM: Emit metric:<br/>dlq_events_total{tenant, event_type}

        Note over DLM: Delete message from DLQ<br/>(acknowledged)
    end
```

### 2.6 Event Replay from Dead-Letter Queue

```mermaid
sequenceDiagram
    actor Operator
    participant DashUI as Dashboard UI
    participant DashAPI as Dashboard API
    participant DLM as Dead-Letter Manager
    participant PG as PostgreSQL
    participant SQS as AWS SQS
    participant Worker as Dispatcher Worker
    participant Consumer as Consumer Endpoint

    Operator->>DashUI: Click "Replay" on dead-lettered event
    DashUI->>DashAPI: POST /v1/dlq/events/{eventId}/replay

    DashAPI->>DLM: Initiate replay

    DLM->>PG: Load dead_letter_event<br/>Validate event still exists
    DLM->>PG: UPDATE dead_letter_events<br/>SET status = 'REPLAYING'
    DLM->>PG: UPDATE event_deliveries<br/>SET status = 'RETRYING', attempts = 0

    DLM->>SQS: SendMessage to dispatch queue<br/>(reset attempt counter)

    DLM-->>DashAPI: 202 Accepted
    DashAPI-->>DashUI: Replay initiated
    DashUI-->>Operator: "Event queued for replay"

    SQS->>Worker: ReceiveMessage
    Worker->>Consumer: POST /webhooks<br/>(HMAC signed, fresh timestamp)

    alt Consumer now healthy
        Consumer-->>Worker: 200 OK
        Worker->>PG: INSERT INTO delivery_attempts<br/>(status: SUCCESS, attempt: 1, is_replay: true)
        Worker->>PG: UPDATE event_deliveries<br/>SET status = 'DELIVERED'
        Worker->>PG: UPDATE dead_letter_events<br/>SET status = 'REPLAYED'
    else Consumer still failing
        Note over Worker: Normal retry flow activates
    end
```

---

## 3. Inter-Component Data Contracts

### 3.1 SQS Message Schema (Outbox Poller → Dispatcher)

```json
{
  "messageId": "msg_01J5K...",
  "eventId": "evt_01J5K...",
  "subscriptionId": "sub_01J5K...",
  "tenantId": "tenant_acme",
  "eventType": "order.completed",
  "attemptNumber": 1,
  "maxAttempts": 8,
  "enqueuedAt": "2026-07-10T04:00:00.000Z",
  "outboxEntryId": "obx_01J5K..."
}
```

> [!NOTE]
> The SQS message does **not** contain the event payload. The Dispatcher Worker loads the full payload from PostgreSQL. This keeps SQS messages small (< 1 KB) and avoids SQS's 256 KB message size limit.

### 3.2 SQS Message Attributes

| Attribute | Type | Value | Purpose |
|---|---|---|---|
| `tenantId` | String | `tenant_acme` | SQS message filtering (future) |
| `eventType` | String | `order.completed` | SQS message filtering (future) |
| `attemptNumber` | Number | `1` | Retry tracking |
| `originalEnqueuedAt` | String | ISO-8601 timestamp | Latency tracking |

### 3.3 Database Shared State Contract

All services read/write to the same PostgreSQL database. Ownership boundaries:

| Table | Primary Writer | Readers |
|---|---|---|
| `events` | Ingest API | All services |
| `outbox` | Ingest API (insert), Poller (update) | Poller |
| `subscriptions` | Ingest API | Dispatcher, Dashboard API |
| `delivery_attempts` | Dispatcher Workers | Dashboard API, DLM |
| `event_deliveries` | Dispatcher Workers, DLM | Dashboard API |
| `dead_letter_events` | Dead-Letter Manager | Dashboard API |
| `tenants` | Dashboard API (admin) | Ingest API |
| `api_keys` | Dashboard API (admin) | Ingest API |

---

## 4. Error Handling at Component Boundaries

### 4.1 Error Categories and Handling

| Error Category | HTTP Status | Retryable | Action |
|---|---|---|---|
| **Client Error** | 400-499 (except 429) | ❌ No | Log, mark failed, send to DLQ immediately |
| **Rate Limited** | 429 | ✅ Yes | Respect `Retry-After` header, re-queue with delay |
| **Server Error** | 500-599 | ✅ Yes | Exponential backoff retry |
| **Connection Error** | N/A | ✅ Yes | Exponential backoff retry |
| **Timeout** | N/A | ✅ Yes | Exponential backoff retry |
| **DNS Resolution Failure** | N/A | ✅ Yes (limited) | Retry up to 3 times, then DLQ |
| **SSL/TLS Error** | N/A | ❌ No | Log, mark failed, alert, DLQ |

> [!WARNING]
> Client errors (4xx except 429) are **not retried**. If a consumer returns `401 Unauthorized` or `404 Not Found`, the event is immediately dead-lettered. This prevents wasting resources on permanently failing deliveries.

### 4.2 Circuit Breaker Thresholds

```java
@ConfigurationProperties(prefix = "eventrelay.circuit-breaker")
public class CircuitBreakerConfig {
    private int failureThreshold = 5;        // failures to open circuit
    private int successThreshold = 3;        // successes to close circuit
    private Duration halfOpenTimeout = Duration.ofSeconds(30);
    private Duration openTimeout = Duration.ofMinutes(5);
    private Duration windowSize = Duration.ofMinutes(1);
}
```

```mermaid
stateDiagram-v2
    [*] --> Closed
    Closed --> Open: failureCount >= 5<br/>within 1 minute
    Open --> HalfOpen: After 5 minutes
    HalfOpen --> Closed: 3 consecutive<br/>successes
    HalfOpen --> Open: Any failure

    state Closed {
        [*] --> Monitoring
        Monitoring: All requests pass through
        Monitoring: Track failure count
    }

    state Open {
        [*] --> Blocking
        Blocking: All requests fast-fail
        Blocking: Re-queue with delay
    }

    state HalfOpen {
        [*] --> Testing
        Testing: Allow limited requests
        Testing: Track success/failure
    }
```

---

## 5. Synchronous Communication Details

### 5.1 Ingest API — Internal Communication

```mermaid
graph LR
    subgraph IngestAPI["Ingest API Request Pipeline"]
        Filter["API Key Filter<br/>(Authentication)"]
        RateCheck["Rate Limit Check<br/>(Redis)"]
        DedupCheck["Idempotency Check<br/>(Redis → DB fallback)"]
        Validate["Payload Validation<br/>(Jackson + Jakarta Validation)"]
        Persist["Transactional Persist<br/>(PostgreSQL)"]
        Response["202 Accepted"]
    end

    Filter --> RateCheck --> DedupCheck --> Validate --> Persist --> Response

    Filter -->|"401"| Reject1["401 Unauthorized"]
    RateCheck -->|"429"| Reject2["429 Too Many Requests"]
    DedupCheck -->|"Duplicate"| Existing["200 OK (existing event)"]
    Validate -->|"Invalid"| Reject3["422 Unprocessable Entity"]
    Persist -->|"DB Error"| Reject4["503 Service Unavailable"]
```

### 5.2 Timeout Configuration

| Connection | Connect Timeout | Read Timeout | Idle Timeout |
|---|---|---|---|
| Ingest API → PostgreSQL | 5s | 10s | 600s (HikariCP) |
| Ingest API → Redis | 2s | 3s | 300s |
| Dispatcher → Consumer | 5s | 30s | N/A (per-request) |
| Dispatcher → PostgreSQL | 5s | 10s | 600s |
| Dispatcher → Redis | 2s | 3s | 300s |
| Dashboard API → PostgreSQL | 5s | 30s | 600s |

---

## 6. Asynchronous Communication Details

### 6.1 SQS Queue Configuration

| Queue | Type | Visibility Timeout | Message Retention | Max Receive Count | DLQ |
|---|---|---|---|---|---|
| `eventrelay-dispatch` | Standard | 60 seconds | 14 days | 8 | `eventrelay-dispatch-dlq` |
| `eventrelay-dispatch-dlq` | Standard | 300 seconds | 14 days | N/A | None |
| `eventrelay-replay` | Standard | 120 seconds | 7 days | 5 | `eventrelay-dispatch-dlq` |

### 6.2 Message Lifecycle in SQS

```mermaid
sequenceDiagram
    participant Poller as Outbox Poller
    participant Queue as SQS Queue
    participant Worker as Dispatcher Worker
    participant DLQ as SQS DLQ

    Poller->>Queue: SendMessage
    Note over Queue: Message AVAILABLE

    Queue->>Worker: ReceiveMessage
    Note over Queue: Message IN-FLIGHT<br/>(visibility timeout: 60s)

    alt Processing succeeds
        Worker->>Queue: DeleteMessage
        Note over Queue: Message DELETED
    else Processing fails (exception)
        Note over Queue: After visibility timeout expires<br/>Message becomes AVAILABLE again
        Note over Queue: receiveCount incremented
    else Max receive count reached
        Queue->>DLQ: Message moved to DLQ
        Note over DLQ: Message persisted in DLQ<br/>for manual inspection
    end
```

---

## 7. Health Check Dependencies

Each service implements health checks that verify its critical dependencies:

```java
@Component
public class CustomHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();

        // Check PostgreSQL
        details.put("database", checkDatabase());

        // Check Redis
        details.put("redis", checkRedis());

        // Check SQS (for services that use it)
        details.put("sqs", checkSqs());

        boolean allHealthy = details.values().stream()
            .allMatch(v -> "UP".equals(v));

        return allHealthy
            ? Health.up().withDetails(details).build()
            : Health.down().withDetails(details).build();
    }
}
```

| Service | Health Dependencies | Failure Action |
|---|---|---|
| Ingest API | PostgreSQL, Redis | ALB removes instance from target group |
| Outbox Poller | PostgreSQL, SQS | Standby takes over via leader election |
| Dispatcher | PostgreSQL, SQS, Redis | ECS restarts task |
| Dead-Letter Manager | PostgreSQL, SQS DLQ | Alerts fired; manual intervention |
| Dashboard API | PostgreSQL (replica) | ALB removes instance |

---

## 8. Related Documents

| Document | Description |
|---|---|
| [Architecture](./Architecture.md) | High-level system architecture |
| [Service Overview](./Service_Overview.md) | Individual service details |
| [Data Flow](./DataFlow.md) | End-to-end data flow diagrams |
| [Event Flow](./EventFlow.md) | Event state machine and lifecycle |
