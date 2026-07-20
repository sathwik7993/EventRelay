package com.eventrelay.api.web;

import com.eventrelay.api.security.ApiKeyAuthFilter;
import com.eventrelay.core.domain.Tenant;
import com.eventrelay.core.repository.DeadLetterEventRepository;
import com.eventrelay.core.repository.DeliveryRepository;
import com.eventrelay.core.repository.EventRepository;
import com.eventrelay.core.repository.SubscriptionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregate counters powering the dashboard overview. */
@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

    private final EventRepository events;
    private final DeliveryRepository deliveries;
    private final DeadLetterEventRepository deadLetters;
    private final SubscriptionRepository subscriptions;

    public StatsController(EventRepository events, DeliveryRepository deliveries,
                           DeadLetterEventRepository deadLetters,
                           SubscriptionRepository subscriptions) {
        this.events = events;
        this.deliveries = deliveries;
        this.deadLetters = deadLetters;
        this.subscriptions = subscriptions;
    }

    public record Stats(
            long events,
            long subscriptions,
            long deadLettered,
            Map<String, Long> deliveriesByStatus,
            long deliveriesTotal,
            double successRate
    ) {
    }

    @GetMapping
    public Stats stats(@RequestAttribute(ApiKeyAuthFilter.TENANT_ATTRIBUTE) Tenant tenant) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Object[] row : deliveries.countByStatusForTenant(tenant.getId())) {
            byStatus.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }

        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long delivered = byStatus.getOrDefault("DELIVERED", 0L);
        // Success rate over terminal deliveries only; in-flight ones aren't decided yet.
        long terminal = delivered + byStatus.getOrDefault("DEAD", 0L);
        double successRate = terminal == 0 ? 100.0 : (delivered * 100.0) / terminal;

        long dlq = deadLetters.findByTenantIdOrderByFailedAtDesc(
                tenant.getId(), PageRequest.of(0, 1)).getTotalElements();

        List<?> subs = subscriptions.findByTenantIdAndDeletedAtIsNull(tenant.getId());

        return new Stats(
                events.countByTenantId(tenant.getId()),
                subs.size(),
                dlq,
                byStatus,
                total,
                Math.round(successRate * 100.0) / 100.0);
    }
}
