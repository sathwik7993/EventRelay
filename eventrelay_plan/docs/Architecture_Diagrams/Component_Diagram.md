# Component Diagram — EventRelay Internal Architecture

> **Document Version:** 1.0  
> **Last Updated:** 2026-07-10  
> **Status:** Production Reference

## Overview

This document details the **internal component structure** of each service within EventRelay. Every service follows a layered architecture (Controller → Service → Repository) with cross-cutting concerns (security, rate limiting, observability) applied via Spring filters and interceptors.

---

## Full Component Diagram

```mermaid
graph TB
    subgraph IngestService ["Ingest Service (Spring Boot)"]
        subgraph IngestControllers ["Controllers (REST API)"]
            EC["EventController<br/>/api/v1/events"]
            TC["TenantController<br/>/api/v1/tenants"]
            SC["SubscriptionController<br/>/api/v1/subscriptions"]
            HC["HealthController<br/>/actuator/health"]
        end

        subgraph IngestFilters ["Servlet Filters & Interceptors"]
            AF["ApiKeyAuthFilter<br/>Validates X-API-Key header<br/>Resolves tenant context"]
            RL["RateLimitFilter<br/>Per-tenant request throttling<br/>Token bucket (Redis)"]
            RI["RequestIdInterceptor<br/>X-Request-Id propagation<br/>MDC logging context"]
        end

        subgraph IngestValidators ["Validators"]
            EV["EventValidator<br/>• Payload size ≤ 256KB<br/>• Required fields check<br/>• Event type format<br/>• JSON schema validation"]
            SV["SubscriptionValidator<br/>• URL reachability check<br/>• HTTPS enforcement<br/>• Event type existence<br/>• Duplicate prevention"]
            TV["TenantValidator<br/>• Name uniqueness<br/>• Quota limits check"]
        end

        subgraph IngestServices ["Service Layer"]
            ES["EventService<br/>• Accept event<br/>• Idempotency check<br/>• Transactional outbox write<br/>• Fan-out to subscriptions"]
            TS["TenantService<br/>• CRUD operations<br/>• API key rotation<br/>• Signing secret mgmt"]
            SS["SubscriptionService<br/>• CRUD operations<br/>• Event type filtering<br/>• Status management"]
            IS["IdempotencyService<br/>• Redis GET/SETEX<br/>• TTL: 24 hours<br/>• Key: tenant_id + idempotency_key"]
        end

        subgraph IngestRepos ["Repository Layer (Spring Data JPA)"]
            ER["EventRepository"]
            TR["TenantRepository"]
            SR["SubscriptionRepository"]
            OR["OutboxRepository"]
            AKR["ApiKeyRepository"]
        end

        AF --> RL --> RI --> EC & TC & SC
        EC --> EV --> ES
        TC --> TV --> TS
        SC --> SV --> SS
        ES --> IS
        ES --> ER & OR
        TS --> TR & AKR
        SS --> SR
    end

    subgraph OutboxPoller ["Outbox Poller Service (Spring Boot)"]
        subgraph PollerCore ["Core Components"]
            PS["PollerScheduler<br/>@Scheduled(fixedDelay=500ms)<br/>Polls outbox table"]
            LE["LeaderElection<br/>PostgreSQL advisory lock<br/>pg_try_advisory_lock()"]
            OBR["OutboxBatchReader<br/>SELECT FOR UPDATE SKIP LOCKED<br/>Batch size: 100"]
        end

        subgraph PollerPublisher ["Publisher"]
            SP["SqsPublisher<br/>SendMessageBatch API<br/>Max batch: 10 messages<br/>Retry with backoff"]
            OM["OutboxMarker<br/>UPDATE status = PROCESSED<br/>SET processed_at = NOW()"]
        end

        PS --> LE
        LE -- "Lock acquired" --> OBR
        OBR --> SP
        SP --> OM
    end

    subgraph Dispatcher ["Dispatcher Worker Service (Spring Boot)"]
        subgraph DispatchListener ["SQS Listener"]
            SL["SqsMessageListener<br/>@SqsListener<br/>Concurrency: 10 threads<br/>Long poll: 20s<br/>Visibility timeout: 60s"]
            MD["MessageDeserializer<br/>JSON → DeliveryTask POJO<br/>Schema validation"]
        end

        subgraph DispatchPipeline ["Delivery Pipeline"]
            RLC["RateLimitChecker<br/>Token bucket check (Redis)<br/>Per-tenant + per-endpoint"]
            CBC["CircuitBreakerChecker<br/>Check endpoint health state<br/>CLOSED → OPEN → HALF_OPEN"]
            PP["PayloadPreparer<br/>Resolve subscription config<br/>Apply event type filters<br/>Build HTTP request body"]
            HS["HmacSigner<br/>HMAC-SHA256 signing<br/>Construct: timestamp.payload<br/>Set X-EventRelay-Signature<br/>Set X-EventRelay-Timestamp"]
            HC2["HttpDeliveryClient<br/>Apache HttpClient 5<br/>Connection pool: 200<br/>Connect timeout: 5s<br/>Read timeout: 30s<br/>Follow redirects: false"]
        end

        subgraph DispatchResult ["Result Handling"]
            RH["ResponseHandler<br/>• 2xx → SUCCESS<br/>• 4xx → PERMANENT_FAILURE<br/>• 5xx → RETRYABLE_FAILURE<br/>• Timeout → RETRYABLE_FAILURE"]
            DAR["DeliveryAttemptRecorder<br/>INSERT delivery_attempts<br/>status, response_code,<br/>response_body (truncated),<br/>latency_ms"]
            RS["RetryScheduler<br/>Exponential backoff + jitter<br/>Delays: 1s, 5s, 30s, 5m, 30m, 1h, 6h<br/>Max attempts: 15<br/>Re-enqueue to SQS"]
            DLH["DeadLetterHandler<br/>INSERT dead_letter_events<br/>Publish DLQ metric<br/>Send alert notification"]
        end

        SL --> MD
        MD --> RLC --> CBC --> PP --> HS --> HC2
        HC2 --> RH
        RH --> DAR
        RH -- "Retryable" --> RS
        RH -- "Max retries exceeded" --> DLH
    end

    subgraph DashboardService ["Dashboard Service (Spring Boot)"]
        subgraph DashControllers ["Controllers"]
            DOC["OverviewController<br/>/api/dashboard/overview"]
            DEC["EventExplorerController<br/>/api/dashboard/events"]
            DDC["DeliveryMonitorController<br/>/api/dashboard/deliveries"]
            DRC["ReplayController<br/>/api/dashboard/replay"]
            DSC["DlqController<br/>/api/dashboard/dlq"]
        end

        subgraph DashServices ["Services"]
            MS["MetricsService<br/>Aggregate delivery stats<br/>Success/failure rates<br/>Latency percentiles"]
            EES["EventExplorerService<br/>Search & filter events<br/>Pagination & sorting"]
            RPS["ReplayService<br/>Fetch DLQ event<br/>Create new delivery<br/>Enqueue to SQS"]
        end

        DOC --> MS
        DEC --> EES
        DDC --> MS
        DRC & DSC --> RPS
    end

    subgraph SharedComponents ["Shared Libraries (Common Module)"]
        subgraph Security ["Security"]
            HMAC["HmacUtils<br/>HMAC-SHA256 computation<br/>Constant-time comparison"]
            AKV["ApiKeyValidator<br/>Key lookup & hash verify<br/>BCrypt hashed storage"]
        end

        subgraph Resilience ["Resilience"]
            RLM["RateLimiter<br/>Token bucket algorithm<br/>Redis Lua script (atomic)<br/>Configurable per tenant"]
            CBR["CircuitBreaker<br/>States: CLOSED, OPEN, HALF_OPEN<br/>Failure threshold: 5 consecutive<br/>Recovery timeout: 60s<br/>Half-open probe: 1 request"]
        end

        subgraph Observability2 ["Observability"]
            MM["MetricsManager<br/>Micrometer registry<br/>Custom counters, gauges,<br/>histograms, timers"]
            SL2["StructuredLogger<br/>JSON-formatted logs<br/>Correlation ID propagation<br/>Tenant context injection"]
        end

        subgraph Models ["Domain Models"]
            EM["Event<br/>Tenant<br/>Subscription<br/>DeliveryAttempt<br/>OutboxEntry<br/>DeadLetterEvent<br/>ApiKey"]
        end
    end

    %% External Dependencies
    PG[("PostgreSQL")]
    RD[("Redis")]
    SQS["AWS SQS"]

    IngestRepos --> PG
    IS --> RD
    RL --> RD
    SP --> SQS
    OBR --> PG
    OM --> PG
    SL --> SQS
    RLC --> RD
    CBC --> RD
    DAR --> PG
    RS --> SQS
    DLH --> PG
    MS & EES & RPS --> PG

    classDef controller fill:#bbdefb,stroke:#1565c0,stroke-width:1px
    classDef filter fill:#fff9c4,stroke:#f9a825,stroke-width:1px
    classDef validator fill:#ffe0b2,stroke:#e65100,stroke-width:1px
    classDef service fill:#c8e6c9,stroke:#2e7d32,stroke-width:1px
    classDef repo fill:#e1bee7,stroke:#6a1b9a,stroke-width:1px
    classDef shared fill:#d7ccc8,stroke:#4e342e,stroke-width:1px
    classDef external fill:#ffcdd2,stroke:#b71c1c,stroke-width:2px

    class EC,TC,SC,HC,DOC,DEC,DDC,DRC,DSC controller
    class AF,RL,RI filter
    class EV,SV,TV validator
    class ES,TS,SS,IS,PS,LE,OBR,SP,OM,RLC,CBC,PP,HS,HC2,RH,DAR,RS,DLH,MS,EES,RPS,SL,MD service
    class ER,TR,SR,OR,AKR repo
    class HMAC,AKV,RLM,CBR,MM,SL2,EM shared
    class PG,RD,SQS external
```

