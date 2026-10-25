package com.example.messaging.scheduler;

import com.example.messaging.batch.BulkUploadStorage;
import com.example.messaging.config.MessagingProperties;
import com.example.messaging.metrics.MessagingMetrics;
import com.example.messaging.service.AuditService;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scheduled, batched purge (docs/database/retention-and-archival.md).
 *
 * <ul>
 *   <li><b>message_request</b> (+ delivery_event, message_attempt, outbox rows) older than
 *       {@code message-days}, only in a terminal state: DELIVERED, REJECTED, CANCELLED, FAILED, or DEAD_LETTERED
 *       and discarded. RETRY_PENDING, DELIVERY_UNKNOWN, QUEUED, PROCESSING, ... are never purged, however old (they are
 *       alerted on instead). Children are deleted before the parent.</li>
 *   <li><b>delivery_event</b> older than {@code event-days} whose message is terminal.</li>
 *   <li><b>audit_event</b> older than {@code audit-days}, except rows under a legal hold.</li>
 *   <li>expired <b>idempotency_record</b>, old <b>processed_message</b>, SENT <b>outbox_event</b>,
 *       <b>replay_guard</b>, and the ledger/failure rows and upload files of old bulk jobs.</li>
 * </ul>
 * Legal hold ({@code legal_hold} table, active while {@code released_at IS NULL}): scope TENANT protects every
 * message and audit row of the tenant, scope MESSAGE protects one message and its audit trail.
 *
 * <p>Safety: every table is purged in small transactions ({@code batch-size} ids selected with
 * {@code FETCH FIRST n ROWS ONLY}, then deleted by primary key), with a pause between batches. Re-running is harmless
 * (idempotent), two instances running at once only delete disjoint or already-deleted rows. {@code dry-run} counts
 * instead of deleting. Each run emits counters ({@code messaging.retention.deleted{table}}), a timer
 * ({@code messaging.retention.run}) and one {@code RETENTION_PURGE} audit event holding counts only.
 */
@Component
@EnableConfigurationProperties(RetentionProperties.class)
public class RetentionJob {

    public static final String AUDIT_ACTION = "RETENTION_PURGE";

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private static final String TERMINAL = "(m.status in ('DELIVERED','REJECTED','CANCELLED','FAILED') "
            + "or (m.status = 'DEAD_LETTERED' and m.discarded = true))";
    private static final String NOT_HELD = "not exists (select 1 from legal_hold h where h.released_at is null "
            + "and h.tenant_id = m.tenant_id and (h.scope = 'TENANT' or (h.scope = 'MESSAGE' and h.entity_id = m.id)))";

