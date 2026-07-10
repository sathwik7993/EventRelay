# Metrics Catalog

## Overview

This document is the authoritative catalog of all custom and system metrics emitted by EventRelay. Every metric listed here is registered via Micrometer and exposed to Prometheus at the `/actuator/prometheus` endpoint.

> [!NOTE]
> Micrometer uses dot-separated names (e.g., `eventrelay.events.received`), which are automatically converted to Prometheus snake_case format (e.g., `eventrelay_events_received_total`). This document uses the **Prometheus naming convention** throughout.

---

## Metric Summary Table

| Category | Metric | Type | Critical? |
|----------|--------|------|-----------|
| Ingestion | `eventrelay_events_received_total` | Counter | ✅ |
| Ingestion | `eventrelay_events_validated_total` | Counter | |
| Ingestion | `eventrelay_events_rejected_total` | Counter | ✅ |
| Ingestion | `eventrelay_ingestion_latency_seconds` | Histogram | ✅ |
| Delivery | `eventrelay_deliveries_total` | Counter | ✅ |
| Delivery | `eventrelay_delivery_latency_seconds` | Histogram | ✅ |
| Delivery | `eventrelay_delivery_attempts_total` | Counter | |
| Delivery | `eventrelay_delivery_payload_size_bytes` | Histogram | |
| Queue | `eventrelay_queue_depth` | Gauge | ✅ |
| Queue | `eventrelay_queue_age_seconds` | Gauge | ✅ |
| Queue | `eventrelay_queue_messages_sent_total` | Counter | |
| Queue | `eventrelay_queue_messages_received_total` | Counter | |
| Rate Limiting | `eventrelay_rate_limited_total` | Counter | ✅ |
| Rate Limiting | `eventrelay_rate_limit_remaining` | Gauge | |
| DLQ | `eventrelay_dlq_events_total` | Counter | ✅ |
| DLQ | `eventrelay_dlq_replayed_total` | Counter | |
| DLQ | `eventrelay_dlq_depth` | Gauge | ✅ |
| Circuit Breaker | `eventrelay_circuit_breaker_state` | Gauge | ✅ |
| Circuit Breaker | `eventrelay_circuit_breaker_calls_total` | Counter | |
| System | JVM metrics (auto-registered) | Various | |
| System | HikariCP metrics (auto-registered) | Various | |
| System | HTTP server metrics (auto-registered) | Various | |

---

## Ingestion Metrics

### `eventrelay_events_received_total`

Events successfully accepted by the Ingest API and written to the outbox table.

| Property | Value |
|----------|-------|
| **Type** | Counter |
| **Unit** | events |
| **Labels** | `tenant_id`, `event_type`, `api_version` |
| **Source** | Ingest API |
| **Usage** | Throughput monitoring, tenant usage tracking, billing |

```java
// Registration
Counter eventsReceived = Counter.builder("eventrelay.events.received")
    .description("Total events successfully ingested and written to outbox")
    .tag("tenant_id", tenantId)
    .tag("event_type", eventType)
    .tag("api_version", "v1")
    .register(meterRegistry);

// Usage
eventsReceived.increment();
```

**Key PromQL Queries:**

```promql
# Events per second (global)
sum(rate(eventrelay_events_received_total[5m]))

# Events per second by tenant
sum(rate(eventrelay_events_received_total[5m])) by (tenant_id)

# Top 10 tenants by volume (last hour)
topk(10, sum(increase(eventrelay_events_received_total[1h])) by (tenant_id))
```

---

### `eventrelay_events_validated_total`

Events that passed schema validation (subset of received events).

| Property | Value |
|----------|-------|
| **Type** | Counter |
| **Unit** | events |
| **Labels** | `tenant_id`, `event_type`, `validation_result` |
| **Source** | Ingest API |
| **Usage** | Validation success rate, schema compliance tracking |

```java
Counter eventsValidated = Counter.builder("eventrelay.events.validated")
    .description("Events that passed payload validation")
    .tag("tenant_id", tenantId)
    .tag("event_type", eventType)
    .tag("validation_result", "success") // or "failed"
    .register(meterRegistry);
```

---

### `eventrelay_events_rejected_total`

Events rejected at ingestion (invalid payload, auth failure, rate limited, etc.).

