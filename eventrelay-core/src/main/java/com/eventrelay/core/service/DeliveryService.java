package com.eventrelay.core.service;

import com.eventrelay.common.retry.BackoffCalculator;
import com.eventrelay.core.domain.DeadLetterEvent;
import com.eventrelay.core.domain.Delivery;
import com.eventrelay.core.domain.DeliveryAttempt;
import com.eventrelay.core.domain.DeliveryState;
import com.eventrelay.core.domain.DeliveryStatus;
import com.eventrelay.core.domain.Event;
import com.eventrelay.core.domain.Subscription;
import com.eventrelay.core.repository.DeadLetterEventRepository;
import com.eventrelay.core.repository.DeliveryAttemptRepository;
import com.eventrelay.core.repository.DeliveryRepository;
import com.eventrelay.core.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the delivery state machine and a per-subscription circuit breaker.
 *
 * <p>The circuit breaker is DB-backed (via {@code subscriptions.failure_count} /
 * {@code last_failure_at}) rather than in-memory, so it is shared correctly
 * across all dispatcher instances. After {@code circuit.threshold} consecutive
 * failures the circuit is open: deliveries to that subscription are skipped
 * (without consuming retry attempts) until the cooldown elapses, protecting a
 * struggling endpoint from being hammered.
 */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private final DeliveryRepository deliveries;
    private final DeadLetterEventRepository deadLetters;
    private final SubscriptionRepository subscriptions;
    private final DeliveryAttemptRepository attempts;
    private final int maxAttempts;
    private final int circuitThreshold;
    private final Duration circuitCooldown;

    public DeliveryService(DeliveryRepository deliveries, DeadLetterEventRepository deadLetters,
                           SubscriptionRepository subscriptions, DeliveryAttemptRepository attempts,
                           @Value("${eventrelay.delivery.max-attempts:8}") int maxAttempts,
                           @Value("${eventrelay.circuit.failure-threshold:5}") int circuitThreshold,
                           @Value("${eventrelay.circuit.cooldown-seconds:30}") int cooldownSeconds) {
        this.deliveries = deliveries;
        this.deadLetters = deadLetters;
        this.subscriptions = subscriptions;
        this.attempts = attempts;
        this.maxAttempts = maxAttempts;
        this.circuitThreshold = circuitThreshold;
        this.circuitCooldown = Duration.ofSeconds(cooldownSeconds);
    }

    /** Fans an event out into one delivery per matching subscription. */
    @Transactional
    public List<Delivery> createDeliveries(Event event, List<Subscription> subs) {
        List<Delivery> created = new ArrayList<>(subs.size());
        for (Subscription sub : subs) {
            created.add(deliveries.save(new Delivery(
                    event.getId(), sub.getId(), event.getTenantId(),
                    event.getEventType(), sub.getTargetUrl())));
        }
        return created;
    }

    /** True if the subscription's circuit is open (too many recent failures). */
    public boolean isCircuitOpen(Subscription subscription) {
        if (subscription.getFailureCount() < circuitThreshold) {
            return false;
        }
        OffsetDateTime lastFailure = subscription.getLastFailureAt();
        return lastFailure != null && OffsetDateTime.now().isBefore(lastFailure.plus(circuitCooldown));
    }

    /** A 2xx was received: close the circuit and mark the delivery delivered. */
    @Transactional
    public void recordSuccess(Delivery delivery, Subscription subscription, int httpStatus) {
        delivery.incrementAttempts();
        delivery.setLastHttpStatus(httpStatus);
        delivery.setLastError(null);
        delivery.setStatus(DeliveryState.DELIVERED);
        deliveries.save(delivery);

        if (subscription.getFailureCount() != 0) {
            subscription.setFailureCount(0);
            subscription.setLastFailureAt(null);
            subscriptions.save(subscription);
        }
    }

    /**
     * The attempt failed. Records the failure against the subscription (feeding
     * the circuit breaker), then either reschedules with backoff or dead-letters.
     */
    @Transactional
    public DeliveryResult recordFailure(Delivery delivery, Subscription subscription,
                                        boolean permanent, Integer httpStatus,
                                        String error, String payloadForDlq) {
        delivery.incrementAttempts();
        delivery.setLastHttpStatus(httpStatus);
        delivery.setLastError(error);

        if (subscription != null) {
            subscription.setFailureCount(subscription.getFailureCount() + 1);
            subscription.setLastFailureAt(OffsetDateTime.now());
            subscriptions.save(subscription);
        }

        boolean exhausted = delivery.getAttemptCount() >= maxAttempts;
        if (permanent || exhausted) {
            deadLetter(delivery, permanent, httpStatus, error, payloadForDlq);
            return DeliveryResult.DEAD_LETTERED;
        }

        Duration backoff = BackoffCalculator.backoffFor(delivery.getAttemptCount() + 1);
        delivery.setNextAttemptAt(OffsetDateTime.now().plus(backoff));
        delivery.setStatus(DeliveryState.RETRYING);
        deliveries.save(delivery);
        return DeliveryResult.RETRY_SCHEDULED;
    }

    /**
     * The circuit is open: skip this attempt without consuming a retry, record a
     * SKIPPED audit entry, and reschedule for when the cooldown ends.
     */
    @Transactional
    public void recordSkipped(Delivery delivery, Subscription subscription) {
        DeliveryAttempt skipped = new DeliveryAttempt(delivery.getEventId(), subscription.getId(),
                delivery.getTenantId(), delivery.getAttemptCount() + 1, DeliveryStatus.SKIPPED,
                delivery.getTargetUrl());
        skipped.setErrorMessage("Circuit open for subscription");
        skipped.setDurationMs(0);
        attempts.save(skipped);

        OffsetDateTime resumeAt = subscription.getLastFailureAt() != null
                ? subscription.getLastFailureAt().plus(circuitCooldown)
                : OffsetDateTime.now().plus(circuitCooldown);
        delivery.setNextAttemptAt(resumeAt);
        delivery.setStatus(DeliveryState.RETRYING);
        deliveries.save(delivery);
    }

    private void deadLetter(Delivery delivery, boolean permanent, Integer httpStatus,
                            String error, String payloadForDlq) {
        delivery.setStatus(DeliveryState.DEAD);
        deliveries.save(delivery);

        String reason = permanent
                ? "Permanent failure: " + error
                : "Retries exhausted after " + delivery.getAttemptCount() + " attempts: " + error;
        deadLetters.save(new DeadLetterEvent(
                delivery.getEventId(), delivery.getSubscriptionId(), delivery.getTenantId(),
                delivery.getEventType(), payloadForDlq, reason, httpStatus,
                delivery.getAttemptCount()));
        log.info("Delivery {} dead-lettered: {}", delivery.getId(), reason);
    }

    /** Reclaims deliveries stuck in QUEUED past their lease back to RETRYING. */
    @Transactional
    public int reclaimStale(Duration leaseDuration, int limit) {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(leaseDuration);
        List<Delivery> stale = deliveries.claimStaleQueued(cutoff, limit);
        for (Delivery d : stale) {
            d.setStatus(DeliveryState.RETRYING);
            d.setNextAttemptAt(OffsetDateTime.now());
            deliveries.save(d);
        }
        if (!stale.isEmpty()) {
            log.warn("Reclaimed {} stale in-flight deliveries", stale.size());
        }
        return stale.size();
    }

    /**
     * Re-queues a dead-lettered delivery: creates a fresh PENDING delivery for
     * the same event/subscription and marks the DLQ entry replayed.
     */
    @Transactional
    public Delivery replay(DeadLetterEvent dlq, Subscription subscription, String by) {
        Delivery fresh = new Delivery(dlq.getEventId(), dlq.getSubscriptionId(),
                dlq.getTenantId(), dlq.getEventType(), subscription.getTargetUrl());
        deliveries.save(fresh);
        dlq.markReplayed(by);
        deadLetters.save(dlq);
        return fresh;
    }
}
