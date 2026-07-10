package com.eventrelay.api.web;

import com.eventrelay.api.idempotency.RedisIdempotencyCache;
import com.eventrelay.api.security.ApiKeyAuthFilter;
import com.eventrelay.api.web.dto.EventDtos.DeliveryAttemptResponse;
import com.eventrelay.api.web.dto.EventDtos.IngestEventRequest;
import com.eventrelay.api.web.dto.EventDtos.IngestEventResponse;
import com.eventrelay.api.web.dto.EventDtos.ReplayResponse;
import com.eventrelay.core.domain.DeadLetterEvent;
import com.eventrelay.core.domain.DeadLetterStatus;
import com.eventrelay.core.domain.Event;
import com.eventrelay.core.domain.Subscription;
import com.eventrelay.core.domain.Tenant;
import com.eventrelay.core.repository.DeadLetterEventRepository;
import com.eventrelay.core.repository.DeliveryAttemptRepository;
import com.eventrelay.core.repository.EventRepository;
import com.eventrelay.core.repository.SubscriptionRepository;
import com.eventrelay.core.service.DeliveryService;
import com.eventrelay.core.service.IngestCommand;
import com.eventrelay.core.service.IngestResult;
import com.eventrelay.core.service.IngestionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final IngestionService ingestion;
    private final EventRepository events;
    private final DeliveryAttemptRepository deliveryAttempts;
    private final DeadLetterEventRepository deadLetters;
    private final SubscriptionRepository subscriptions;
    private final DeliveryService deliveryService;
    private final RedisIdempotencyCache idempotencyCache;
    private final ObjectMapper objectMapper;

    public EventController(IngestionService ingestion, EventRepository events,
                           DeliveryAttemptRepository deliveryAttempts,
                           DeadLetterEventRepository deadLetters,
                           SubscriptionRepository subscriptions,
                           DeliveryService deliveryService,
                           RedisIdempotencyCache idempotencyCache,
                           ObjectMapper objectMapper) {
        this.ingestion = ingestion;
        this.events = events;
        this.deliveryAttempts = deliveryAttempts;
        this.deadLetters = deadLetters;
        this.subscriptions = subscriptions;
        this.deliveryService = deliveryService;
        this.idempotencyCache = idempotencyCache;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<IngestEventResponse> ingest(
            @RequestAttribute(ApiKeyAuthFilter.TENANT_ATTRIBUTE) Tenant tenant,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody IngestEventRequest request) {

        boolean hasKey = idempotencyKey != null && !idempotencyKey.isBlank();

        // Fast path: a cached key means we already ingested this exact request.
        if (hasKey) {
            Optional<UUID> cached = idempotencyCache.lookup(tenant.getId(), idempotencyKey);
            if (cached.isPresent()) {
                Optional<Event> existing = events.findById(cached.get());
                if (existing.isPresent()) {
                    return ResponseEntity.ok(response(existing.get(), true));
                }
            }
        }

        String payloadJson = toJson(request.data());
        String metadataJson = request.metadata() != null ? toJson(request.metadata()) : "{}";

        IngestResult result = ingestion.ingest(tenant.getId(),
                new IngestCommand(request.eventType(), idempotencyKey, payloadJson, metadataJson));

        if (hasKey) {
            idempotencyCache.remember(tenant.getId(), idempotencyKey, result.event().getId());
        }

        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(response(result.event(), result.duplicate()));
    }

    @GetMapping("/{id}")
    public IngestEventResponse get(
            @RequestAttribute(ApiKeyAuthFilter.TENANT_ATTRIBUTE) Tenant tenant,
            @PathVariable UUID id) {
        Event event = requireOwnedEvent(tenant, id);
        return response(event, false);
    }

    @GetMapping("/{id}/deliveries")
    public List<DeliveryAttemptResponse> deliveries(
            @RequestAttribute(ApiKeyAuthFilter.TENANT_ATTRIBUTE) Tenant tenant,
            @PathVariable UUID id) {
        requireOwnedEvent(tenant, id);
        return deliveryAttempts.findByEventIdOrderByAttemptNumberAsc(id).stream()
                .map(a -> new DeliveryAttemptResponse(
                        a.getId(), a.getAttemptNumber(), a.getStatus().name(),
                        a.getHttpStatusCode(), a.getDurationMs(), a.getErrorMessage(),
                        a.getTargetUrl(), a.getCreatedAt()))
                .toList();
    }

    /** Re-queues every dead-lettered delivery for this event. */
    @PostMapping("/{id}/replay")
    public ResponseEntity<ReplayResponse> replay(
            @RequestAttribute(ApiKeyAuthFilter.TENANT_ATTRIBUTE) Tenant tenant,
            @PathVariable UUID id) {
        requireOwnedEvent(tenant, id);

        List<DeadLetterEvent> pending = deadLetters.findByTenantIdAndEventIdAndStatus(
                tenant.getId(), id, DeadLetterStatus.PENDING);

        int replayed = 0;
        for (DeadLetterEvent dlq : pending) {
            Subscription subscription = subscriptions.findById(dlq.getSubscriptionId()).orElse(null);
            if (subscription != null && subscription.getDeletedAt() == null) {
                deliveryService.replay(dlq, subscription, "api");
                replayed++;
            }
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new ReplayResponse(id, replayed, "QUEUED_FOR_REPLAY"));
    }

    private Event requireOwnedEvent(Tenant tenant, UUID id) {
        Event event = events.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
        if (!event.getTenantId().equals(tenant.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found");
        }
        return event;
    }

    private IngestEventResponse response(Event event, boolean duplicate) {
        return new IngestEventResponse(event.getId(), event.getEventType(),
                duplicate ? "DUPLICATE" : "ACCEPTED", duplicate, event.getCreatedAt());
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON payload");
        }
    }
}