| Property | Value |
|----------|-------|
| **Type** | Counter |
| **Unit** | events |
| **Labels** | `tenant_id`, `rejection_reason` |
| **Source** | Ingest API |
| **Usage** | API error rate monitoring, tenant integration health |

Label values for `rejection_reason`:

| Value | Description |
|-------|-------------|
| `invalid_payload` | JSON parse error or schema validation failure |
| `authentication_failed` | Invalid or missing API key |
| `rate_limited` | Tenant exceeded rate limit |
| `payload_too_large` | Payload exceeds 1MB limit |
| `invalid_event_type` | Unknown event type |
| `tenant_disabled` | Tenant account suspended |

---

### `eventrelay_ingestion_latency_seconds`

End-to-end time from receiving an HTTP request to returning a response (includes validation, outbox write, and response serialization).

| Property | Value |
|----------|-------|
| **Type** | Histogram |
| **Unit** | seconds |
| **Labels** | `tenant_id`, `event_type`, `status` |
| **Buckets** | 5ms, 10ms, 25ms, 50ms, 100ms, 250ms, 500ms, 1s, 2.5s, 5s, 10s |
| **Source** | Ingest API |
| **SLO** | p99 < 500ms |

```java
Timer ingestionLatency = Timer.builder("eventrelay.ingestion.latency")
    .description("Ingestion latency from request receipt to response")
    .publishPercentiles(0.5, 0.75, 0.9, 0.95, 0.99)
    .publishPercentileHistogram()
    .serviceLevelObjectives(
        Duration.ofMillis(50),
        Duration.ofMillis(100),
        Duration.ofMillis(250),
        Duration.ofMillis(500)
    )
    .tag("tenant_id", tenantId)
    .tag("event_type", eventType)
    .tag("status", "success")
    .register(meterRegistry);

// Usage
ingestionLatency.record(() -> {
    return processEvent(event);
});
```

**Key PromQL Queries:**

```promql
# p99 ingestion latency
histogram_quantile(0.99, sum(rate(eventrelay_ingestion_latency_seconds_bucket[5m])) by (le))

# p99 per tenant
histogram_quantile(0.99, sum(rate(eventrelay_ingestion_latency_seconds_bucket[5m])) by (le, tenant_id))

# SLO compliance: % of requests under 500ms
sum(rate(eventrelay_ingestion_latency_seconds_bucket{le="0.5"}[5m]))
/ sum(rate(eventrelay_ingestion_latency_seconds_count[5m])) * 100
```

---

## Delivery Metrics

### `eventrelay_deliveries_total`

Total webhook delivery attempts with status outcome.

| Property | Value |
|----------|-------|
| **Type** | Counter |
| **Unit** | deliveries |
| **Labels** | `tenant_id`, `event_type`, `status`, `http_status`, `endpoint_host` |
| **Source** | Dispatcher Worker |
| **Usage** | Delivery success rate (primary SLI), error analysis |

Label values for `status`:

| Value | Description |
|-------|-------------|
| `success` | HTTP 2xx received from target |
| `failed` | HTTP 4xx/5xx received or connection error |
| `timeout` | Target did not respond within 30s |
| `circuit_open` | Circuit breaker prevented attempt |
| `rate_limited` | Per-tenant rate limit exceeded |

```java
Counter deliveries = Counter.builder("eventrelay.deliveries")
    .description("Total webhook delivery attempts")
    .tag("tenant_id", tenantId)
    .tag("event_type", eventType)
    .tag("status", status)        // success, failed, timeout, circuit_open
    .tag("http_status", String.valueOf(httpStatus)) // 200, 500, etc. (0 for connection errors)
    .tag("endpoint_host", endpointHost)
    .register(meterRegistry);
```

**Key PromQL Queries:**

```promql
# Delivery success rate (primary SLI)
sum(rate(eventrelay_deliveries_total{status="success"}[5m]))
/ sum(rate(eventrelay_deliveries_total[5m])) * 100

# Failure rate by HTTP status
sum(rate(eventrelay_deliveries_total{status="failed"}[5m])) by (http_status)

# Per-tenant success rate
sum(rate(eventrelay_deliveries_total{status="success"}[5m])) by (tenant_id)
/ sum(rate(eventrelay_deliveries_total[5m])) by (tenant_id) * 100
```

