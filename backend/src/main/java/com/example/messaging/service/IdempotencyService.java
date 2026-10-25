package com.example.messaging.service;

import com.example.messaging.common.ApiException;
import com.example.messaging.common.ErrorCode;
import com.example.messaging.common.Ids;
import com.example.messaging.config.MessagingProperties;
import com.example.messaging.coordination.CoordinationService;
import com.example.messaging.domain.IdempotencyRecord;
import com.example.messaging.metrics.MessagingMetrics;
import com.example.messaging.repository.IdempotencyRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotency per CONTRACT 3.3.6. The database record (unique on tenant + key) is the source of truth; the
 * coordination cache is a fast path for completed replays only and is never trusted for correctness.
 * <ul>
 *   <li>unseen key: claim it IN_PROGRESS in its own committed transaction, caller proceeds</li>
 *   <li>same key + same hash, COMPLETED: replay the stored response (HTTP 200, {@code Idempotent-Replay: true})</li>
 *   <li>same key + same hash, IN_PROGRESS: 409 IDEMPOTENCY_IN_PROGRESS (an abandoned claim past its TTL is taken over)</li>
 *   <li>same key + different hash: 422 IDEMPOTENCY_KEY_REUSED</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /** Result of {@link #begin}: either proceed with the given claim id, or replay a stored response. */
    public record Outcome(String claimId, Replay replay) {
        public boolean isReplay() {
            return replay != null;
        }
    }

    public record Replay(int status, String body, String messageId) {}

    record Cached(String hash, int status, String body, String messageId) {}

    private final IdempotencyRecordRepository repository;
    private final CoordinationService coordination;
    private final MessagingMetrics metrics;
    private final MessagingProperties props;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate requiresNew;

    public IdempotencyService(IdempotencyRecordRepository repository, CoordinationService coordination,
            MessagingMetrics metrics, MessagingProperties props, ObjectMapper mapper, Clock clock,
            PlatformTransactionManager tm) {
        this.repository = repository;
        this.coordination = coordination;
        this.metrics = metrics;
        this.props = props;
        this.mapper = mapper;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(tm);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static String cacheKey(String tenantId, String key) {
        return "idem:" + tenantId + ":" + key;
    }

    /** Must be called outside the business transaction (it commits the claim on its own). */
    public Outcome begin(String tenantId, String key, String requestHash) {
        Optional<Cached> cached = readCache(tenantId, key);
        if (cached.isPresent()) {
            Cached c = cached.get();
            if (!c.hash().equals(requestHash)) {
                throw reused();
            }
            metrics.idempotencyHit();
            return new Outcome(null, new Replay(c.status(), c.body(), c.messageId()));
        }
        Instant now = clock.instant();
        Duration claimTtl = props.idempotency().claimTtl();
        for (int attempt = 0; attempt < 3; attempt++) {
            String claimId = tryClaim(tenantId, key, requestHash, now, now.plus(claimTtl));
            if (claimId != null) {
                return new Outcome(claimId, null);
            }
            IdempotencyRecord existing = repository.findByTenantIdAndIdemKey(tenantId, key).orElse(null);
            if (existing == null) {
                continue; // released in between: try to claim again
            }
            if (!existing.getRequestHash().equals(requestHash)) {
                throw reused();
            }
            if (IdempotencyRecord.COMPLETED.equals(existing.getStatus())) {
                metrics.idempotencyHit();
                writeCache(tenantId, key, new Cached(requestHash, existing.getResponseStatus(),
                        existing.getResponseBody(), existing.getMessageId()), existing.getExpiresAt());
                return new Outcome(null, new Replay(existing.getResponseStatus(), existing.getResponseBody(),
                        existing.getMessageId()));
            }
            // IN_PROGRESS: take over only when the claim has expired (crashed worker)
            Boolean tookOver = requiresNew.execute(s ->
                    repository.takeOver(existing.getId(), requestHash, now, now.plus(claimTtl)) == 1);
            if (Boolean.TRUE.equals(tookOver)) {
                return new Outcome(existing.getId(), null);
            }
            throw new ApiException.ConflictException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                    "A request with this Idempotency-Key is still being processed");
        }
        throw new ApiException.ConflictException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                "A request with this Idempotency-Key is still being processed");
    }

    private ApiException reused() {
        return new ApiException.UnprocessableException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                "Idempotency-Key was already used with a different request body");
    }

    private String tryClaim(String tenantId, String key, String hash, Instant now, Instant expires) {
        try {
            return requiresNew.execute(s -> {
                IdempotencyRecord r = new IdempotencyRecord();
                r.setId(Ids.newId());
                r.setTenantId(tenantId);
                r.setIdemKey(key);
                r.setRequestHash(hash);
                r.setStatus(IdempotencyRecord.IN_PROGRESS);
                r.setCreatedAt(now);
                r.setExpiresAt(expires);
                repository.saveAndFlush(r);
                return r.getId();
            });
        } catch (DataIntegrityViolationException dup) {
            return null;
        }
    }

    /** Joins the caller's transaction: the record flips to COMPLETED atomically with the business change. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(String tenantId, String key, String claimId, String requestHash, int status, String body,
            String messageId) {
        Instant expires = clock.instant().plus(props.idempotency().recordTtl());
        repository.complete(claimId, status, body, messageId, expires);
        // write the fast-path cache only after commit is not possible without sync hooks; a stale cache entry can only
        // point at the committed truth because the DB record is identical, so write now and tolerate rollback via TTL.
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        writeCache(tenantId, key, new Cached(requestHash, status, body, messageId), expires);
                    }
                });
    }

    /** Releases an IN_PROGRESS claim after a failed business transaction so the client can retry the same key. */
    public void release(String claimId) {
        try {
            requiresNew.executeWithoutResult(s -> repository.releaseClaim(claimId));
        } catch (RuntimeException e) {
            log.warn("Could not release idempotency claim {}: {}", claimId, e.getClass().getSimpleName());
        }
    }

    private Optional<Cached> readCache(String tenantId, String key) {
        try {
            return coordination.getCached(cacheKey(tenantId, key)).map(json -> {
                try {
                    return mapper.readValue(json, Cached.class);
                } catch (Exception e) {
                    return null;
                }
            });
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private void writeCache(String tenantId, String key, Cached c, Instant expires) {
        try {
            Duration ttl = Duration.between(clock.instant(), expires);
            if (!ttl.isNegative() && !ttl.isZero()) {
                coordination.putCached(cacheKey(tenantId, key), mapper.writeValueAsString(c), ttl);
            }
        } catch (Exception e) {
            log.debug("Idempotency cache write skipped: {}", e.getClass().getSimpleName());
        }
    }
}
