# Backoff Algorithms

## Overview

When a webhook delivery fails with a retryable error, EventRelay schedules the next attempt after a calculated delay. The backoff algorithm determines this delay, balancing two competing goals: **fast recovery** (retry soon to minimize latency) and **server protection** (don't hammer a struggling endpoint). EventRelay uses exponential backoff with configurable jitter to spread retry load and prevent thundering herds.

> [!IMPORTANT]
> Jitter is **critical** in production. Without jitter, when a downstream service recovers from an outage, all backed-off retries fire simultaneously at the exact same intervals, potentially causing the service to fail again. This is the "thundering herd" problem.

---

## Base Formula

The core exponential backoff formula:

```
delay = min(base_delay × multiplier^(attempt - 1), max_delay)
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `baseDelay` | 1 second | Initial delay after first failure |
| `multiplier` | 2.0 | Exponential growth factor |
| `maxDelay` | 3600 seconds (1 hour) | Maximum delay cap |
| `maxAttempts` | 5 | Total delivery attempts |

### Default Retry Schedule (No Jitter)

| Attempt | Formula | Raw Delay | Capped Delay | Cumulative Wait |
|---------|---------|-----------|-------------|-----------------|
| 1 | Initial delivery | — | — | 0s |
| 2 | 1 × 2^0 | 1s | 1s | 1s |
| 3 | 1 × 2^1 | 2s | 2s | 3s |
| 4 | 1 × 2^2 | 4s | 4s | 7s |
| 5 | 1 × 2^3 | 8s | 8s | 15s |

### Production Retry Schedule (Longer Intervals)

For production use, we recommend a more aggressive schedule with longer intervals:

| Attempt | Formula | Raw Delay | Capped Delay | Cumulative Wait |
|---------|---------|-----------|-------------|-----------------|
| 1 | Initial delivery | — | — | 0s |
| 2 | 5 × 3^0 | 5s | 5s | 5s |
| 3 | 5 × 3^1 | 15s | 15s | 20s |
| 4 | 5 × 3^2 | 45s | 45s | 1m 5s |
| 5 | 5 × 3^3 | 135s | 135s | 3m 20s |
| 6 | 5 × 3^4 | 405s | 405s | 10m 5s |
| 7 | 5 × 3^5 | 1215s | 1215s | 30m 20s |
| 8 | 5 × 3^6 | 3645s | 3600s | 1h 30m |

---

## Jitter Strategies

Jitter adds randomness to the delay to prevent synchronized retries. EventRelay supports three jitter strategies:

### Comparison Table

| Strategy | Formula | Min Delay | Max Delay | Best For |
|----------|---------|-----------|-----------|----------|
| **No Jitter** | `base × mult^n` | Deterministic | Deterministic | Testing only |
| **Full Jitter** | `random(0, base × mult^n)` | 0 | `base × mult^n` | Most workloads |
| **Equal Jitter** | `base × mult^n / 2 + random(0, base × mult^n / 2)` | `base × mult^n / 2` | `base × mult^n` | When minimum delay needed |
| **Decorrelated Jitter** | `random(base, prev_delay × 3)` | `base` | `prev × 3` | High contention |

### Visual Comparison (Attempt 4, base=5s, mult=3)

```
Attempt 4: base delay = 5 × 3^3 = 135s

No Jitter:        |────────────────────────────────────────── 135s ──|
                  ████████████████████████████████████████████████████

Full Jitter:      |── 0s ──────────────── up to 135s ──────────────|
                  ░░░░░░░░████████████████░░░░░░░░░░░░░░░░░░░░░░░░░
                  (uniform random between 0 and 135s)

Equal Jitter:     |── 67.5s ────────────── up to 135s ──────────────|
                  ████████████████████████░░░░░████████░░░░░░░░░░░░░
                  (67.5s base + random 0-67.5s)

Decorrelated:     |── 5s ────────────── up to prev×3 ──────────────|
                  ░░░████████████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░
                  (random between base and prev_delay × 3)
```

---

## Java Implementation

```java
package com.eventrelay.dispatch.backoff;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Calculates retry backoff delays with configurable jitter strategies.
 *
 * <p>References:
 * <ul>
 *   <li><a href="https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/">
 *       AWS: Exponential Backoff and Jitter</a></li>
 *   <li><a href="https://stripe.com/docs/webhooks/best-practices">
 *       Stripe: Webhook Best Practices</a></li>
 * </ul>
 */
@Component
public class BackoffCalculator {

    private final BackoffConfig config;

    public BackoffCalculator(BackoffConfig config) {
        this.config = config;
    }

    /**
     * Calculates the delay before the next retry attempt.
     *
     * @param attemptNumber Current attempt number (1-based, so first retry is attempt 2)
     * @return Duration to wait before the next retry
     */
    public Duration calculateDelay(int attemptNumber) {
        return calculateDelay(attemptNumber, Duration.ZERO);
    }

    /**
     * Calculates the delay with an optional override (e.g., Retry-After header).
     * The override takes precedence if it exceeds the calculated delay.
     */
    public Duration calculateDelay(int attemptNumber, Duration overrideDelay) {
        if (overrideDelay != null && !overrideDelay.isZero()) {
            // Cap the override at maxDelay
            long overrideMs = Math.min(
                    overrideDelay.toMillis(),
                    config.getMaxDelayMs()
            );
            return Duration.ofMillis(overrideMs);
        }

        long rawDelayMs = calculateRawDelay(attemptNumber);
        long jitteredDelayMs = applyJitter(rawDelayMs, attemptNumber);

        // Enforce bounds
        long finalDelayMs = Math.max(
                config.getMinDelayMs(),
                Math.min(jitteredDelayMs, config.getMaxDelayMs())
        );

        return Duration.ofMillis(finalDelayMs);
    }

    /**
     * Calculates the raw exponential delay without jitter.
     *
     * <pre>delay = baseDelay × multiplier^(attempt - 1)</pre>
     */
    private long calculateRawDelay(int attemptNumber) {
        // attempt 1 = initial delivery (no delay)
        // attempt 2 = first retry: baseDelay × multiplier^0 = baseDelay
        int retryNumber = Math.max(0, attemptNumber - 1);

        double rawDelay = config.getBaseDelayMs()
                * Math.pow(config.getMultiplier(), retryNumber);

        // Prevent overflow
        if (rawDelay > config.getMaxDelayMs() || Double.isInfinite(rawDelay)) {
            return config.getMaxDelayMs();
        }

        return (long) rawDelay;
    }

    /**
     * Applies the configured jitter strategy to the raw delay.
     */
    private long applyJitter(long rawDelayMs, int attemptNumber) {
        return switch (config.getJitterStrategy()) {
            case NONE -> rawDelayMs;
            case FULL -> fullJitter(rawDelayMs);
            case EQUAL -> equalJitter(rawDelayMs);
            case DECORRELATED -> decorrelatedJitter(rawDelayMs, attemptNumber);
        };
    }

    /**
     * Full Jitter: delay = random(0, rawDelay)
     *
     * <p>Provides maximum spread. On average, delays are half the raw value.
     * Best general-purpose strategy per AWS recommendation.
     */
    private long fullJitter(long rawDelayMs) {
        if (rawDelayMs <= 0) return 0;
        return ThreadLocalRandom.current().nextLong(0, rawDelayMs + 1);
    }

    /**
     * Equal Jitter: delay = rawDelay/2 + random(0, rawDelay/2)
     *
     * <p>Guarantees a minimum delay of rawDelay/2 while still adding
     * randomness. Good when you need a guaranteed minimum wait.
     */
    private long equalJitter(long rawDelayMs) {
        long half = rawDelayMs / 2;
        return half + ThreadLocalRandom.current().nextLong(0, half + 1);
    }

    /**
     * Decorrelated Jitter: delay = random(baseDelay, previousDelay × 3)
     *
     * <p>Each retry's delay is based on the previous delay rather than
     * the attempt number. Produces more varied timing patterns, reducing
     * correlation between retries from different callers.
     */
    private long decorrelatedJitter(long rawDelayMs, int attemptNumber) {
        long baseMs = config.getBaseDelayMs();
        // Simulate previous delay
        long previousDelay = attemptNumber <= 1
                ? baseMs
                : calculateRawDelay(attemptNumber - 1);

        long upperBound = Math.min(previousDelay * 3, config.getMaxDelayMs());
        if (upperBound <= baseMs) return baseMs;

        return ThreadLocalRandom.current().nextLong(baseMs, upperBound + 1);
    }

    /**
     * Returns the complete retry schedule for a given number of attempts.
     * Useful for debugging and displaying to users.
     */
    public RetrySchedule getSchedule(int maxAttempts) {
        Duration[] delays = new Duration[maxAttempts];
        Duration cumulative = Duration.ZERO;

        delays[0] = Duration.ZERO; // First attempt has no delay

        for (int i = 1; i < maxAttempts; i++) {
            long rawDelay = calculateRawDelay(i);
            // Show raw delay for schedule display (no jitter in preview)
            Duration delay = Duration.ofMillis(
                    Math.min(rawDelay, config.getMaxDelayMs()));
            delays[i] = delay;
            cumulative = cumulative.plus(delay);
        }

        return new RetrySchedule(delays, cumulative, config);
    }
}
```

---

## Backoff Configuration

```java
package com.eventrelay.dispatch.backoff;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "eventrelay.backoff")
public class BackoffConfig {

