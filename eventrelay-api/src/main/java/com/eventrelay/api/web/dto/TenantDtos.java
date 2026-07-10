package com.eventrelay.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public final class TenantDtos {

    private TenantDtos() {
    }

    public record CreateTenantRequest(
            @NotBlank String name,
            @NotBlank
            @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}$",
                    message = "slug must be lowercase alphanumeric with hyphens")
            String slug
    ) {
    }

    public record CreateTenantResponse(
            UUID tenantId,
            String name,
            String slug,
            String apiKey,
            String warning
    ) {
    }
}
