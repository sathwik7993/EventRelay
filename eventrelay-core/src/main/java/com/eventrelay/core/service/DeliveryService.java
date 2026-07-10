package com.eventrelay.core.service;

import com.eventrelay.common.retry.BackoffCalculator;
import com.eventrelay.core.domain.DeadLetterEvent;
import com.eventrelay.core.domain.Delivery;
import com.eventrelay.core.domain.DeliveryState;
import com.eventrelay.core.domain.Event;
import com.eventrelay.core.domain.Subscription;
import com.eventrelay.core.repository.DeadLetterEventRepository;
import com.eventrelay.core.repository.DeliveryRepository;
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
 * Owns the delivery state machine: creating deliveries, and transitioning them
 * on each attempt's outcome (success, transient failure → backoff, permanent
 * failure / exhaustion → dead-letter).
 */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private final DeliveryRepository deliveries;
    private final DeadLetterEventRepository deadLetters;
    private final int maxAttempts;

    public DeliveryService(DeliveryRepository deliveries, DeadLetterEventRepository deadLetters,
                           @Value("${eventrelay.delivery.max-attempts:8}") int maxAttempts) {
        this.deliveries = deliveries;
        this.deadLetters = deadLetters;
        this.maxAttempts = maxAttempts;
    }

    /** Fans an event out into one delivery per matching subscription. */
    @Transactional
    public List<Delivery> createDeliveries(Event event, List<Subscription> subscriptions) {
        List<Delivery> created = new ArrayList<>(subscriptions.size());
        for (Subscription sub : subscriptions) {
            created.add(deliveries.save(new Delivery(
                    event.getId(), sub.getId(), event.getTenantId(),
                    event.getEventType(), sub.getTargetUrl())));
        }
        return created;
    }

    /** A 2xx was received. */
    @Transactional
    public void recordSuccess(Delivery delivery, int httpStatus) {
        delivery.incrementAttempts();
        delivery.setLastHttpStatus(httpStatus);
        delivery.setLastError(null);
        delivery.setStatus(DeliveryState.DELIVERED);
        deliveries.save(delivery);
    }

    /**
     * The attempt failed. Transient failures are rescheduled with backoff until
     * {@code maxAttempts} is reached; permanent failures (or exhaustion) are
     * dead-lettered using {@code payloadForDlq} as the self-contained snapshot.
     */
    @Transactional
    public void recordFailure(Delivery delivery, boolean permanent, Integer httpStatus,
                              String error, String payloadForDlq) {
        delivery.incrementAttempts();
        delivery.setLastHttpStatus(httpStatus);
        delivery.setLastError(error);

        boolean exhausted = delivery.getAttemptCount() >= maxAttempts;
        if (permanent || exhausted) {
            deadLetter(delivery, permanent, httpStatus, error, payloadForDlq);
            return;
        }

        Duration backoff = BackoffCalculator.backoffFor(delivery.getAttemptCount() + 1);
        delivery.setNextAttemptAt(OffsetDateTime.now().plus(backoff));
        delivery.setStatus(DeliveryState.RETRYING);
        deliveries.save(delivery);
        log.debug("Delivery {} scheduled for retry in {}s (attempt {})",
                delivery.getId(), backoff.toSeconds(), delivery.getAttemptCount() + 1);
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
