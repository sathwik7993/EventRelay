# Retry Strategies

> **Document Status**: Production Reference  
> **Last Updated**: 2026-07-10  
> **Audience**: Backend Engineers, SREs  
> **Related Documents**: [Circuit_Breakers.md](./Circuit_Breakers.md), [Delivery_Guarantees.md](./Delivery_Guarantees.md), [Failure_Scenarios.md](./Failure_Scenarios.md)

---

## 1. Overview

Retry strategies determine when and how EventRelay re-attempts failed webhook deliveries. The choice of strategy has direct impact on:

- **Recovery speed** — how quickly transient failures are resolved
- **Thundering herd avoidance** — preventing simultaneous retries from overwhelming recovering endpoints
- **Resource utilization** — CPU, memory, and network consumed by retries
- **Consumer experience** — delivery latency during failure periods

EventRelay uses **exponential backoff with full jitter** as its default strategy, based on AWS's research and industry best practices.

---

## 2. Strategy Comparison

| Strategy | Formula | Thundering Herd | Spread | Complexity | Used By |
|---|---|---|---|---|---|
| Immediate | `delay = 0` | ❌ Worst | None | Trivial | — |
| Fixed Delay | `delay = constant` | ❌ Bad | None | Low | Legacy systems |
| Linear Backoff | `delay = attempt × base` | ⚠️ Moderate | Low | Low | Some queues |
| Exponential Backoff | `delay = base × 2^attempt` | ⚠️ Moderate | None | Medium | Basic implementations |
| Exp. Backoff + Full Jitter | `delay = random(0, base × 2^attempt)` | ✅ Good | High | Medium | **EventRelay**, AWS |
| Exp. Backoff + Equal Jitter | `delay = base × 2^attempt / 2 + random(0, base × 2^attempt / 2)` | ✅ Good | Medium | Medium | Some AWS services |
| Decorrelated Jitter | `delay = random(base, prev_delay × 3)` | ✅ Best | Highest | Medium | AWS recommendation |

---

## 3. Mathematical Foundations

### 3.1 Immediate Retry

```
delay(attempt) = 0
```

No delay between attempts. Only suitable for transient blips where immediate retry is expected to succeed.

**Problems**: Creates a tight retry loop that can overwhelm both the sender and receiver. Never use for webhook delivery.

### 3.2 Fixed Delay

```
delay(attempt) = D

where:
  D = fixed delay constant (e.g., 5 seconds)
```

Every retry waits the same duration regardless of attempt number.

**Problems**: No backoff means no relief for overloaded endpoints. All retries for all events land at the same intervals, creating periodic spikes.

### 3.3 Exponential Backoff (No Jitter)

```
delay(attempt) = min(base × 2^attempt, cap)

where:
  base = initial delay (e.g., 1 second)
  cap  = maximum delay (e.g., 4 hours)
```

Each retry waits exponentially longer. However, all clients with the same failure time retry at the same moments, causing **correlated retries**.

### 3.4 Exponential Backoff with Full Jitter ⭐ (EventRelay Default)

```
delay(attempt) = random(0, min(base × 2^attempt, cap))

where:
  base   = initial delay (1 second)
  cap    = maximum delay (14400 seconds = 4 hours)
  random = uniform random in [0, upper_bound)
```

Full jitter spreads retries uniformly across the entire backoff window, maximizing temporal spread and minimizing collision probability.

### 3.5 Exponential Backoff with Equal Jitter

```
temp = min(base × 2^attempt, cap)
delay(attempt) = temp / 2 + random(0, temp / 2)

where:
  The delay is guaranteed to be at least half the exponential value
```

Equal jitter provides a minimum guaranteed delay (half the exponential value) while still adding randomness. This provides more predictable timing than full jitter.

### 3.6 Decorrelated Jitter (AWS Recommendation)

```
delay(attempt) = min(cap, random(base, previous_delay × 3))

where:
  base           = initial delay (1 second)
  cap            = maximum delay (14400 seconds)
  previous_delay = delay from the last attempt (starts at base)
```

