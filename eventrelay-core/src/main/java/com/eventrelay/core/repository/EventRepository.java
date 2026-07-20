package com.eventrelay.core.repository;

import com.eventrelay.core.domain.Event;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID> {

    Optional<Event> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    Page<Event> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    Page<Event> findByTenantIdAndEventTypeOrderByCreatedAtDesc(
            UUID tenantId, String eventType, Pageable pageable);

    long countByTenantId(UUID tenantId);
}