    /** Base delay in milliseconds */
    private long baseDelayMs = 5_000;  // 5 seconds

    /** Exponential multiplier */
    private double multiplier = 3.0;

    /** Maximum delay in milliseconds */
    private long maxDelayMs = 3_600_000;  // 1 hour

    /** Minimum delay in milliseconds (floor) */
    private long minDelayMs = 1_000;  // 1 second

    /** Jitter strategy */
    private JitterStrategy jitterStrategy = JitterStrategy.FULL;

    // Getters and setters
    public long getBaseDelayMs() { return baseDelayMs; }
    public void setBaseDelayMs(long baseDelayMs) { this.baseDelayMs = baseDelayMs; }
    public double getMultiplier() { return multiplier; }
    public void setMultiplier(double multiplier) { this.multiplier = multiplier; }
    public long getMaxDelayMs() { return maxDelayMs; }
    public void setMaxDelayMs(long maxDelayMs) { this.maxDelayMs = maxDelayMs; }
    public long getMinDelayMs() { return minDelayMs; }
    public void setMinDelayMs(long minDelayMs) { this.minDelayMs = minDelayMs; }
    public JitterStrategy getJitterStrategy() { return jitterStrategy; }
    public void setJitterStrategy(JitterStrategy jitterStrategy) { this.jitterStrategy = jitterStrategy; }
}
```

```java
package com.eventrelay.dispatch.backoff;