---

## Service Decomposition Details

### Ingest Service

```mermaid
graph LR
    subgraph RequestFlow ["Request Processing Pipeline"]
        direction LR
        REQ["HTTP Request"] --> F1["ApiKeyAuthFilter"]
        F1 --> F2["RateLimitFilter"]
        F2 --> F3["RequestIdInterceptor"]
        F3 --> CTRL["Controller"]
        CTRL --> VAL["Validator"]
        VAL --> SVC["Service"]
        SVC --> REPO["Repository"]
        REPO --> DB[("PostgreSQL")]
    end

    style REQ fill:#e3f2fd
    style DB fill:#ffcdd2
```

| Layer | Responsibility | Key Classes |
|---|---|---|
| **Filters** | Cross-cutting: auth, rate limiting, request tracking | `ApiKeyAuthFilter`, `RateLimitFilter`, `RequestIdInterceptor` |
| **Controllers** | HTTP binding, request/response mapping, status codes | `EventController`, `TenantController`, `SubscriptionController` |
| **Validators** | Input validation, business rule checks | `EventValidator`, `SubscriptionValidator` |
| **Services** | Business logic, transaction management, orchestration | `EventService`, `TenantService`, `IdempotencyService` |
| **Repositories** | Data access, query execution | `EventRepository`, `OutboxRepository`, `TenantRepository` |

