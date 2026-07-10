package com.eventrelay.common.retry;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with full ±jitter, per {@code FR-5} in the plan.
 *
 * <p>Attempt {@code n} (1-based) waits for a predefined interval; a random
 * jitter of ±20% is applied to spread retries and avoid a thundering herd when
 * many deliveries fail against the same endpoint and recover together.
 */
public final class BackoffCalculator {

    /** Delay before each attempt, in seconds. Index 0 = first retry. */
    private static final long[] INTERVALS_SECONDS = {1, 5, 30, 300, 1800, 3600, 14400};

    private static final double JITTER_FRACTION = 0.20;

    private BackoffCalculator() {
    }

    /**
     * Delay to wait before delivery attempt number {@code attemptNumber}
     * (1-based). The first attempt has no delay.
     */
    public static Duration backoffFor(int attemptNumber) {
        if (attemptNumber <= 1) {
            return Duration.ZERO;
        }
        int index = Math.min(attemptNumber - 2, INTERVALS_SECONDS.length - 1);
        long base = INTERVALS_SECONDS[index];
        double jitter = 1.0 + ThreadLocalRandom.current().nextDouble(-JITTER_FRACTION, JITTER_FRACTION);
        long seconds = Math.max(0, Math.round(base * jitter));
        return Duration.ofSeconds(seconds);
    }
}