Each delay is decorrelated from the exponential function and instead based on the previous delay. This produces the best spread across time and is recommended by AWS.

**Reference**: [AWS Architecture Blog — "Exponential Backoff and Jitter"](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/)

---

## 4. Timing Diagrams — 5-Attempt Policy

### 4.1 All Strategies Compared (base = 1s, cap = 60s)

```
Time (seconds) →  0    1    2    3    4    5   10   15   20   30   40   50   60
                  ├────┼────┼────┼────┼────┼────┼────┼────┼────┼────┼────┼────┤

Immediate:        ●●●●●
                  All 5 attempts in <1 second

Fixed (5s):       ●    ●    ●    ●    ●
                  @0   @5   @10  @15  @20

Linear (5s):      ●    ●         ●              ●                   ●
                  @0   @5        @15             @30                 @50

Exponential:      ● ●    ●         ●                        ●
                  @0 @1  @3        @7                        @15

Exp + Full Jitter:●  ●     ●           ●                 ●
                  @0 @0.7 @2.1       @5.3               @12.8
                  (randomized — each run different)

Decorrelated:     ●   ●       ●              ●                          ●
                  @0  @1.2    @4.8           @11.3                       @29.1
                  (randomized — each run different)
```

### 4.2 EventRelay's Actual Retry Schedule (15 Attempts)

```
Attempt  Base Delay   Jitter Range        Example Actual    Cumulative
───────  ──────────   ─────────────       ──────────────    ──────────
  1      immediate    —                   0s                0s
  2      1s           [0, 1.5s]           0.8s              0.8s
  3      2s           [0, 3s]             1.7s              2.5s
  4      4s           [0, 6s]             4.2s              6.7s
  5      8s           [0, 12s]            9.1s              15.8s
  6      30s          [0, 45s]            22.3s             38.1s
  7      60s          [0, 90s]            67.4s             ~1.8m
  8      5m           [0, 7.5m]           3.2m              ~5m
  9      15m          [0, 22.5m]          11.4m             ~16m
 10      30m          [0, 45m]            28.7m             ~45m
 11      1h           [0, 1.5h]           52m               ~1.6h
 12      2h           [0, 3h]             1.8h              ~3.4h
 13      4h           [0, 6h]             3.1h              ~6.5h
 14      4h           [0, 6h]             5.2h              ~11.7h
 15      4h           [0, 6h]             4.7h              ~16.4h
         ──────────                                         ──────────
         DEAD-LETTERED after attempt 15                     ~16-28h total
```

---

## 5. Java Implementations

### 5.1 Retry Strategy Interface

```java
public interface RetryStrategy {

    /**
     * Calculates the delay before the next retry attempt.
     *
     * @param attempt       Current attempt number (1-based; 1 = first retry)
     * @param previousDelay Previous delay in milliseconds (for decorrelated jitter)
     * @return Delay in milliseconds before next attempt
     */
    long calculateDelay(int attempt, long previousDelay);

    /**
     * Returns the maximum number of retry attempts.
     */
    int getMaxAttempts();

    /**
     * Returns the strategy name for logging and metrics.
     */
    String getName();
}
```

### 5.2 Immediate Retry

```java
public class ImmediateRetryStrategy implements RetryStrategy {

    private final int maxAttempts;

    public ImmediateRetryStrategy(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    @Override
    public long calculateDelay(int attempt, long previousDelay) {
        return 0;
    }

    @Override
    public int getMaxAttempts() { return maxAttempts; }

    @Override
    public String getName() { return "immediate"; }
}
```

### 5.3 Fixed Delay

```java
public class FixedDelayRetryStrategy implements RetryStrategy {

    private final long delayMs;
    private final int maxAttempts;

    public FixedDelayRetryStrategy(long delayMs, int maxAttempts) {
        this.delayMs = delayMs;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public long calculateDelay(int attempt, long previousDelay) {
        return delayMs;
    }

    @Override
    public int getMaxAttempts() { return maxAttempts; }

    @Override
    public String getName() { return "fixed"; }
}
```