### Dispatcher Worker — Delivery Pipeline

```mermaid
graph LR
    subgraph Pipeline ["Delivery Pipeline (per message)"]
        direction LR
        MSG["SQS Message"] --> DESER["Deserialize"]
        DESER --> RATE["Rate Limit<br/>Check"]
        RATE --> CB["Circuit Breaker<br/>Check"]
        CB --> PREP["Prepare<br/>Payload"]
        PREP --> SIGN["HMAC<br/>Sign"]
        SIGN --> POST["HTTP<br/>POST"]
        POST --> HANDLE["Handle<br/>Response"]
    end

    RATE -. "Rate exceeded" .-> REQUEUE["Re-enqueue<br/>with delay"]
    CB -. "Circuit open" .-> REQUEUE
    HANDLE -. "Retryable failure" .-> RETRY["Schedule<br/>Retry"]
    HANDLE -. "Permanent failure /<br/>Max retries" .-> DLQ["Dead Letter<br/>Handler"]
    HANDLE -- "Success" --> DELETE["Delete<br/>SQS Message"]

    style MSG fill:#f3e5f5
    style DELETE fill:#c8e6c9
    style DLQ fill:#ffcdd2
```

| Stage | Component | Failure Behavior |
|---|---|---|
| **Deserialize** | `MessageDeserializer` | Log error + delete poison message |
| **Rate Limit** | `RateLimitChecker` | Re-enqueue with 1s delay |
| **Circuit Breaker** | `CircuitBreakerChecker` | Re-enqueue with recovery timeout delay |
| **Prepare** | `PayloadPreparer` | Log error + dead-letter |
| **HMAC Sign** | `HmacSigner` | Log error + dead-letter (config issue) |
| **HTTP POST** | `HttpDeliveryClient` | Return response/exception to handler |
| **Handle Response** | `ResponseHandler` | Route to retry or dead-letter |

---

## Shared Library Components

### Rate Limiter — Token Bucket (Redis Lua Script)

```mermaid
stateDiagram-v2
    [*] --> CheckTokens
    CheckTokens --> HasTokens: tokens > 0
    CheckTokens --> NoTokens: tokens = 0
    HasTokens --> DecrementToken: Atomic DECR
    DecrementToken --> AllowRequest
    NoTokens --> RejectRequest: Return 429
    AllowRequest --> [*]
    RejectRequest --> [*]
```

| Parameter | Default Value | Configurable Per |
|---|---|---|
| Bucket capacity | 100 requests | Tenant |
| Refill rate | 100 requests/second | Tenant |
| Refill interval | 1 second | Global |

### Circuit Breaker — State Machine

