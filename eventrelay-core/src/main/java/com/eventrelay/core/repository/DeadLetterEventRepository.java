package com.eventrelay.core.repository;

import com.eventrelay.core.domain.DeadLetterEvent;
import com.eventrelay.core.domain.DeadLetterStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, UUID> {

    Page<DeadLetterEvent> findByTenantIdOrderByFailedAtDesc(UUID tenantId, Pageable pageable);

    List<DeadLetterEvent> findByTenantIdAndEventIdAndStatus(
            UUID tenantId, UUID eventId, DeadLetterStatus status);
}
