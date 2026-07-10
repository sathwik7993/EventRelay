package com.eventrelay.api.web.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class DeadLetterDtos {

    private DeadLetterDtos() {
    }

    public record DeadLetterResponse(
            UUID id,
            UUID eventId,
            UUID subscriptionId,
            String eventType,
            String failureReason,
            Integer lastHttpStatus,
            int totalAttempts,
            String status,
            OffsetDateTime failedAt
    ) {
    }

    public record DeadLetterPage(
            List<DeadLetterResponse> data,
            int page,
            int size,
            long totalElements
    ) {
    }
}
