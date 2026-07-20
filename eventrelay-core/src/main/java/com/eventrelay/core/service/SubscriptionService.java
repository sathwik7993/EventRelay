package com.eventrelay.core.service;

import com.eventrelay.common.crypto.SigningSecrets;
import com.eventrelay.core.domain.Subscription;
import com.eventrelay.core.domain.SubscriptionStatus;
import com.eventrelay.core.repository.SubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptions;
    private final TargetUrlValidator targetUrlValidator;

    public SubscriptionService(SubscriptionRepository subscriptions,
                               TargetUrlValidator targetUrlValidator) {
        this.subscriptions = subscriptions;
        this.targetUrlValidator = targetUrlValidator;
    }

    @Transactional
    public Subscription create(UUID tenantId, String targetUrl, String[] eventTypes, String description) {
        // SSRF guard: reject internal/private targets before we ever store them.
        targetUrlValidator.validate(targetUrl);

        String signingSecret = SigningSecrets.generate();
        Subscription subscription = new Subscription(
                UUID.randomUUID(), tenantId, targetUrl, eventTypes, signingSecret);
        subscription.setDescription(description);
        return subscriptions.save(subscription);
    }

    @Transactional(readOnly = true)
    public List<Subscription> listForTenant(UUID tenantId) {
        return subscriptions.findByTenantIdAndDeletedAtIsNull(tenantId);
    }

    /** Active subscriptions for a tenant whose filter matches the given event type. */
    @Transactional(readOnly = true)
    public List<Subscription> matching(UUID tenantId, String eventType) {
        return subscriptions
                .findByTenantIdAndStatusAndDeletedAtIsNull(tenantId, SubscriptionStatus.ACTIVE)
                .stream()
                .filter(s -> s.matches(eventType))
                .toList();
    }
}