```mermaid
stateDiagram-v2
    [*] --> CLOSED
    CLOSED --> OPEN: Failure threshold reached<br/>(5 consecutive failures)
    OPEN --> HALF_OPEN: Recovery timeout elapsed<br/>(60 seconds)
    HALF_OPEN --> CLOSED: Probe request succeeds
    HALF_OPEN --> OPEN: Probe request fails

    state CLOSED {
        [*] --> Tracking
        Tracking: Count consecutive failures
        Tracking: Reset counter on success
    }

    state OPEN {
        [*] --> Rejecting
        Rejecting: All requests fast-fail
        Rejecting: Timer running
    }

    state HALF_OPEN {
        [*] --> Probing
        Probing: Allow 1 probe request
        Probing: Evaluate result
    }
```

| Parameter | Value | Notes |
|---|---|---|
| Failure threshold | 5 consecutive | Per endpoint (tenant + URL combination) |
| Recovery timeout | 60 seconds | Time before transitioning OPEN → HALF_OPEN |
| Half-open probe count | 1 request | Only 1 request allowed through to test recovery |
| State storage | Redis | Key: `cb:{tenant_id}:{endpoint_hash}` |
| State TTL | 24 hours | Auto-cleanup of stale circuit breaker state |

---

## Package Structure

```
com.eventrelay
├── ingest/
│   ├── controller/
│   │   ├── EventController.java
│   │   ├── TenantController.java
│   │   └── SubscriptionController.java
│   ├── filter/
│   │   ├── ApiKeyAuthFilter.java
│   │   ├── RateLimitFilter.java
│   │   └── RequestIdInterceptor.java
│   ├── validator/
│   │   ├── EventValidator.java
│   │   ├── SubscriptionValidator.java
│   │   └── TenantValidator.java
│   ├── service/
│   │   ├── EventService.java
│   │   ├── TenantService.java
│   │   ├── SubscriptionService.java
│   │   └── IdempotencyService.java
│   └── repository/
│       ├── EventRepository.java
│       ├── TenantRepository.java
│       ├── SubscriptionRepository.java
│       ├── OutboxRepository.java
│       └── ApiKeyRepository.java
├── poller/
│   ├── PollerScheduler.java
│   ├── LeaderElection.java
│   ├── OutboxBatchReader.java
│   ├── SqsPublisher.java
│   └── OutboxMarker.java
├── dispatcher/
│   ├── listener/
│   │   ├── SqsMessageListener.java
│   │   └── MessageDeserializer.java
│   ├── pipeline/
│   │   ├── RateLimitChecker.java
│   │   ├── CircuitBreakerChecker.java
│   │   ├── PayloadPreparer.java
│   │   ├── HmacSigner.java
│   │   └── HttpDeliveryClient.java
│   └── result/
│       ├── ResponseHandler.java
│       ├── DeliveryAttemptRecorder.java
│       ├── RetryScheduler.java
│       └── DeadLetterHandler.java
├── dashboard/
│   ├── controller/
│   ├── service/
│   └── dto/
└── common/
    ├── security/
    │   ├── HmacUtils.java
    │   └── ApiKeyValidator.java
    ├── resilience/
    │   ├── RateLimiter.java
    │   └── CircuitBreaker.java
    ├── observability/
    │   ├── MetricsManager.java
    │   └── StructuredLogger.java
    └── model/
        ├── Event.java
        ├── Tenant.java
        ├── Subscription.java
        ├── DeliveryAttempt.java
        ├── OutboxEntry.java
        ├── DeadLetterEvent.java
        └── ApiKey.java
```

---

## Inter-Component Communication Matrix

| From | To | Protocol | Pattern | Notes |
|---|---|---|---|---|
| Ingest Service | PostgreSQL | JDBC | Synchronous | Transactional writes |
| Ingest Service | Redis | Redis protocol | Synchronous | Idempotency check, rate limit |
| Outbox Poller | PostgreSQL | JDBC | Polling (500ms) | `SELECT FOR UPDATE SKIP LOCKED` |
| Outbox Poller | SQS | AWS SDK | Async batch send | `SendMessageBatch` (up to 10) |
| Dispatcher | SQS | AWS SDK | Long polling (20s) | `ReceiveMessage` |
| Dispatcher | Redis | Redis protocol | Synchronous | Rate limit + circuit breaker |
| Dispatcher | PostgreSQL | JDBC | Synchronous | Record delivery attempt |
| Dispatcher | Target URLs | HTTP/HTTPS | Synchronous (30s timeout) | HMAC-signed POST |
| Dashboard | PostgreSQL | JDBC | Synchronous | Read queries |
| Dashboard | SQS DLQ | AWS SDK | Synchronous | Read DLQ for replay |

---

## Related Documents

- [System Overview](../Architecture_Diagrams/System_Overview.md) — High-level architecture
- [Deployment Diagram](../Architecture_Diagrams/Deployment_Diagram.md) — AWS infrastructure
- [Event Ingestion Sequence](../Sequence_Diagrams/Event_Ingestion.md) — Detailed request flow
- [HMAC Signing Sequence](../Sequence_Diagrams/HMAC_Signing.md) — Signing implementation details
