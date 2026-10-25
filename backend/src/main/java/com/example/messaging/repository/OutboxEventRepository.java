package com.example.messaging.repository;

import com.example.messaging.domain.OutboxEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    /** Candidates for the publisher: NEW and due (available_at reached), oldest first. */
    List<OutboxEvent> findByStatusAndAvailableAtLessThanEqualOrderByCreatedAtAsc(
            String status, Instant due, Pageable page);

    /** CAS claim: only one publisher instance wins a given event. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("update OutboxEvent o set o.status = 'SENT', o.sentAt = :now where o.id = :id and o.status = 'NEW'")
    int markSent(@Param("id") String id, @Param("now") Instant now);

    long countByStatus(String status);

    /** NEW events that are already due: the real publish backlog (scheduled future sends excluded). */
    long countByStatusAndAvailableAtLessThanEqual(String status, Instant due);

    boolean existsByAggregateIdAndStatus(String aggregateId, String status);
}