### 5.4 Exponential Backoff (No Jitter)

```java
public class ExponentialBackoffStrategy implements RetryStrategy {

    private final long baseDelayMs;
    private final long maxDelayMs;
    private final int maxAttempts;

    public ExponentialBackoffStrategy(long baseDelayMs, long maxDelayMs, int maxAttempts) {
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public long calculateDelay(int attempt, long previousDelay) {
        // delay = min(base × 2^attempt, cap)
        long exponential = baseDelayMs * (1L << Math.min(attempt, 30));
        return Math.min(exponential, maxDelayMs);
    }

    @Override
    public int getMaxAttempts() { return maxAttempts; }

    @Override
    public String getName() { return "exponential"; }
}
```

### 5.5 Exponential Backoff with Full Jitter ⭐ (Default)

```java
public class FullJitterBackoffStrategy implements RetryStrategy {

    private final long baseDelayMs;
    private final long maxDelayMs;
    private final int maxAttempts;
    private final ThreadLocalRandom random = ThreadLocalRandom.current();

    public FullJitterBackoffStrategy(long baseDelayMs, long maxDelayMs, int maxAttempts) {
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public long calculateDelay(int attempt, long previousDelay) {
        // delay = random(0, min(base × 2^attempt, cap))
        long exponential = baseDelayMs * (1L << Math.min(attempt, 30));
        long cap = Math.min(exponential, maxDelayMs);
        return ThreadLocalRandom.current().nextLong(0, cap + 1);
    }

    @Override
    public int getMaxAttempts() { return maxAttempts; }

    @Override
    public String getName() { return "full_jitter"; }
}
```

### 5.6 Exponential Backoff with Equal Jitter

```java
public class EqualJitterBackoffStrategy implements RetryStrategy {

    private final long baseDelayMs;
    private final long maxDelayMs;
    private final int maxAttempts;

    public EqualJitterBackoffStrategy(long baseDelayMs, long maxDelayMs, int maxAttempts) {
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public long calculateDelay(int attempt, long previousDelay) {
        // temp = min(base × 2^attempt, cap)
        // delay = temp / 2 + random(0, temp / 2)
        long exponential = baseDelayMs * (1L << Math.min(attempt, 30));
        long temp = Math.min(exponential, maxDelayMs);
        long half = temp / 2;
        return half + ThreadLocalRandom.current().nextLong(0, half + 1);
    }

    @Override
    public int getMaxAttempts() { return maxAttempts; }

    @Override
    public String getName() { return "equal_jitter"; }
}
```

### 5.7 Decorrelated Jitter (AWS Recommendation)

```java
public class DecorrelatedJitterStrategy implements RetryStrategy {

    private final long baseDelayMs;
    private final long maxDelayMs;
    private final int maxAttempts;

    public DecorrelatedJitterStrategy(long baseDelayMs, long maxDelayMs, int maxAttempts) {
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public long calculateDelay(int attempt, long previousDelay) {
        // delay = min(cap, random(base, previous_delay × 3))
        long prevDelay = (attempt == 1) ? baseDelayMs : previousDelay;
        long upperBound = prevDelay * 3;
        long delay = ThreadLocalRandom.current().nextLong(baseDelayMs, Math.max(baseDelayMs + 1, upperBound));
        return Math.min(delay, maxDelayMs);
    }

    @Override
    public int getMaxAttempts() { return maxAttempts; }

    @Override
    public String getName() { return "decorrelated_jitter"; }
}
```

---

## 6. EventRelay's Production Configuration

### 6.1 Retry Strategy Configuration