    /** Per-table outcome of one run: rows deleted, or rows that would be deleted in dry-run mode. */
    public record Report(boolean dryRun, Map<String, Long> counts, Duration took) {
        public long total() {
            return counts.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MessagingProperties props;
    private final RetentionProperties retention;
    private final MessagingMetrics metrics;
    private final AuditService audit;
    private final BulkUploadStorage uploads;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();

    public RetentionJob(JdbcTemplate jdbc, PlatformTransactionManager tm, MessagingProperties props,
            RetentionProperties retention, MessagingMetrics metrics, AuditService audit, BulkUploadStorage uploads,
            Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.props = props;
        this.retention = retention;
        this.metrics = metrics;
        this.audit = audit;
        this.uploads = uploads;
        this.clock = clock;
    }

    @Scheduled(cron = "${messaging.retention.cron:0 30 2 * * *}")
    public void scheduled() {
        if (!retention.enabled()) {
            return;
        }
        try {
            run();
        } catch (RuntimeException e) {
            log.error("retention run failed: {}", e.getClass().getSimpleName());
        }
    }

    public Report run() {
        return run(retention.dryRun());
    }

    /** Runs one purge pass. A concurrent call in the same JVM returns an empty report instead of overlapping. */
    public Report run(boolean dryRun) {
        if (!running.compareAndSet(false, true)) {
            return new Report(dryRun, Map.of(), Duration.ZERO);
        }
        long started = System.nanoTime();
        Map<String, Long> counts = new LinkedHashMap<>();
        try {
            Instant now = clock.instant();
            LocalDateTime messageCutoff = utc(now.minus(Duration.ofDays(props.retention().messageDays())));
            LocalDateTime eventCutoff = utc(now.minus(Duration.ofDays(props.retention().eventDays())));
            LocalDateTime auditCutoff = utc(now.minus(Duration.ofDays(props.retention().auditDays())));

            counts.put("message_request", purgeMessages(messageCutoff, dryRun));
            counts.put("delivery_event", purgeSimple("delivery_event", "t.id", "delivery_event t join message_request m "
                    + "on m.id = t.message_id", "t.received_at < ? and " + TERMINAL + " and " + NOT_HELD, dryRun,
                    eventCutoff));
            counts.put("audit_event", purgeSimple("audit_event", "t.id", "audit_event t",
                    "t.occurred_at < ? and not exists (select 1 from legal_hold h where h.released_at is null "
                            + "and h.tenant_id = t.tenant_id and (h.scope = 'TENANT' or (h.scope = 'MESSAGE' "
                            + "and t.entity_type = 'MESSAGE' and h.entity_id = t.entity_id)))", dryRun, auditCutoff));
            counts.put("idempotency_record", purgeSimple("idempotency_record", "t.id", "idempotency_record t",
                    "(t.status = 'COMPLETED' and t.expires_at < ?) or (t.status <> 'COMPLETED' and t.expires_at < ?)",
                    dryRun, utc(now), utc(now.minus(Duration.ofDays(1)))));
            counts.put("processed_message", purgeSimple("processed_message", "t.id", "processed_message t",
                    "t.processed_at < ?", dryRun, utc(now.minus(Duration.ofDays(retention.processedMessageDays())))));
            counts.put("outbox_event", purgeSimple("outbox_event", "t.id", "outbox_event t",
                    "t.status = 'SENT' and t.sent_at < ?", dryRun,
                    utc(now.minus(Duration.ofDays(retention.outboxSentDays())))));
            counts.put("replay_guard", purgeSimple("replay_guard", "t.event_id", "replay_guard t",
                    "t.seen_at < ?", dryRun, utc(now.minus(Duration.ofHours(retention.replayGuardHours())))));
            counts.put("bulk_job_row", purgeBulkRows(messageCutoff, dryRun));
            counts.put("bulk_job_failure", purgeBulkFailures(messageCutoff, dryRun));
            if (retention.purgeBulkFiles()) {
                counts.put("bulk_upload_file", purgeBulkFiles(now.minus(Duration.ofDays(props.retention().messageDays())),
                        dryRun));
            }
        } finally {
            running.set(false);
        }
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        report(dryRun, counts, took);
        return new Report(dryRun, counts, took);
    }

    private void report(boolean dryRun, Map<String, Long> counts, Duration took) {
        Timer.builder("messaging.retention.run").tag("dryRun", Boolean.toString(dryRun)).register(metrics.registry())
                .record(took);
        if (!dryRun) {
            counts.forEach((table, n) -> {
                if (n > 0) {
                    metrics.registry().counter("messaging.retention.deleted", "table", table).increment(n);
                }
            });
        }
        Map<String, Object> detail = new LinkedHashMap<>(counts);
        detail.put("dryRun", dryRun);
        detail.put("tookMs", took.toMillis());
        try {
            audit.recordAs("system", "SYSTEM", null, AUDIT_ACTION, "RETENTION", "run", detail);
        } catch (RuntimeException e) {
            log.warn("retention audit event could not be written: {}", e.getClass().getSimpleName());
        }
        log.info("retention {} finished in {} ms: {}", dryRun ? "dry-run" : "purge", took.toMillis(), counts);
    }

    private static LocalDateTime utc(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ messages

    private long purgeMessages(LocalDateTime cutoff, boolean dryRun) {
        String where = "m.created_at < ? and m.updated_at < ? and " + TERMINAL + " and " + NOT_HELD;
        if (dryRun) {
            return count("message_request m", where, cutoff, cutoff);
        }
        int batch = retention.effectiveBatchSize();
        long deleted = 0;
        for (int i = 0; i < retention.maxBatchesPerTable(); i++) {
            List<String> ids = jdbc.queryForList("select m.id from message_request m where " + where
                    + " order by m.created_at fetch first " + batch + " rows only", String.class, cutoff, cutoff);
            if (ids.isEmpty()) {
                break;
            }
            String in = placeholders(ids.size());
            Object[] args = ids.toArray();
            Integer n = tx.execute(s -> {
                jdbc.update("delete from delivery_event where message_id in (" + in + ")", args);
                jdbc.update("delete from message_attempt where message_id in (" + in + ")", args);
                jdbc.update("delete from outbox_event where aggregate_id in (" + in + ")", args);
                return jdbc.update("delete from message_request where id in (" + in + ")", args);
            });
            deleted += n == null ? 0 : n;
            if (ids.size() < batch) {
                break;
            }
            pause();
        }
        return deleted;
    }

    // ------------------------------------------------------------------ generic tables

    /**
     * @param from  FROM clause (may join); {@code idExpr} selects the primary key of the table being purged
     * @param where predicate using {@code ?} placeholders for {@code params}
     */
    private long purgeSimple(String table, String idExpr, String from, String where, boolean dryRun,
            Object... params) {
        String idColumn = idExpr.substring(idExpr.indexOf('.') + 1);
        if (dryRun) {
            return count(from, where, params);
        }
        int batch = retention.effectiveBatchSize();
        long deleted = 0;
        for (int i = 0; i < retention.maxBatchesPerTable(); i++) {
            List<String> ids = jdbc.queryForList("select " + idExpr + " from " + from + " where " + where
                    + " fetch first " + batch + " rows only", String.class, params);
            if (ids.isEmpty()) {
                break;
            }
            String sql = "delete from " + table + " where " + idColumn + " in (" + placeholders(ids.size()) + ")";
            Integer n = tx.execute(s -> jdbc.update(sql, ids.toArray()));
            deleted += n == null ? 0 : n;
            if (ids.size() < batch) {
                break;
            }
            pause();
        }
        return deleted;
    }

    private long count(String from, String where, Object... params) {
        Long n = jdbc.queryForObject("select count(*) from " + from + " where " + where, Long.class, params);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ bulk jobs

    private static final String OLD_JOBS = "select id from bulk_job where finished_at < ? "
            + "and status in ('COMPLETED','COMPLETED_WITH_ERRORS','FAILED','STOPPED','ABANDONED')";

    private long purgeBulkRows(LocalDateTime cutoff, boolean dryRun) {
        return purgeByJob("bulk_job_row", "external_ref", cutoff, dryRun);
    }

    private long purgeBulkFailures(LocalDateTime cutoff, boolean dryRun) {
        return purgeByJob("bulk_job_failure", "id", cutoff, dryRun);
    }

    private long purgeByJob(String table, String keyColumn, LocalDateTime cutoff, boolean dryRun) {
        long deleted = 0;
        int batch = retention.effectiveBatchSize();
        List<String> jobs = jdbc.queryForList(OLD_JOBS, String.class, cutoff);
        for (String jobId : jobs) {
            if (dryRun) {
                Long n = jdbc.queryForObject("select count(*) from " + table + " where job_id = ?", Long.class, jobId);
                deleted += n == null ? 0 : n;
                continue;
            }
            for (int i = 0; i < retention.maxBatchesPerTable(); i++) {
                List<String> keys = jdbc.queryForList("select " + keyColumn + " from " + table + " where job_id = ? "
                        + "fetch first " + batch + " rows only", String.class, jobId);
                if (keys.isEmpty()) {
                    break;
                }
                String sql = "delete from " + table + " where job_id = ? and " + keyColumn + " in ("
                        + placeholders(keys.size()) + ")";
                Object[] args = new Object[keys.size() + 1];
                args[0] = jobId;
                for (int k = 0; k < keys.size(); k++) {
                    args[k + 1] = keys.get(k);
                }
                Integer n = tx.execute(s -> jdbc.update(sql, args));
                deleted += n == null ? 0 : n;
                if (keys.size() < batch) {
                    break;
                }
                pause();
            }
        }
        return deleted;
    }

    private long purgeBulkFiles(Instant olderThan, boolean dryRun) {
        Path base = uploads.baseDir();
        if (!Files.isDirectory(base)) {
            return 0;
        }
        List<Path> old = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base, 2)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".csv")).filter(Files::isRegularFile).forEach(p -> {
                try {
                    if (Files.getLastModifiedTime(p).toInstant().isBefore(olderThan)) {
                        old.add(p);
                    }
                } catch (IOException ignored) {
                    // unreadable: leave it
                }
            });
        } catch (IOException e) {
            log.warn("bulk upload directory could not be scanned: {}", e.getClass().getSimpleName());
            return 0;
        }
        if (dryRun) {
            return old.size();
        }
        long deleted = 0;
        for (Path p : old) {
            try {
                if (Files.deleteIfExists(uploads.requireInsideUploadDir(p))) {
                    deleted++;
                }
            } catch (IOException ignored) {
                // retried on the next run
            }
        }
        return deleted;
    }

    // ------------------------------------------------------------------ helpers

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder(n * 3);
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "?" : ",?");
        }
        return sb.toString();
    }

    private void pause() {
        long ms = retention.pauseMs();
        if (ms > 0) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
