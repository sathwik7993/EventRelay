package com.eventrelay.core.repository;

import com.eventrelay.core.domain.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    List<Subscription> findByTenantIdAndDeletedAtIsNull(UUID tenantId);

    List<Subscription> findByTenantIdAndStatusAndDeletedAtIsNull(
            UUID tenantId, com.eventrelay.core.domain.SubscriptionStatus status);
}