```java
@Configuration
public class RetryConfig {

    @Bean
    public RetryStrategy webhookRetryStrategy(
            @Value("${eventrelay.retry.base-delay-ms:1000}") long baseDelayMs,
            @Value("${eventrelay.retry.max-delay-ms:14400000}") long maxDelayMs,
            @Value("${eventrelay.retry.max-attempts:15}") int maxAttempts,
            @Value("${eventrelay.retry.strategy:full_jitter}") String strategy) {

        return switch (strategy) {
            case "immediate" -> new ImmediateRetryStrategy(maxAttempts);
            case "fixed" -> new FixedDelayRetryStrategy(baseDelayMs, maxAttempts);
            case "exponential" -> new ExponentialBackoffStrategy(baseDelayMs, maxDelayMs, maxAttempts);
            case "full_jitter" -> new FullJitterBackoffStrategy(baseDelayMs, maxDelayMs, maxAttempts);
            case "equal_jitter" -> new EqualJitterBackoffStrategy(baseDelayMs, maxDelayMs, maxAttempts);
            case "decorrelated" -> new DecorrelatedJitterStrategy(baseDelayMs, maxDelayMs, maxAttempts);
            default -> throw new IllegalArgumentException("Unknown retry strategy: " + strategy);
        };
    }
}
```

### 6.2 Application Properties

```yaml
eventrelay:
  retry:
    strategy: full_jitter          # Strategy name
    base-delay-ms: 1000           # 1 second base delay
    max-delay-ms: 14400000        # 4 hour maximum delay
    max-attempts: 15              # Maximum retry attempts
    non-retryable-status-codes:   # Status codes that should not trigger retries
      - 400
      - 401
      - 403
      - 404
      - 405
      - 410
      - 413
      - 422
    respect-retry-after: true     # Honor Retry-After header from 429 responses
    retry-after-max-seconds: 3600 # Maximum Retry-After we'll honor (1 hour)
```

### 6.3 Retry Execution Engine

```java
@Service
@Slf4j
public class RetryEngine {

    private final RetryStrategy strategy;
    private final WebhookDeliveryService deliveryService;
    private final EventRepository eventRepository;
    private final DeadLetterService deadLetterService;
    private final MeterRegistry metrics;

    /**
     * Processes a delivery attempt and handles retry scheduling.
     */
    public DeliveryResult processWithRetry(DeliveryTask task) {
        int attempt = task.getAttemptNumber();

        // Check if max attempts exceeded
        if (attempt > strategy.getMaxAttempts()) {
            log.warn("Event {} exceeded max attempts ({}), dead-lettering",
                    task.getEventId(), strategy.getMaxAttempts());
            deadLetterService.moveToDeadLetter(task);
            metrics.counter("eventrelay.retry.dead_lettered").increment();
            return DeliveryResult.deadLettered();
        }

        // Attempt delivery
        DeliveryOutcome outcome = deliveryService.deliver(task);

        if (outcome.isSuccess()) {
            metrics.counter("eventrelay.delivery.success",
                    "attempt", String.valueOf(attempt)).increment();
            return DeliveryResult.success(outcome);
        }

        // Check if error is retryable
        if (!outcome.isRetryable()) {
            log.info("Event {} failed with non-retryable status {}, marking failed",
                    task.getEventId(), outcome.getStatusCode());
            eventRepository.markFailed(task.getEventId(), outcome.getStatusCode());
            metrics.counter("eventrelay.delivery.permanent_failure").increment();
            return DeliveryResult.permanentFailure(outcome);
        }

        // Calculate next retry delay
        long delayMs = calculateNextDelay(attempt, task.getPreviousDelayMs(), outcome);

        log.info("Event {} attempt {} failed (status={}), scheduling retry in {}ms",
                task.getEventId(), attempt, outcome.getStatusCode(), delayMs);

        // Schedule retry
        task.setAttemptNumber(attempt + 1);
        task.setPreviousDelayMs(delayMs);
        task.setNextAttemptAt(Instant.now().plusMillis(delayMs));

        metrics.counter("eventrelay.retry.scheduled",
                "attempt", String.valueOf(attempt)).increment();
        metrics.timer("eventrelay.retry.delay").record(Duration.ofMillis(delayMs));

        return DeliveryResult.retryScheduled(delayMs);
    }

    private long calculateNextDelay(int attempt, long previousDelay, DeliveryOutcome outcome) {
        // Honor Retry-After header for 429 responses
        if (outcome.getStatusCode() == 429 && outcome.getRetryAfterSeconds() > 0) {
            long retryAfterMs = outcome.getRetryAfterSeconds() * 1000L;
            long maxRetryAfterMs = 3600_000L; // 1 hour maximum
            return Math.min(retryAfterMs, maxRetryAfterMs);
        }

        return strategy.calculateDelay(attempt, previousDelay);
    }
}
```