public enum JitterStrategy {
    /** No jitter — deterministic delays. For testing only. */
    NONE,

    /** Full jitter — random(0, delay). Best general-purpose option. */
    FULL,

    /** Equal jitter — delay/2 + random(0, delay/2). Guaranteed minimum. */
    EQUAL,

    /** Decorrelated jitter — random(base, prev_delay × 3). Varied patterns. */
    DECORRELATED
}
```

### YAML Configuration

```yaml
eventrelay:
  backoff:
    base-delay-ms: 5000
    multiplier: 3.0
    max-delay-ms: 3600000
    min-delay-ms: 1000
    jitter-strategy: FULL
```

---

## Retry Schedule Model

```java
package com.eventrelay.dispatch.backoff;

import java.time.Duration;

/**
 * Represents a complete retry schedule — useful for API responses
 * and debugging.
 */
public record RetrySchedule(
    Duration[] delays,
    Duration totalDuration,
    BackoffConfig config
) {
    /**
     * Returns a human-readable representation of the schedule.
     */
    public String toDisplayString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Retry Schedule (").append(config.getJitterStrategy())
          .append(" jitter)\n");
        sb.append("─".repeat(50)).append("\n");

        Duration cumulative = Duration.ZERO;
        for (int i = 0; i < delays.length; i++) {
            cumulative = cumulative.plus(delays[i]);
            sb.append(String.format("  Attempt %d: delay=%s, cumulative=%s\n",
                    i + 1, formatDuration(delays[i]), formatDuration(cumulative)));
        }

        sb.append("─".repeat(50)).append("\n");
        sb.append(String.format("  Total max wait: %s\n",
                formatDuration(totalDuration)));
        return sb.toString();
    }

    private static String formatDuration(Duration d) {
        if (d.isZero()) return "0s";
        long hours = d.toHours();
        long minutes = d.toMinutesPart();
        long seconds = d.toSecondsPart();
        if (hours > 0) return String.format("%dh %dm %ds", hours, minutes, seconds);
        if (minutes > 0) return String.format("%dm %ds", minutes, seconds);
        return String.format("%ds", seconds);
    }
}
```

**Example schedule output:**

```
Retry Schedule (FULL jitter)
──────────────────────────────────────────────────
  Attempt 1: delay=0s, cumulative=0s
  Attempt 2: delay=5s, cumulative=5s
  Attempt 3: delay=15s, cumulative=20s
  Attempt 4: delay=45s, cumulative=1m 5s
  Attempt 5: delay=2m 15s, cumulative=3m 20s
  Attempt 6: delay=6m 45s, cumulative=10m 5s
  Attempt 7: delay=20m 15s, cumulative=30m 20s
  Attempt 8: delay=1h 0m 0s, cumulative=1h 30m 20s
