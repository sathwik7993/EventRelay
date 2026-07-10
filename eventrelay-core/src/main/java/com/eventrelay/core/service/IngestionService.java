package com.eventrelay.core.service;

import com.eventrelay.core.domain.Event;
import com.eventrelay.core.domain.OutboxMessage;
import com.eventrelay.core.repository.EventRepository;
import com.eventrelay.core.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Implements the <b>transactional outbox pattern</b>: the event log row and its
 * outbox row are written in a single database transaction. Either both commit or
 * neither does, so an event can never be persisted without also being scheduled
 * for dispatch (the "dual-write problem" the platform exists to solve).
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final EventRepository events;
    private final OutboxRepository outbox;

    public IngestionService(EventRepository events, OutboxRepository outbox) {
        this.events = events;
        this.outbox = outbox;
    }

    @Transactional
    public IngestResult ingest(UUID tenantId, IngestCommand cmd) {
        if (cmd.idempotencyKey() != null && !cmd.idempotencyKey().isBlank()) {
            Optional<Event> existing =
                    events.findByTenantIdAndIdempotencyKey(tenantId, cmd.idempotencyKey());
            if (existing.isPresent()) {
                log.debug("Idempotent replay for tenant={} key={}", tenantId, cmd.idempotencyKey());
                return new IngestResult(existing.get(), true);
            }
        }

        UUID eventId = UUID.randomUUID();
        Event event = new Event(eventId, tenantId, cmd.eventType(),
                cmd.idempotencyKey(), cmd.payloadJson(), cmd.metadataJson());
        OutboxMessage message = new OutboxMessage(eventId, cmd.eventType(), cmd.payloadJson());

        try {
            events.save(event);
            outbox.save(message);
        } catch (DataIntegrityViolationException e) {
            // Concurrent request won the race on the same idempotency key.
            // The unique constraint protected us; return the winner's row.
            if (cmd.idempotencyKey() != null) {
                Optional<Event> winner =
                        events.findByTenantIdAndIdempotencyKey(tenantId, cmd.idempotencyKey());
                if (winner.isPresent()) {
                    return new IngestResult(winner.get(), true);
                }
            }
            throw e;
        }

        log.debug("Ingested event id={} type={} tenant={}", eventId, cmd.eventType(), tenantId);
        return new IngestResult(event, false);
    }
}