---

### `eventrelay_delivery_latency_seconds`

Time from dispatch start to receiving a response from the target endpoint.

| Property | Value |
|----------|-------|
| **Type** | Histogram |
| **Unit** | seconds |
| **Labels** | `tenant_id`, `status`, `endpoint_host` |
| **Buckets** | 50ms, 100ms, 250ms, 500ms, 1s, 2.5s, 5s, 10s, 30s, 60s |
| **Source** | Dispatcher Worker |
| **SLO** | p99 first-attempt < 500ms |

```java
Timer deliveryLatency = Timer.builder("eventrelay.delivery.latency")
    .description("Webhook delivery latency (dispatch to response)")
    .publishPercentiles(0.5, 0.75, 0.9, 0.95, 0.99)
    .publishPercentileHistogram()
    .serviceLevelObjectives(
        Duration.ofMillis(100),
        Duration.ofMillis(250),
        Duration.ofMillis(500),
        Duration.ofSeconds(1),
        Duration.ofSeconds(5)
    )
    .minimumExpectedValue(Duration.ofMillis(10))
    .maximumExpectedValue(Duration.ofSeconds(60))
    .tag("tenant_id", tenantId)
    .tag("status", status)
    .tag("endpoint_host", endpointHost)
    .register(meterRegistry);
```

---

### `eventrelay_delivery_attempts_total`

Counts each individual delivery attempt (including retries) — distinct from `deliveries_total` which counts first + retry attempts but tracks the final outcome.

| Property | Value |
|----------|-------|
| **Type** | Counter |
| **Unit** | attempts |
| **Labels** | `tenant_id`, `event_type`, `attempt_number` |
| **Source** | Dispatcher Worker, Retry Engine |
| **Usage** | Retry amplification ratio, per-attempt success analysis |

```java
Counter deliveryAttempts = Counter.builder("eventrelay.delivery.attempts")
    .description("Individual delivery attempts including retries")
    .tag("tenant_id", tenantId)
    .tag("event_type", eventType)
    .tag("attempt_number", String.valueOf(attemptNumber))
    .register(meterRegistry);
```

**Key PromQL Queries:**

```promql
# Retry amplification ratio (total attempts / unique events)
sum(rate(eventrelay_delivery_attempts_total[5m]))
/ sum(rate(eventrelay_deliveries_total[5m]))

# Retry distribution (what % of events need N retries)
sum(rate(eventrelay_delivery_attempts_total{attempt_number!="1"}[5m]))
/ sum(rate(eventrelay_delivery_attempts_total[5m])) * 100
```

---

### `eventrelay_delivery_payload_size_bytes`

Size of the webhook payload sent to the target.

| Property | Value |
|----------|-------|
| **Type** | Histogram |
| **Unit** | bytes |
| **Labels** | `tenant_id`, `event_type` |
| **Buckets** | 256, 512, 1KB, 4KB, 16KB, 64KB, 256KB, 1MB |
| **Source** | Dispatcher Worker |

---

## Queue Metrics

### `eventrelay_queue_depth`

Current number of messages in the SQS queue (visible + in-flight).

| Property | Value |
|----------|-------|
| **Type** | Gauge |
| **Unit** | messages |
| **Labels** | `queue_name` |
| **Source** | Queue Monitor (scheduled task) |
| **Alert** | Warning if > 10,000 for 5+ minutes |

```java
Gauge.builder("eventrelay.queue.depth", sqsClient, client -> {
        GetQueueAttributesResponse attrs = client.getQueueAttributes(
            GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)
                .build()
        );
        return Double.parseDouble(attrs.attributes().get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
             + Double.parseDouble(attrs.attributes().get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
    })
    .description("Current SQS queue depth (visible + in-flight)")
    .tag("queue_name", "eventrelay-delivery")
    .register(meterRegistry);
```

---

### `eventrelay_queue_age_seconds`

Age of the oldest message in the queue — measures processing lag.

| Property | Value |
|----------|-------|
| **Type** | Gauge |
| **Unit** | seconds |
| **Labels** | `queue_name` |
| **Source** | Queue Monitor (scheduled task, polled every 30s) |
| **Alert** | Critical if > 3600s (1 hour) |

