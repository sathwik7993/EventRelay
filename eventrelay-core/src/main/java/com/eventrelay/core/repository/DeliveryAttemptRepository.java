package com.eventrelay.core.repository;

import com.eventrelay.core.domain.DeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {

    List<DeliveryAttempt> findByEventIdOrderByAttemptNumberAsc(UUID eventId);
}
