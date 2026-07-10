package com.eventrelay.dispatcher.worker;

import com.eventrelay.core.domain.Delivery;
import com.eventrelay.core.domain.DeliveryState;
import com.eventrelay.core.domain.Event;
import com.eventrelay.core.domain.Subscription;
import com.eventrelay.core.repository.DeliveryRepository;
import com.eventrelay.core.repository.EventRepository;
import com.eventrelay.core.repository.SubscriptionRepository;
import com.eventrelay.core.service.DeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Processes one delivery message: loads current state, performs the signed HTTP
 * attempt, and advances the delivery via {@link DeliveryService}. Safe to invoke
 * more than once for the same delivery (at-least-once queue semantics) — a
 * delivery already DELIVERED or DEAD is skipped.
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

    public DeliveryProcessor(DeliveryRepository deliveries, SubscriptionRepository subscriptions,
                             EventRepository events, HttpDeliveryClient httpClient,
                             DeliveryService deliveryService) {
        this.deliveries = deliveries;
        this.subscriptions = subscriptions;
        this.events = events;
        this.httpClient = httpClient;
        this.deliveryService = deliveryService;
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
            deliveryService.recordFailure(delivery, true, null,
                    "Subscription no longer exists", payloadOf(event));
            return;
        }
        if (event == null) {
            deliveryService.recordFailure(delivery, true, null,
                    "Source event no longer exists", EMPTY_PAYLOAD);
            return;
        }

        int attemptNumber = delivery.getAttemptCount() + 1;
        DeliveryOutcome outcome = httpClient.deliver(delivery, subscription, event, attemptNumber);

        if (outcome.success()) {
            deliveryService.recordSuccess(delivery, outcome.httpStatus());
        } else {
            deliveryService.recordFailure(delivery, outcome.permanent(), outcome.httpStatus(),
                    outcome.error(), event.getPayload());
        }
    }

    private String payloadOf(Event event) {
        return event != null ? event.getPayload() : EMPTY_PAYLOAD;
    }
}
