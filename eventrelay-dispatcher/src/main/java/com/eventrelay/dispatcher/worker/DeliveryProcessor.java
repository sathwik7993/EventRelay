package com.eventrelay.dispatcher.worker;

import com.eventrelay.core.domain.Delivery;
import com.eventrelay.core.domain.DeliveryState;
import com.eventrelay.core.domain.Event;
import com.eventrelay.core.domain.Subscription;
import com.eventrelay.core.repository.DeliveryRepository;
import com.eventrelay.core.repository.EventRepository;
import com.eventrelay.core.repository.SubscriptionRepository;
import com.eventrelay.core.service.DeliveryResult;
import com.eventrelay.core.service.DeliveryService;
import com.eventrelay.core.service.TargetUrlValidator;
import com.eventrelay.dispatcher.tracing.DeliveryTracer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Processes one delivery message: loads current state, enforces the circuit
 * breaker, performs the signed HTTP attempt, and advances the delivery via
 * {@link DeliveryService}. Safe to invoke more than once for the same delivery
 * (at-least-once queue semantics) — a terminal delivery is skipped.
 */
@Service
public class DeliveryProcessor {

    private static final Logger log = LoggerFactory.getLogger(DeliveryProcessor.class);
    private static final String EMPTY_PAYLOAD = "{}";

    private final DeliveryRepository deliveries;
    private final SubscriptionRepository subscriptions;
    private final EventRepository events;
    private final HttpDeliveryClient httpClient;
    private final DeliveryService deliveryService;
    private final TargetUrlValidator targetUrlValidator;
    private final DeliveryTracer deliveryTracer;
    private final MeterRegistry metrics;

    public DeliveryProcessor(DeliveryRepository deliveries, SubscriptionRepository subscriptions,
                             EventRepository events, HttpDeliveryClient httpClient,
                             DeliveryService deliveryService, TargetUrlValidator targetUrlValidator,
                             DeliveryTracer deliveryTracer,
                             MeterRegistry metrics) {
        this.deliveries = deliveries;
        this.subscriptions = subscriptions;
        this.events = events;
        this.httpClient = httpClient;
        this.deliveryService = deliveryService;
        this.targetUrlValidator = targetUrlValidator;
        this.deliveryTracer = deliveryTracer;
        this.metrics = metrics;
    }

    public void process(String deliveryId) {
        Delivery delivery = deliveries.findById(UUID.fromString(deliveryId)).orElse(null);
        if (delivery == null) {
            log.debug("Delivery {} not found; discarding message", deliveryId);
            return;
        }
        if (delivery.getStatus() == DeliveryState.DELIVERED || delivery.getStatus() == DeliveryState.DEAD) {
            log.debug("Delivery {} already terminal ({}); skipping", deliveryId, delivery.getStatus());
            return;
        }

        Event event = events.findById(delivery.getEventId()).orElse(null);
        Subscription subscription = subscriptions.findById(delivery.getSubscriptionId()).orElse(null);

        if (subscription == null || subscription.getDeletedAt() != null) {
            recordDlq(deliveryService.recordFailure(delivery, subscription, true, null,
                    "Subscription no longer exists", payloadOf(event)));
            return;
        }
        if (event == null) {
            recordDlq(deliveryService.recordFailure(delivery, subscription, true, null,
                    "Source event no longer exists", EMPTY_PAYLOAD));
            return;
        }

        // Re-check the SSRF policy at delivery time: DNS can change between when a
        // subscription was created and when we actually send (DNS rebinding).
        if (!targetUrlValidator.isAllowed(delivery.getTargetUrl())) {
            metrics.counter("eventrelay.delivery.blocked").increment();
            recordDlq(deliveryService.recordFailure(delivery, subscription, true, null,
                    "Target URL blocked by SSRF policy", event.getPayload()));
            return;
        }

        // Circuit breaker: don't hammer an endpoint that's failing consistently.
        if (deliveryService.isCircuitOpen(subscription)) {
            deliveryService.recordSkipped(delivery, subscription);
            metrics.counter("eventrelay.delivery.skipped").increment();
            log.debug("Circuit open for subscription {}; skipping delivery {}",
                    subscription.getId(), delivery.getId());
            return;
        }

        int attemptNumber = delivery.getAttemptCount() + 1;

        // Resume the trace started at ingest so this delivery is a child span.
        Span span = deliveryTracer.startSpan(event, delivery, attemptNumber);
        try (Tracer.SpanInScope scope = deliveryTracer.withSpan(span)) {
            DeliveryOutcome outcome = httpClient.deliver(delivery, subscription, event, attemptNumber);
            span.tag("delivery.success", Boolean.toString(outcome.success()));
            if (outcome.httpStatus() != null) {
                span.tag("http.status_code", Integer.toString(outcome.httpStatus()));
            }

            if (outcome.success()) {
                deliveryService.recordSuccess(delivery, subscription, outcome.httpStatus());
            } else {
                span.error(new DeliveryFailedException(outcome.error()));
                DeliveryResult result = deliveryService.recordFailure(delivery, subscription,
                        outcome.permanent(), outcome.httpStatus(), outcome.error(), event.getPayload());
                recordDlq(result);
            }
        } finally {
            span.end();
        }
    }

    /** Marker used to annotate a failed delivery span; never thrown to callers. */
    private static final class DeliveryFailedException extends RuntimeException {
        DeliveryFailedException(String message) {
            super(message, null, false, false);
        }
    }

    private void recordDlq(DeliveryResult result) {
        if (result == DeliveryResult.DEAD_LETTERED) {
            metrics.counter("eventrelay.deliveries.dead_lettered").increment();
        } else {
            metrics.counter("eventrelay.delivery.retries").increment();
        }
    }

    private String payloadOf(Event event) {
        return event != null ? event.getPayload() : EMPTY_PAYLOAD;
    }
}
