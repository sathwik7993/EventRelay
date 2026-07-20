package com.eventrelay.api.web;

import com.eventrelay.api.idempotency.RedisIdempotencyCache;
import com.eventrelay.api.ratelimit.RateLimiter;
import com.eventrelay.api.security.ApiKeyAuthFilter;
import com.eventrelay.api.tracing.TraceMetadataWriter;
import com.eventrelay.api.web.dto.EventDtos.DeliveryAttemptResponse;
import com.eventrelay.api.web.dto.EventDtos.EventPage;
import com.eventrelay.api.web.dto.EventDtos.EventSummary;
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
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final RateLimiter rateLimiter;
    private final MeterRegistry metrics;
    private final TraceMetadataWriter traceMetadataWriter;
    private final ObjectMapper objectMapper;

    public EventController(IngestionService ingestion, EventRepository events,
                           DeliveryAttemptRepository deliveryAttempts,
                           DeadLetterEventRepository deadLetters,
                           SubscriptionRepository subscriptions,
                           DeliveryService deliveryService,
                           RedisIdempotencyCache idempotencyCache,
                           RateLimiter rateLimiter,
                           MeterRegistry metrics,
                           TraceMetadataWriter traceMetadataWriter,
                           ObjectMapper objectMapper) {
        this.ingestion = ingestion;
        this.events = events;
        this.deliveryAttempts = deliveryAttempts;
        this.deadLetters = deadLetters;
        this.subscriptions = subscriptions;
        this.deliveryService = deliveryService;
        this.idempotencyCache = idempotencyCache;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.traceMetadataWriter = traceMetadataWriter;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<IngestEventResponse> ingest(
            @RequestAttribute(ApiKeyAuthFilter.TENANT_ATTRIBUTE) Tenant tenant,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody IngestEventRequest request) {

        if (!rateLimiter.allow(tenant)) {
            metrics.counter("eventrelay.ingest.rate_limited").increment();
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Per-tenant rate limit exceeded");
        }

        boolean hasKey = idempotencyKey != null && !idempotencyKey.isBlank();

        // Fast path: a cached key means we already ingested this exact request.
        if (hasKey) {
            Optional<UUID> cached = idempotencyCache.lookup(tenant.getId(), idempotencyKey);
            if (cached.isPresent()) {
                Optional<Event> existing = events.findById(cached.get());
                if (existing.isPresent()) {
                    metrics.counter("eventrelay.events.ingested", "result", "duplicate").increment();
                    return ResponseEntity.ok(response(existing.get(), true));
                }
            }
        }

        String payloadJson = toJson(request.data());
        // Carry the current trace context with the event so the dispatcher can
        // continue the same trace when it delivers, minutes or retries later.
        ObjectNode metadata = request.metadata() != null && request.metadata().isObject()
                ? ((ObjectNode) request.metadata()).deepCopy()
                : objectMapper.createObjectNode();
        traceMetadataWriter.writeInto(metadata);
        String metadataJson = toJson(metadata);

        IngestResult result = ingestion.ingest(tenant.getId(),
                new IngestCommand(request.eventType(), idempotencyKey, payloadJson, metadataJson));

        if (hasKey) {
            idempotencyCache.remember(tenant.getId(), idempotencyKey, result.event().getId());
        }
        metrics.counter("eventrelay.events.ingested",
                "result", result.duplicate() ? "duplicate" : "accepted").increment();

        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(response(result.event(), result.duplicate()));
    }

    /** Paginated event log for the tenant, newest first. */
    @GetMapping
    public EventPage list(
            @RequestAttribute(ApiKeyAuthFilter.TENANT_ATTRIBUTE) Tenant tenant,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Page<Event> result = (eventType == null || eventType.isBlank())
                ? events.findByTenantIdOrderByCreatedAtDesc(tenant.getId(), pageable)
                : events.findByTenantIdAndEventTypeOrderByCreatedAtDesc(tenant.getId(), eventType, pageable);

        return new EventPage(
                result.getContent().stream()
                        .map(e -> new EventSummary(e.getId(), e.getEventType(),
                                e.getIdempotencyKey(), e.getCreatedAt()))
                        .toList(),
                result.getNumber(), result.getSize(), result.getTotalElements());
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
