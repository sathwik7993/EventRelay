package com.eventrelay.api.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class EventDtos {

    private EventDtos() {
    }

    public record IngestEventRequest(
            @NotBlank String eventType,
            @NotNull JsonNode data,
            JsonNode metadata
    ) {
    }

    public record IngestEventResponse(
            UUID eventId,
            String eventType,
            String status,
            boolean duplicate,
            OffsetDateTime createdAt
    ) {
    }

    public record DeliveryAttemptResponse(
            UUID id,
            int attemptNumber,
            String status,
            Integer httpStatusCode,
            int durationMs,
            String errorMessage,
            String targetUrl,
            OffsetDateTime createdAt
    ) {
    }
}
