package com.eventrelay.api.dispatch;

import com.eventrelay.core.domain.Event;
import com.eventrelay.core.domain.OutboxMessage;
import com.eventrelay.core.domain.Subscription;
import com.eventrelay.core.repository.EventRepository;
import com.eventrelay.core.repository.OutboxRepository;
import com.eventrelay.core.service.SubscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Drains the transactional outbox and delivers each event to matching
 * subscriptions.
 *
 * <p>Milestone 1 delivers synchronously inside the polling transaction, which
 * keeps semantics simple (no rows can get stuck mid-flight) at the cost of
 * holding row locks during HTTP calls — acceptable at MVP volume. Milestone 2
 * replaces the in-line HTTP call with a publish to AWS SQS, and the dispatcher
 * becomes its own horizontally scalable worker.
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxRepository outbox;
    private final EventRepository events;
    private final SubscriptionService subscriptions;
    private final HttpDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final int batchSize;

    public OutboxPoller(OutboxRepository outbox, EventRepository events,
                        SubscriptionService subscriptions, HttpDispatcher dispatcher,
                        ObjectMapper objectMapper,
                        @Value("${eventrelay.dispatch.batch-size:50}") int batchSize) {
        this.outbox = outbox;
        this.events = events;
        this.subscriptions = subscriptions;
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${eventrelay.dispatch.poll-interval-ms:500}")
    @Transactional
    public void poll() {
        List<OutboxMessage> batch = outbox.lockPendingBatch(batchSize);
        if (batch.isEmpty()) {
            return;
        }
        log.debug("Polled {} pending outbox rows", batch.size());
        for (OutboxMessage message : batch) {
            process(message);
        }
    }

    private void process(OutboxMessage message) {
        Event event = events.findById(message.getAggregateId()).orElse(null);
        if (event == null) {
            // Event vanished (should not happen); don't block the outbox on it.
            log.warn("Outbox row {} references missing event {}", message.getId(), message.getAggregateId());
            message.markProcessed();
            outbox.save(message);
            return;
        }

        List<Subscription> targets = subscriptions.matching(event.getTenantId(), event.getEventType());
        String envelope = buildEnvelope(event);
        for (Subscription target : targets) {
            dispatcher.deliver(target, event.getId(), event.getEventType(), envelope, 1);
        }

        message.markProcessed();
        outbox.save(message);
    }

    /** Builds the JSON body delivered to subscribers: metadata + the raw event data. */
    private String buildEnvelope(Event event) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("event_id", event.getId().toString());
            root.put("event_type", event.getEventType());
            root.put("created_at", event.getCreatedAt().toString());
            root.set("data", objectMapper.readTree(event.getPayload()));
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            // Payload was validated as JSON at ingest; fall back defensively.
            log.error("Failed to build envelope for event {}", event.getId(), e);
            return "{\"event_id\":\"" + event.getId() + "\"}";
        }
    }
}
