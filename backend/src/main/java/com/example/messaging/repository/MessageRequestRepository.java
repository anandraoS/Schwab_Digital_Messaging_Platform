package com.example.messaging.repository;

import com.example.messaging.domain.MessageRequest;
import com.example.messaging.domain.MessageStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRequestRepository
        extends JpaRepository<MessageRequest, String>, JpaSpecificationExecutor<MessageRequest> {

    Optional<MessageRequest> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);

    Optional<MessageStatusView> findStatusViewById(String id);

    /**
     * Compare-and-set status change: returns 1 when this caller won the race, 0 when the message was no longer in
     * {@code from}. Bumps the optimistic-lock version so concurrent entity updates still detect the change.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("update MessageRequest m set m.status = :to, m.statusReason = :reason, m.statusChangedAt = :now, "
            + "m.updatedAt = :now, m.version = m.version + 1 where m.id = :id and m.status = :from")
    int casStatus(@Param("id") String id, @Param("from") MessageStatus from, @Param("to") MessageStatus to,
            @Param("reason") String reason, @Param("now") Instant now);

    @Query("select m.status as status, count(m) as count from MessageRequest m where m.createdAt >= :since "
            + "group by m.status")
    List<StatusCount> countByStatusSince(@Param("since") Instant since);

    @Query("select m.status as status, count(m) as count from MessageRequest m where m.tenantId = :tenantId "
            + "and m.createdAt >= :since group by m.status")
    List<StatusCount> countByStatusSinceForTenant(@Param("tenantId") String tenantId, @Param("since") Instant since);

    /** Retry scheduler / sweeper support (stage 2). */
    List<MessageRequest> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            MessageStatus status, Instant due, Pageable page);

    List<MessageRequest> findByStatusAndLockedUntilBefore(MessageStatus status, Instant before, Pageable page);

    List<MessageRequest> findByStatusAndStatusChangedAtBefore(MessageStatus status, Instant before, Pageable page);

    long countByStatus(MessageStatus status);

    /**
     * CAS "touch" used by the poller when it re-publishes a stale QUEUED message: moves the staleness clock forward
     * only while the message is still QUEUED at the same attempt, so concurrent pollers publish at most one envelope.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("update MessageRequest m set m.statusChangedAt = :now where m.id = :id and m.status = "
            + "com.example.messaging.domain.MessageStatus.QUEUED and m.attemptCount = :attemptCount "
            + "and m.statusChangedAt < :before")
    int touchQueued(@Param("id") String id, @Param("attemptCount") int attemptCount,
            @Param("before") Instant before, @Param("now") Instant now);

    /** Records the claim of an attempt by a consumer (attempt number, lock deadline, channel in use). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("update MessageRequest m set m.attemptCount = :attemptNo, m.lockedUntil = :lockedUntil, "
            + "m.channelUsed = :channel, m.nextAttemptAt = null where m.id = :id")
    int markClaimed(@Param("id") String id, @Param("attemptNo") int attemptNo, @Param("lockedUntil") Instant lockedUntil,
            @Param("channel") com.example.messaging.domain.Channel channel);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("update MessageRequest m set m.nextAttemptAt = :next where m.id = :id")
    int setNextAttemptAt(@Param("id") String id, @Param("next") Instant next);

    /** Moves the message to its next fallback channel and gives it a fresh attempt budget. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("update MessageRequest m set m.fallbackIndex = :index, m.channelUsed = :channel, m.maxAttempts = :max, "
            + "m.nextAttemptAt = null where m.id = :id")
    int applyFallback(@Param("id") String id, @Param("index") int index,
            @Param("channel") com.example.messaging.domain.Channel channel, @Param("max") int max);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("update MessageRequest m set m.maxAttempts = :max where m.id = :id")
    int setMaxAttempts(@Param("id") String id, @Param("max") int max);

    @Query("select m.channel as channel, count(m) as count from MessageRequest m where m.createdAt >= :since "
            + "group by m.channel")
    List<ChannelCount> countByChannelSince(@Param("since") Instant since);

    @Query("select m.channel as channel, count(m) as count from MessageRequest m where m.tenantId = :tenantId "
            + "and m.createdAt >= :since group by m.channel")
    List<ChannelCount> countByChannelSinceForTenant(@Param("tenantId") String tenantId, @Param("since") Instant since);

    long countByBulkJobId(String bulkJobId);
}
