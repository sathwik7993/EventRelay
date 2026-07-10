package com.eventrelay.api.web;

import com.eventrelay.api.security.ApiKeyAuthFilter;
import com.eventrelay.api.web.dto.DeadLetterDtos.DeadLetterPage;
import com.eventrelay.api.web.dto.DeadLetterDtos.DeadLetterResponse;
import com.eventrelay.core.domain.DeadLetterEvent;
import com.eventrelay.core.domain.Tenant;
import com.eventrelay.core.repository.DeadLetterEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dead-letter")
public class DeadLetterController {

    private final DeadLetterEventRepository deadLetters;

    public DeadLetterController(DeadLetterEventRepository deadLetters) {
        this.deadLetters = deadLetters;
    }

    @GetMapping
    public DeadLetterPage list(
            @RequestAttribute(ApiKeyAuthFilter.TENANT_ATTRIBUTE) Tenant tenant,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int cappedSize = Math.min(Math.max(size, 1), 100);
        Page<DeadLetterEvent> result = deadLetters.findByTenantIdOrderByFailedAtDesc(
                tenant.getId(), PageRequest.of(Math.max(page, 0), cappedSize));

        return new DeadLetterPage(
                result.getContent().stream().map(this::toResponse).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements());
    }

    private DeadLetterResponse toResponse(DeadLetterEvent d) {
        return new DeadLetterResponse(
                d.getId(), d.getEventId(), d.getSubscriptionId(), d.getEventType(),
                d.getFailureReason(), d.getLastHttpStatus(), d.getTotalAttempts(),
                d.getStatus().name(), d.getFailedAt());
    }
}
