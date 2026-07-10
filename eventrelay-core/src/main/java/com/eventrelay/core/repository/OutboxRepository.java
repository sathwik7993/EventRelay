package com.eventrelay.core.repository;

import com.eventrelay.core.domain.OutboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxMessage, Long> {

    /**
     * Claims a batch of PENDING outbox rows for this poller instance.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} lets multiple poller instances run
     * concurrently without contending on the same rows: each locks a disjoint
     * set and other pollers skip locked rows instead of blocking. Must be
     * invoked inside a transaction so the locks are held until commit.
     */
    @Query(value = """
            SELECT * FROM outbox
            WHERE status = 'PENDING'
            ORDER BY id ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxMessage> lockPendingBatch(@Param("limit") int limit);
}