---

## 7. Strategy Selection Guide

### 7.1 Decision Matrix

| Scenario | Recommended Strategy | Rationale |
|---|---|---|
| General webhook delivery | **Full Jitter** | Best balance of spread and recovery time |
| High-volume tenant (>1000 eps) | **Decorrelated Jitter** | Maximum spread for large fan-out |
| Internal service integration | **Equal Jitter** | Guaranteed minimum delay prevents oscillation |
| Testing/development | **Fixed Delay** | Predictable timing for test assertions |
| Health check probes | **Exponential** | Predictable, no randomness needed |
| Critical real-time events | **Equal Jitter** | Guaranteed minimum wait, faster early retries |

### 7.2 Strategy Comparison Under Load

Simulation: 1000 simultaneous failures retrying over 60 seconds

```
                    Retry Distribution (1000 clients, t=0 to t=60s)
                    ──────────────────────────────────────────────────
Fixed (5s):         ████████████████████                    @5s
                                        (all 1000 at t=5s — thundering herd!)

Exponential:        ██████████████████████████████          @1s, @2s, @4s, @8s, @16s, @32s
                    (correlated — all retry at same times)

Full Jitter:        ██ █ ███ ██ █ ██ █ █ ███ █ ██ █ ██ █ █ ██ ██ █ ██
                    (spread uniformly — no spikes)

Equal Jitter:       █ ███ ██ ████ ███ ████ ██ ███ ████ ███ ██
                    (spread, but more concentrated than full jitter)

Decorrelated:       █ █ ██ █ █ ██ █ █ ██ █ ██ █ █ █ ██ █ █ █ ██ █ █ █
                    (maximum spread — lowest peak load)
```

---

## 8. Special Retry Behaviors

### 8.1 Retry-After Header Handling

When a webhook endpoint returns `429 Too Many Requests` with a `Retry-After` header:

```java
public class RetryAfterParser {

    /**
     * Parses the Retry-After header value.
     * Supports both delay-seconds and HTTP-date formats.
     *
     * @return Retry delay in seconds, or -1 if header is absent/invalid
     */
    public long parseRetryAfter(HttpResponse response) {
        String retryAfter = response.getHeader("Retry-After");
        if (retryAfter == null || retryAfter.isBlank()) {
            return -1;
        }

        // Try parsing as seconds first
        try {
            long seconds = Long.parseLong(retryAfter.trim());
            return Math.max(0, seconds);
        } catch (NumberFormatException e) {
            // Try parsing as HTTP-date
            try {
                ZonedDateTime retryAt = ZonedDateTime.parse(retryAfter.trim(),
                        DateTimeFormatter.RFC_1123_DATE_TIME);
                long seconds = Duration.between(Instant.now(), retryAt.toInstant()).getSeconds();
                return Math.max(0, seconds);
            } catch (DateTimeParseException ex) {
                log.warn("Unparseable Retry-After header: {}", retryAfter);
                return -1;
            }
        }
    }
}
```

### 8.2 Circuit Breaker Integration

Retries are suppressed when the circuit breaker for an endpoint is OPEN:

```java
public DeliveryResult processWithRetry(DeliveryTask task) {
    // Check circuit breaker before attempting delivery
    CircuitBreakerState cbState = circuitBreakerRegistry
            .getState(task.getEndpointId());

    if (cbState == CircuitBreakerState.OPEN) {
        // Don't count as a retry attempt — just delay
        long remainingOpenTime = circuitBreakerRegistry
                .getRemainingOpenTimeMs(task.getEndpointId());

        log.info("Circuit breaker OPEN for endpoint {}, deferring retry by {}ms",
                task.getEndpointId(), remainingOpenTime);

        return DeliveryResult.deferred(remainingOpenTime);
    }

    // Proceed with normal retry logic...
    return attemptDelivery(task);
}
```