```java
Gauge.builder("eventrelay.queue.age", sqsClient, client -> {
        // Use SQS ApproximateAgeOfOldestMessage attribute
        GetQueueAttributesResponse attrs = client.getQueueAttributes(
            GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.APPROXIMATE_AGE_OF_OLDEST_MESSAGE)
                .build()
        );
        return Double.parseDouble(
            attrs.attributes().getOrDefault(
                QueueAttributeName.APPROXIMATE_AGE_OF_OLDEST_MESSAGE, "0"
            )
        );
    })
    .description("Age of oldest message in the queue (seconds)")
    .tag("queue_name", "eventrelay-delivery")
    .register(meterRegistry);
```

---

### `eventrelay_queue_messages_sent_total` / `eventrelay_queue_messages_received_total`

Messages sent to and received from SQS.

| Property | Value |
|----------|-------|
| **Type** | Counter |
| **Labels** | `queue_name` |
| **Source** | Ingest API (sent), Dispatcher Worker (received) |
| **Usage** | Queue throughput, producer-consumer balance |

---

## Rate Limiting Metrics

### `eventrelay_rate_limited_total`

Requests rejected due to per-tenant rate limiting.

| Property | Value |
|----------|-------|
| **Type** | Counter |
| **Labels** | `tenant_id`, `limit_type` |
| **Source** | Ingest API, Dispatcher Worker |
| **Usage** | Rate limit hit frequency, tenant capacity planning |

Label values for `limit_type`:

| Value | Description |
|-------|-------------|
| `ingestion` | Ingestion API rate limit |
| `delivery` | Delivery rate limit per endpoint |
| `burst` | Burst limit exceeded |

```java
Counter rateLimited = Counter.builder("eventrelay.rate.limited")
    .description("Requests rejected due to rate limiting")
    .tag("tenant_id", tenantId)
    .tag("limit_type", "ingestion")
    .register(meterRegistry);
```

---

### `eventrelay_rate_limit_remaining`

Remaining tokens in the per-tenant token bucket.

| Property | Value |
|----------|-------|
| **Type** | Gauge |
| **Labels** | `tenant_id` |
| **Source** | Rate Limiter (Redis-backed) |
| **Usage** | Capacity headroom monitoring |

---

## Dead-Letter Queue Metrics

### `eventrelay_dlq_events_total`

Events moved to the dead-letter queue after exhausting all retry attempts.

| Property | Value |
|----------|-------|
| **Type** | Counter |
| **Labels** | `tenant_id`, `event_type`, `failure_reason` |
| **Source** | Retry Engine |
| **Alert** | Critical if rate > 0 sustained for 5 minutes |

Label values for `failure_reason`:

| Value | Description |
|-------|-------------|
| `max_retries_exhausted` | All retry attempts failed |
| `permanent_failure` | HTTP 4xx (non-retryable) |
| `circuit_breaker` | Circuit breaker open for extended period |
| `payload_rejected` | Target explicitly rejected payload |

---

### `eventrelay_dlq_replayed_total`

Events manually or automatically replayed from the DLQ.

| Property | Value |
|----------|-------|
| **Type** | Counter |
| **Labels** | `tenant_id`, `replay_trigger` |
| **Source** | DLQ Processor |

---

### `eventrelay_dlq_depth`

Current number of events sitting in the dead-letter queue.

| Property | Value |
|----------|-------|
| **Type** | Gauge |
| **Labels** | `tenant_id` |
| **Source** | DLQ Monitor |
| **Alert** | Warning if > 50, Critical if > 100 |

---

## Circuit Breaker Metrics

### `eventrelay_circuit_breaker_state`

Current state of the circuit breaker per endpoint.

| Property | Value |
|----------|-------|
| **Type** | Gauge |
| **Labels** | `tenant_id`, `endpoint_host` |
| **Values** | `0` = CLOSED (healthy), `1` = HALF_OPEN, `2` = OPEN (tripping) |
| **Source** | Dispatcher Worker |

```java
Gauge.builder("eventrelay.circuit.breaker.state", circuitBreaker, cb -> {
        switch (cb.getState()) {
            case CLOSED: return 0.0;
            case HALF_OPEN: return 1.0;
            case OPEN: return 2.0;
            default: return -1.0;
        }
    })
    .description("Circuit breaker state (0=closed, 1=half-open, 2=open)")
    .tag("tenant_id", tenantId)
    .tag("endpoint_host", endpointHost)
    .register(meterRegistry);
```

