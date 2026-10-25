package com.example.messaging.batch;

import com.example.messaging.common.Ids;
import com.example.messaging.domain.BulkJobStatus;
import com.example.messaging.metrics.MessagingMetrics;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JDBC persistence for everything the running job mutates: the {@code (job, externalRef)} ledger, failure records and
 * the progress counters of {@code bulk_job}.
 *
 * <p>Why JDBC and not the JPA entity: {@code BulkJob} carries a {@code @Version}; many chunk threads incrementing the
 * same row through JPA would fail with optimistic-lock conflicts. Counter updates are single atomic
 * {@code SET x = x + ?} statements instead.
 *
 * <p>Counter deltas are accumulated per transaction and written once, in {@code beforeCommit}, so the {@code bulk_job}
 * row lock is held for microseconds and a rolled-back chunk leaves no trace in the counters (they commit atomically
 * with the messages of the chunk).
 */
@Component
public class BulkJobStore {

    /** Outcome of {@link #claimRow}. */
    public enum Claim {
        /** first time this externalRef is seen in the job */
        NEW,
        /** the same line was claimed before (restart replay or chunk retry): process it again, submit is idempotent */
        SAME_LINE,
        /** the externalRef already belongs to another line of this job */
        DUPLICATE
    }

    public record ClaimResult(Claim claim, long firstLine) {}

    private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final MessagingMetrics metrics;
    private final TransactionTemplate requiresNew;

