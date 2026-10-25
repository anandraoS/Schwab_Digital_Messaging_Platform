package com.example.messaging.repository;

import com.example.messaging.domain.IdempotencyRecord;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {

    Optional<IdempotencyRecord> findByTenantIdAndIdemKey(String tenantId, String idemKey);

    /** Takes over an abandoned IN_PROGRESS claim (expired lock) atomically. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("update IdempotencyRecord r set r.requestHash = :hash, r.createdAt = :now, r.expiresAt = :expires "
            + "where r.id = :id and r.status = 'IN_PROGRESS' and r.expiresAt < :now")
    int takeOver(@Param("id") String id, @Param("hash") String hash, @Param("now") Instant now,
            @Param("expires") Instant expires);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("update IdempotencyRecord r set r.status = 'COMPLETED', r.responseStatus = :status, "
            + "r.responseBody = :body, r.messageId = :messageId, r.expiresAt = :expires where r.id = :id")
    int complete(@Param("id") String id, @Param("status") int status, @Param("body") String body,
            @Param("messageId") String messageId, @Param("expires") Instant expires);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("delete from IdempotencyRecord r where r.id = :id and r.status = 'IN_PROGRESS'")
    int releaseClaim(@Param("id") String id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("delete from IdempotencyRecord r where r.expiresAt < :now and r.status = 'COMPLETED'")
    int purgeExpired(@Param("now") Instant now);
}