---

### `eventrelay_circuit_breaker_calls_total`

Calls routed through the circuit breaker.

| Property | Value |
|----------|-------|
| **Type** | Counter |
| **Labels** | `tenant_id`, `endpoint_host`, `result` |
| **Source** | Dispatcher Worker |

Label values for `result`: `success`, `failure`, `not_permitted` (circuit open).

---

## System Metrics (Auto-Registered)

These metrics are automatically registered by Spring Boot Actuator and Micrometer. No custom code needed.

### JVM Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `jvm_memory_used_bytes` | Gauge | JVM memory usage by area (heap/non-heap) and pool |
| `jvm_memory_max_bytes` | Gauge | Maximum JVM memory |
| `jvm_memory_committed_bytes` | Gauge | Committed JVM memory |
| `jvm_gc_pause_seconds` | Timer | GC pause duration |
| `jvm_gc_memory_promoted_bytes_total` | Counter | Memory promoted to old gen |
| `jvm_threads_live_threads` | Gauge | Current live threads |
| `jvm_threads_peak_threads` | Gauge | Peak thread count |
| `jvm_threads_states_threads` | Gauge | Threads by state |
| `jvm_classes_loaded_classes` | Gauge | Currently loaded classes |

### HikariCP Connection Pool Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `hikaricp_connections` | Gauge | Total connections in pool |
| `hikaricp_connections_active` | Gauge | Active connections |
| `hikaricp_connections_idle` | Gauge | Idle connections |
| `hikaricp_connections_pending` | Gauge | Threads waiting for connection |
| `hikaricp_connections_timeout_total` | Counter | Connection timeout count |
| `hikaricp_connections_acquire_seconds` | Timer | Connection acquisition time |
| `hikaricp_connections_usage_seconds` | Timer | Connection usage duration |
| `hikaricp_connections_creation_seconds` | Timer | Connection creation time |

> [!WARNING]
> **Alert on `hikaricp_connections_pending > 0`** sustained for more than 30 seconds — this indicates connection pool exhaustion which will cascade into request failures.

### HTTP Server Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `http_server_requests_seconds` | Timer | HTTP request duration |
| `http_server_requests_seconds_count` | Counter | Total HTTP requests |
| `http_server_requests_active_seconds` | Timer | Currently active requests |

### Tomcat Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `tomcat_threads_current_threads` | Gauge | Current Tomcat threads |
| `tomcat_threads_busy_threads` | Gauge | Busy Tomcat threads |
| `tomcat_threads_config_max_threads` | Gauge | Max configured threads |

### Logback Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `logback_events_total` | Counter | Log events by level (error, warn, info, debug) |

> [!TIP]
> **Quick health check query**: `rate(logback_events_total{level="error"}[5m]) > 0.1` — triggers when error log rate exceeds 6 errors per minute.

---

## Cardinality Budget

Total estimated metric cardinality for monitoring:

| Category | Metrics | Avg Labels | Est. Series |
|----------|---------|------------|-------------|
| Ingestion | 4 | 100 tenants × 20 types | 8,000 |
| Delivery | 4 | 100 tenants × 5 statuses | 2,000 |
| Queue | 4 | 3 queues | 12 |
| Rate Limiting | 2 | 100 tenants | 200 |
| DLQ | 3 | 100 tenants | 300 |
| Circuit Breaker | 2 | 100 tenants × 3 states | 600 |
| JVM | 20 | 5 instances | 100 |
| HikariCP | 8 | 5 instances | 40 |
| HTTP | 3 | 10 endpoints × 5 instances | 150 |
| **Total** | | | **~11,400** |

> [!NOTE]
> This is well within Prometheus's comfortable operating range (typically handles 1M+ active series). Monitor `prometheus_tsdb_head_series` to track actual cardinality.

---

## Related Documents

- [Prometheus.md](./Prometheus.md) — Prometheus configuration and deployment
- [Dashboards.md](./Dashboards.md) — Dashboard designs using these metrics
- [Alerting.md](./Alerting.md) — Alert rules built on these metrics
- [SLA_SLO.md](./SLA_SLO.md) — SLI definitions using these metrics
