package com.eventrelay.api.web;

import com.eventrelay.api.security.ApiKeyAuthFilter;
import com.eventrelay.api.web.dto.SubscriptionDtos.CreateSubscriptionRequest;
import com.eventrelay.api.web.dto.SubscriptionDtos.SubscriptionResponse;
import com.eventrelay.core.domain.Subscription;
import com.eventrelay.core.domain.Tenant;
import com.eventrelay.core.service.SubscriptionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptions;

    public SubscriptionController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @PostMapping
    public ResponseEntity<SubscriptionResponse> create(
            @RequestAttribute(ApiKeyAuthFilter.TENANT_ATTRIBUTE) Tenant tenant,
            @Valid @RequestBody CreateSubscriptionRequest request) {
        String[] eventTypes = request.eventTypes() != null ? request.eventTypes() : new String[]{};
        Subscription subscription = subscriptions.create(
                tenant.getId(), request.targetUrl(), eventTypes, request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(subscription));
    }

    @GetMapping
    public List<SubscriptionResponse> list(
            @RequestAttribute(ApiKeyAuthFilter.TENANT_ATTRIBUTE) Tenant tenant) {
        return subscriptions.listForTenant(tenant.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    private SubscriptionResponse toResponse(Subscription s) {
        return new SubscriptionResponse(
                s.getId(), s.getTenantId(), s.getTargetUrl(), s.getEventTypes(),
                s.getDescription(), s.getStatus().name(), s.getSigningSecret(), s.getCreatedAt());
    }
}
