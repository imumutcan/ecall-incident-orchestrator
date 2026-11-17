package com.automotive.ecall.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    Optional<OutboxEvent> findByAggregateId(String aggregateId);

    long countByStatus(OutboxStatus status);

    /**
     * Fetches a bounded batch of PENDING outbox events that are due for processing,
     * acquiring row-level locks with SKIP LOCKED so multiple service instances can
     * poll concurrently without blocking or duplicate processing.
     */
    @Query(
        value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING'
              AND next_attempt_at <= :now
            ORDER BY created_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """,
        nativeQuery = true
    )
    List<OutboxEvent> findPendingBatchForUpdateSkipLocked(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize);
}
