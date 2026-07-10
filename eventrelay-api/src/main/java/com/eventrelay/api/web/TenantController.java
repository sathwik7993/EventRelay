package com.eventrelay.api.web;

import com.eventrelay.api.web.dto.TenantDtos.CreateTenantRequest;
import com.eventrelay.api.web.dto.TenantDtos.CreateTenantResponse;
import com.eventrelay.core.service.TenantService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant administration.
 *
 * <p>Milestone 1 note: tenant creation is a bootstrap/admin action and is left
 * unauthenticated. In a later milestone it moves behind an admin credential.
 */
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantService tenants;

    public TenantController(TenantService tenants) {
        this.tenants = tenants;
    }

    @PostMapping
    public ResponseEntity<CreateTenantResponse> create(@Valid @RequestBody CreateTenantRequest request) {
        TenantService.Created created = tenants.createTenant(request.name(), request.slug());
        CreateTenantResponse body = new CreateTenantResponse(
                created.tenant().getId(),
                created.tenant().getName(),
                created.tenant().getSlug(),
                created.rawApiKey(),
                "Store this API key now — it is shown only once and cannot be recovered."
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
}
