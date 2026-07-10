package com.eventrelay.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class SubscriptionDtos {

    private SubscriptionDtos() {
    }

    public record CreateSubscriptionRequest(
            @NotBlank
            @Pattern(regexp = "^https?://.*", message = "target_url must be an http(s) URL")
            String targetUrl,
            String[] eventTypes,
            String description
    ) {
    }

    public record SubscriptionResponse(
            UUID id,
            UUID tenantId,
            String targetUrl,
            String[] eventTypes,
            String description,
            String status,
            String signingSecret,
            OffsetDateTime createdAt
    ) {
    }
}
