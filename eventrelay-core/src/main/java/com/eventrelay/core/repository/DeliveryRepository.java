package com.eventrelay.core.repository;

import com.eventrelay.core.domain.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    /**
     * Claims a batch of deliveries eligible for (re)dispatch — PENDING or
     * RETRYING and past their {@code next_attempt_at}. {@code SKIP LOCKED} lets
     * multiple scheduler instances run without contending. Must run in a transaction.
     */
    @Query(value = """
            SELECT * FROM deliveries
            WHERE status IN ('PENDING', 'RETRYING')
              AND next_attempt_at <= :now
            ORDER BY next_attempt_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Delivery> claimDue(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    /**
     * Finds QUEUED deliveries whose lease has expired — a worker likely crashed
     * after the row was published but before it was advanced. These are reclaimed
     * back to RETRYING so the scheduler re-dispatches them.
     */
    @Query(value = """
            SELECT * FROM deliveries
            WHERE status = 'QUEUED'
              AND leased_at < :cutoff
            ORDER BY leased_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Delivery> claimStaleQueued(@Param("cutoff") OffsetDateTime cutoff, @Param("limit") int limit);

    List<Delivery> findByEventIdOrderByCreatedAtAsc(UUID eventId);

    /** Delivery counts grouped by status, for dashboard tiles. */
    @Query(value = "SELECT status, count(*) FROM deliveries WHERE tenant_id = :tenantId GROUP BY status",
            nativeQuery = true)
    List<Object[]> countByStatusForTenant(@Param("tenantId") UUID tenantId);
}