──────────────────────────────────────────────────
  Total max wait: 1h 30m 20s
```

---

## Timing Examples with Jitter

For attempt 5 (base=5s, multiplier=3.0):

**Raw delay**: `5 × 3^4 = 405s = 6m 45s`

| Jitter Strategy | Min Delay | Max Delay | Example Actual | Spread |
|----------------|-----------|-----------|----------------|--------|
| **None** | 405s | 405s | 405s | 0s |
| **Full** | 0s | 405s | 187s | 405s |
| **Equal** | 202s | 405s | 312s | 203s |
| **Decorrelated** | 5s | 405s | 248s | 400s |

### Simulation: 10 Concurrent Retries (Attempt 5)

```
Timeline (seconds) →
0          100         200         300         400
|-----------|-----------|-----------|-----------|

No Jitter:
ALL ████████████████████████████████████████████████| 405s

Full Jitter:
#1  ██████████| 47s
#2  ████████████████| 89s
#3  ████████████████████| 123s
#4  ██████████████████████████| 167s
#5  ████████████████████████████| 182s
#6  ██████████████████████████████████| 221s
#7  ████████████████████████████████████████| 267s
#8  ████████████████████████████████████████████| 298s
#9  ██████████████████████████████████████████████| 332s
#10 ████████████████████████████████████████████████| 389s
```

> [!TIP]
> **Full jitter** is the recommended default. AWS research shows it produces the best results for most workloads — it minimizes total wait time and spreads load most effectively. See the [AWS Architecture Blog](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/) for analysis.

---

## Unit Tests

```java
package com.eventrelay.dispatch.backoff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffCalculatorTest {

    private BackoffCalculator calculator(JitterStrategy strategy) {
        BackoffConfig config = new BackoffConfig();
        config.setBaseDelayMs(5_000);
        config.setMultiplier(3.0);
        config.setMaxDelayMs(3_600_000);
        config.setMinDelayMs(1_000);
        config.setJitterStrategy(strategy);
        return new BackoffCalculator(config);
    }

    @ParameterizedTest
    @CsvSource({
        "1, 5000",     // 5 × 3^0 = 5s
        "2, 15000",    // 5 × 3^1 = 15s
        "3, 45000",    // 5 × 3^2 = 45s
        "4, 135000",   // 5 × 3^3 = 135s = 2m15s
        "5, 405000",   // 5 × 3^4 = 405s = 6m45s
    })
    void noJitter_shouldReturnExactExponentialDelay(int attempt, long expectedMs) {
        BackoffCalculator calc = calculator(JitterStrategy.NONE);
        Duration delay = calc.calculateDelay(attempt);
        assertThat(delay.toMillis()).isEqualTo(expectedMs);
    }

    @Test
    void noJitter_shouldCapAtMaxDelay() {
        BackoffCalculator calc = calculator(JitterStrategy.NONE);
        Duration delay = calc.calculateDelay(20); // Way beyond max
        assertThat(delay.toMillis()).isEqualTo(3_600_000);
    }

    @Test
    void fullJitter_shouldBeBetweenZeroAndRawDelay() {
        BackoffCalculator calc = calculator(JitterStrategy.FULL);
        long rawDelay = 45_000; // attempt 3

        for (int i = 0; i < 100; i++) {
            Duration delay = calc.calculateDelay(3);
            assertThat(delay.toMillis())
                    .isGreaterThanOrEqualTo(1_000) // minDelay
                    .isLessThanOrEqualTo(rawDelay);
        }
    }

    @Test
    void equalJitter_shouldBeBetweenHalfAndRawDelay() {
        BackoffCalculator calc = calculator(JitterStrategy.EQUAL);
        long rawDelay = 45_000; // attempt 3
        long halfDelay = rawDelay / 2;

        for (int i = 0; i < 100; i++) {
            Duration delay = calc.calculateDelay(3);
            assertThat(delay.toMillis())
                    .isGreaterThanOrEqualTo(halfDelay)
                    .isLessThanOrEqualTo(rawDelay);
        }
    }

    @Test
    void overrideDelay_shouldTakePrecedence() {
        BackoffCalculator calc = calculator(JitterStrategy.FULL);
        Duration override = Duration.ofSeconds(60);
        Duration delay = calc.calculateDelay(3, override);
        assertThat(delay).isEqualTo(override);
    }

    @Test
    void overrideDelay_shouldBeCappedAtMaxDelay() {
        BackoffCalculator calc = calculator(JitterStrategy.FULL);
        Duration override = Duration.ofHours(10); // Way over max
        Duration delay = calc.calculateDelay(3, override);
        assertThat(delay.toMillis()).isEqualTo(3_600_000);
    }

    @Test
    void schedule_shouldShowAllAttempts() {
        BackoffCalculator calc = calculator(JitterStrategy.NONE);
        RetrySchedule schedule = calc.getSchedule(5);
        assertThat(schedule.delays()).hasSize(5);
        assertThat(schedule.delays()[0]).isEqualTo(Duration.ZERO);
    }
}
```

---

## Production Considerations

1. **Thread-Safe Randomness**: Uses `ThreadLocalRandom` instead of `Random` to avoid contention between worker threads. `ThreadLocalRandom` has zero lock contention and is the fastest source of randomness in concurrent Java.

2. **Overflow Protection**: `Math.pow()` can return `Infinity` for large exponents. The calculator detects overflow and falls back to `maxDelay`.

3. **Schedule API**: The `/api/v1/retry-schedule` endpoint lets tenants preview their retry schedule before configuring it. This reduces support tickets from tenants wondering "when will my webhook retry?"

4. **Monitoring Actual Delays**: The `retry.delay.actual` histogram metric records the actual delay between attempts (including jitter), enabling analysis of real retry patterns.

5. **SQS Delay Queue Limits**: AWS SQS supports a maximum message delay of 15 minutes (900 seconds). For longer delays, EventRelay uses a scheduled task that polls the database for `RETRYING` deliveries whose `next_attempt_at` has passed.

---

## Related Documents

- [Retry Policies](./Retry_Policies.md) — Retry decision logic
- [Timeout Handling](./Timeout_Handling.md) — Timeout configuration
- [Delivery States](./Delivery_States.md) — State machine during retries
- [Dispatcher](./Dispatcher.md) — How retries re-enter the dispatch loop
