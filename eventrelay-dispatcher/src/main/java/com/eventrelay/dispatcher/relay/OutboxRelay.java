package com.eventrelay.dispatcher.relay;

import com.eventrelay.core.domain.Event;
import com.eventrelay.core.domain.OutboxMessage;
import com.eventrelay.core.domain.Subscription;
import com.eventrelay.core.repository.EventRepository;
import com.eventrelay.core.repository.OutboxRepository;
import com.eventrelay.core.service.DeliveryService;
import com.eventrelay.core.service.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Drains the transactional outbox: for each pending event, fans it out into one
 * {@code delivery} row per matching subscription, then marks the outbox row
 * processed — all in one transaction. This is the bridge from the durable event
 * log to the retryable delivery pipeline.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final EventRepository events;
    private final SubscriptionService subscriptions;
    private final DeliveryService deliveries;
    private final int batchSize;

    public OutboxRelay(OutboxRepository outbox, EventRepository events,
                       SubscriptionService subscriptions, DeliveryService deliveries,
                       @Value("${eventrelay.relay.batch-size:100}") int batchSize) {
        this.outbox = outbox;
        this.events = events;
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${eventrelay.relay.poll-interval-ms:500}")
    @Transactional
    public void relay() {
        List<OutboxMessage> batch = outbox.lockPendingBatch(batchSize);
        for (OutboxMessage message : batch) {
            Event event = events.findById(message.getAggregateId()).orElse(null);
            if (event != null) {
                List<Subscription> targets =
                        subscriptions.matching(event.getTenantId(), event.getEventType());
                deliveries.createDeliveries(event, targets);
                log.debug("Relayed event {} to {} deliveries", event.getId(), targets.size());
            } else {
                log.warn("Outbox {} references missing event {}", message.getId(), message.getAggregateId());
            }
            message.markProcessed();
            outbox.save(message);
        }
    }
}