---

## 9. Monitoring and Alerting

### 9.1 Key Metrics

```java
@Component
public class RetryMetrics {

    private final MeterRegistry registry;

    public void recordRetryAttempt(String eventId, int attempt, String strategy,
                                    long delayMs, String outcome) {
        registry.counter("eventrelay.retry.attempts",
                "attempt_number", String.valueOf(attempt),
                "strategy", strategy,
                "outcome", outcome
        ).increment();

        registry.timer("eventrelay.retry.delay",
                "attempt_number", String.valueOf(attempt),
                "strategy", strategy
        ).record(Duration.ofMillis(delayMs));

        registry.summary("eventrelay.retry.attempt_distribution",
                "strategy", strategy
        ).record(attempt);
    }
}
```

### 9.2 Alerting Rules

| Alert | Condition | Severity |
|---|---|---|
| High retry rate | `rate(retry_attempts[5m]) > 0.3 × rate(delivery_attempts[5m])` | Warning |
| Excessive retries | Average attempts per event > 3.0 | Warning |
| Dead-letter spike | `rate(dead_lettered[5m]) > 10` | Critical |
| Retry delay anomaly | p99 delay > 2 × expected for attempt number | Warning |

---

## 10. Production Considerations

### 10.1 Retry Storm Mitigation

> [!CAUTION]
> A large number of events failing simultaneously (e.g., receiver outage) can create a **retry storm** when the receiver recovers. Mitigations:

1. **Full jitter** — spreads retries across the backoff window
2. **Circuit breaker** — stops retries when failure rate is high
3. **Rate limiting** — caps delivery rate per endpoint
4. **Gradual recovery** — half-open circuit breaker allows one probe request

### 10.2 Clock Skew Considerations

Retry delays are computed using `Instant.now()` on the worker. In a distributed fleet:
- Workers use NTP for time synchronization
- Maximum acceptable clock skew: 1 second
- Retry delays have enough jitter to absorb clock skew
- Dead-letter decisions use attempt count (not wall clock), immune to skew

### 10.3 Queue-Based vs Timer-Based Retries

EventRelay uses **SQS delay messages** for retry scheduling:

| Approach | Pros | Cons |
|---|---|---|
| SQS delay message | Durable, survives worker restart | Max 15-minute delay per message |
| SQS + DelayQueue combination | Supports longer delays | Slightly more complex |
| In-memory timer | Low latency, precise timing | Lost on worker restart |
| **EventRelay approach** | Hybrid: SQS for <15m, re-enqueue with delay for >15m | Best of both worlds |

```java
public void scheduleRetry(DeliveryTask task, long delayMs) {
    if (delayMs <= SQS_MAX_DELAY_MS) { // 900,000ms = 15 minutes
        // Use SQS message delay directly
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(retryQueueUrl)
                .messageBody(serialize(task))
                .delaySeconds((int) (delayMs / 1000))
                .build());
    } else {
        // Store in PostgreSQL with next_attempt_at timestamp
        // A scheduled poller will re-enqueue when the time comes
        retryRepository.scheduleRetry(
                task.getEventId(),
                task.getAttemptNumber(),
                Instant.now().plusMillis(delayMs),
                delayMs
        );
    }
}
```

---

## 11. Summary

| Aspect | EventRelay's Choice |
|---|---|
| **Default strategy** | Exponential backoff with full jitter |
| **Base delay** | 1 second |
| **Maximum delay** | 4 hours |
| **Maximum attempts** | 15 |
| **Total retry window** | ~24-28 hours |
| **Jitter type** | Full jitter (uniform random in [0, cap]) |
| **429 handling** | Honors `Retry-After` header (max 1 hour) |
| **Circuit breaker integration** | Defers retries when circuit is OPEN |
| **Retry scheduling** | SQS delay (<15m) + PostgreSQL poller (>15m) |
| **Configurable** | Per-tenant strategy override supported |