    public BulkJobStore(JdbcTemplate jdbc, Clock clock, MessagingMetrics metrics, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.metrics = metrics;
        this.requiresNew = new TransactionTemplate(tm);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ ledger / duplicates

    /**
     * Claims {@code externalRef} for {@code lineNo} in its own transaction (so concurrent chunk threads see each
     * other's claims immediately; the primary key arbitrates races).
     */
    public ClaimResult claimRow(String jobId, String externalRef, long lineNo) {
        try {
            ClaimResult r = requiresNew.execute(s -> tryClaim(jobId, externalRef, lineNo));
            if (r != null) {
                return r;
            }
        } catch (DuplicateKeyException race) {
            // another thread inserted the same ref between our SELECT and INSERT: fall through and read it
        }
        Long first = requiresNew.execute(s -> existingLine(jobId, externalRef));
        if (first == null) {
            throw new IllegalStateException("ledger row vanished");
        }
        return result(first, lineNo);
    }

    private ClaimResult tryClaim(String jobId, String externalRef, long lineNo) {
        Long existing = existingLine(jobId, externalRef);
        if (existing != null) {
            return result(existing, lineNo);
        }
        jdbc.update("insert into bulk_job_row (job_id, external_ref, line_no, created_at) values (?, ?, ?, ?)",
                jobId, externalRef, lineNo, now());
        return new ClaimResult(Claim.NEW, lineNo);
    }

    private Long existingLine(String jobId, String externalRef) {
        List<Long> l = jdbc.queryForList("select line_no from bulk_job_row where job_id = ? and external_ref = ?",
                Long.class, jobId, externalRef);
        return l.isEmpty() ? null : l.get(0);
    }

    private static ClaimResult result(long existingLine, long lineNo) {
        return new ClaimResult(existingLine == lineNo ? Claim.SAME_LINE : Claim.DUPLICATE, existingLine);
    }

    // ------------------------------------------------------------------ failures and counters

    public enum Outcome { FAILED, DUPLICATE }

    /**
     * Records one rejected row (joins the caller's transaction) and counts it, exactly once per
     * {@code (job, line)} even when a chunk is retried or the job restarted.
     *
     * @param rawLineHash SHA-256 of the raw line (never the line itself)
     * @return true when the failure was new
     */
    public boolean recordFailure(String jobId, long lineNo, Outcome outcome, String reason, String rawLineHash) {
        Integer exists = jdbc.queryForObject("select count(*) from bulk_job_failure where job_id = ? and line_no = ?",
                Integer.class, jobId, lineNo);
        if (exists != null && exists > 0) {
            return false;
        }
        String r = reason == null ? "UNKNOWN" : reason.length() > 500 ? reason.substring(0, 500) : reason;
        jdbc.update("insert into bulk_job_failure (id, job_id, line_no, reason, raw_line_hash, created_at) "
                + "values (?, ?, ?, ?, ?, ?)", Ids.newId(), jobId, lineNo, r, rawLineHash, now());
        addDelta(jobId, 0, outcome == Outcome.FAILED ? 1 : 0, outcome == Outcome.DUPLICATE ? 1 : 0);
        return true;
    }

    /**
     * Records a duplicate row in its own transaction. Duplicates are <i>filtered</i> by the processor, and Spring Batch
     * never re-processes a filtered item when it rolls a chunk back to re-run it without a skipped sibling; a record
     * made inside the chunk transaction would be lost with that rollback. Doing it independently is safe because the
     * outcome is deterministic (the ledger decides) and the insert is idempotent per {@code (job, line)}, counter
     * included.
     */
    public boolean recordDuplicate(String jobId, long lineNo, String reason, String rawLineHash) {
        Boolean inserted = requiresNew.execute(s -> {
            Integer exists = jdbc.queryForObject(
                    "select count(*) from bulk_job_failure where job_id = ? and line_no = ?", Integer.class, jobId, lineNo);
            if (exists != null && exists > 0) {
                return false;
            }
            String r = reason.length() > 500 ? reason.substring(0, 500) : reason;
            jdbc.update("insert into bulk_job_failure (id, job_id, line_no, reason, raw_line_hash, created_at) "
                    + "values (?, ?, ?, ?, ?, ?)", Ids.newId(), jobId, lineNo, r, rawLineHash, now());
            applyDelta(jobId, 0, 0, 1);
            return true;
        });
        return Boolean.TRUE.equals(inserted);
    }

    /** Counts successfully submitted rows. */
    public void addSucceeded(String jobId, long n) {
        if (n > 0) {
            addDelta(jobId, n, 0, 0);
        }
    }

    private void addDelta(String jobId, long succeeded, long failed, long duplicate) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            applyDelta(jobId, succeeded, failed, duplicate);
            publishProgress(jobId);
            return;
        }
        Pending p = PENDING.get();
        if (p == null) {
            p = new Pending();
            PENDING.set(p);
            TransactionSynchronizationManager.registerSynchronization(new FlushSync(p));
        }
        long[] d = p.deltas.computeIfAbsent(jobId, k -> new long[3]);
        d[0] += succeeded;
        d[1] += failed;
        d[2] += duplicate;
    }

    private void applyDelta(String jobId, long succeeded, long failed, long duplicate) {
        jdbc.update("update bulk_job set processed = processed + ?, succeeded = succeeded + ?, failed = failed + ?, "
                + "duplicate = duplicate + ?, updated_at = ? where id = ?", succeeded + failed + duplicate, succeeded,
                failed, duplicate, now(), jobId);
    }

    private void publishProgress(String jobId) {
        try {
            jdbc.query("select total, processed from bulk_job where id = ?", rs -> {
                long total = rs.getLong(1);
                long processed = rs.getLong(2);
                metrics.bulkProgress(jobId, total <= 0 ? 0.0 : Math.min(1.0, processed / (double) total));
            }, jobId);
        } catch (RuntimeException ignored) {
            // metrics are best effort
        }
    }

    private static final class Pending {
        final Map<String, long[]> deltas = new HashMap<>();
    }

    private final class FlushSync implements TransactionSynchronization {
        private final Pending pending;

        FlushSync(Pending pending) {
            this.pending = pending;
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            pending.deltas.forEach((jobId, d) -> applyDelta(jobId, d[0], d[1], d[2]));
        }

        @Override
        public void afterCompletion(int status) {
            PENDING.remove();
            if (status == STATUS_COMMITTED) {
                pending.deltas.keySet().forEach(BulkJobStore.this::publishProgressAfterCommit);
            }
        }
    }

    private void publishProgressAfterCommit(String jobId) {
        // afterCompletion runs while the finished transaction's connection may still be bound: use an isolated tx
        try {
            requiresNew.executeWithoutResult(s -> publishProgress(jobId));
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    /**
     * After a chunk rollback: removes the IN_PROGRESS idempotency claims of the rolled-back rows (their messages do
     * not exist), so the retry can claim the same keys again. COMPLETED records are never touched.
     */
    public void releaseIdempotencyClaims(String tenantId, List<String> keys) {
        try {
            requiresNew.executeWithoutResult(s -> {
                for (String k : keys) {
                    jdbc.update("delete from idempotency_record where tenant_id = ? and idem_key = ? "
                            + "and status = 'IN_PROGRESS'", tenantId, k);
                }
            });
        } catch (RuntimeException e) {
            // the claim TTL (messaging.idempotency.claim-ttl) frees it eventually
        }
    }

    // ------------------------------------------------------------------ status

    /** Outside any chunk transaction: job lifecycle writes are small autocommit statements. */
    public void markRunning(String jobId, Long executionId) {
        jdbc.update("update bulk_job set status = ?, started_at = coalesce(started_at, ?), finished_at = null, "
                + "spring_batch_execution_id = coalesce(?, spring_batch_execution_id), updated_at = ? where id = ?",
                BulkJobStatus.RUNNING.name(), now(), executionId, now(), jobId);
    }

    public void setExecutionId(String jobId, long executionId) {
        jdbc.update("update bulk_job set spring_batch_execution_id = ?, updated_at = ? where id = ?",
                executionId, now(), jobId);
    }

    public void finish(String jobId, BulkJobStatus status) {
        jdbc.update("update bulk_job set status = ?, finished_at = ?, updated_at = ? where id = ?",
                status.name(), now(), now(), jobId);
        publishProgress(jobId);
    }

    /** Compare-and-set on status (used by sweeper and stop). Returns true when this caller changed the row. */
    public boolean casStatus(String jobId, BulkJobStatus from, BulkJobStatus to) {
        return jdbc.update("update bulk_job set status = ?, updated_at = ? where id = ? and status = ?",
                to.name(), now(), jobId, from.name()) == 1;
    }

    public void markFinishedIfOpen(String jobId, BulkJobStatus to) {
        jdbc.update("update bulk_job set status = ?, finished_at = ?, updated_at = ? where id = ? "
                + "and status in ('CREATED','RUNNING')", to.name(), now(), now(), jobId);
    }

    public long[] counters(String jobId) {
        return jdbc.query("select total, processed, succeeded, failed, duplicate from bulk_job where id = ?",
                rs -> rs.next() ? new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)}
                        : new long[5], jobId);
    }
}
