package com.eventrelay.api.web;

import com.eventrelay.api.security.ApiKeyAuthFilter;
import com.eventrelay.api.web.dto.EventDtos.DeliveryAttemptResponse;
import com.eventrelay.api.web.dto.EventDtos.IngestEventRequest;
import com.eventrelay.api.web.dto.EventDtos.IngestEventResponse;
import com.eventrelay.core.domain.Event;
import com.eventrelay.core.domain.Tenant;
import com.eventrelay.core.repository.DeliveryAttemptRepository;
import com.eventrelay.core.repository.EventRepository;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final IngestionService ingestion;
    private final EventRepository events;
    private final DeliveryAttemptRepository deliveryAttempts;
    private final ObjectMapper objectMapper;

    public EventController(IngestionService ingestion, EventRepository events,
                           DeliveryAttemptRepository deliveryAttempts, ObjectMapper objectMapper) {
        this.ingestion = ingestion;
        this.events = events;
        this.deliveryAttempts = deliveryAttempts;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<IngestEventResponse> ingest(
            @RequestAttribute(ApiKeyAuthFilter.TENANT_ATTRIBUTE) Tenant tenant,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody IngestEventRequest request) {

        String payloadJson = toJson(request.data());
        String metadataJson = request.metadata() != null ? toJson(request.metadata()) : "{}";

        IngestResult result = ingestion.ingest(tenant.getId(),
                new IngestCommand(request.eventType(), idempotencyKey, payloadJson, metadataJson));

        Event event = result.event();
        IngestEventResponse body = new IngestEventResponse(
                event.getId(), event.getEventType(),
                result.duplicate() ? "DUPLICATE" : "ACCEPTED",
                result.duplicate(), event.getCreatedAt());

        // 202 for a freshly accepted event; 200 for an idempotent replay.
        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(body);
    }

    @GetMapping("/{id}")
    public IngestEventResponse get(
            @RequestAttribute(ApiKeyAuthFilter.TENANT_ATTRIBUTE) Tenant tenant,
            @PathVariable UUID id) {
        Event event = requireOwnedEvent(tenant, id);
        return new IngestEventResponse(event.getId(), event.getEventType(),
                "STORED", false, event.getCreatedAt());
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

    private Event requireOwnedEvent(Tenant tenant, UUID id) {
        Event event = events.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
        if (!event.getTenantId().equals(tenant.getId())) {
            // Do not leak existence of another tenant's event.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found");
        }
        return event;
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON payload");
        }
    }
}
