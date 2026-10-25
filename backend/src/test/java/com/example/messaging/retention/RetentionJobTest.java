package com.example.messaging.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.messaging.TestProps;
import com.example.messaging.batch.BulkProperties;
import com.example.messaging.batch.BulkUploadStorage;
import com.example.messaging.batch.H2Support;
import com.example.messaging.config.MessagingProperties;
import com.example.messaging.metrics.MessagingMetrics;
import com.example.messaging.scheduler.RetentionJob;
import com.example.messaging.scheduler.RetentionProperties;
import com.example.messaging.service.AuditService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * RetentionJob against a real (H2, PostgreSQL mode) schema built by the project's Flyway migrations: terminal-state
 * rule, age cut-offs, children before parents, legal hold, dry run, batching, idempotence, other housekeeping tables.
 */
class RetentionJobTest {

    static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    static final String T1 = "tenant-a";
    static final String T2 = "tenant-b";

    H2Support db;
    AuditService audit;
    SimpleMeterRegistry registry;
    CountingTransactionManager tm;
    @TempDir
    Path uploads;

    @BeforeEach
    void setUp() {
        db = new H2Support();
        db.tenant(T1);
        db.tenant(T2);
        audit = mock(AuditService.class);
        registry = new SimpleMeterRegistry();
        tm = new CountingTransactionManager(db.dataSource);
    }

    RetentionJob job(int batchSize, int maxBatches) {
        MessagingProperties base = TestProps.defaults();
        MessagingProperties props = new MessagingProperties(base.dispatch(), base.backpressure(), base.bus(),
                base.coordination(), base.providers(), base.callback(), base.api(),
                new MessagingProperties.Bulk(4, 500, 100, 100000, uploads.toString()),
                new MessagingProperties.Retention(90, 365, 30), base.security(), base.templates(), base.idempotency());
        BulkProperties bulk = new BulkProperties(1 << 20, 8192, 51, 0, 1000, 3, 1000, 2, 20, Duration.ofMinutes(30),
                Duration.ofSeconds(60), Duration.ofSeconds(10), false, true);
        RetentionProperties rp = new RetentionProperties(true, false, batchSize, 0, maxBatches, 7, 7, 24, true,
                "0 30 2 * * *");
        return new RetentionJob(db.jdbc, tm, props, rp, new MessagingMetrics(registry), audit,
                new BulkUploadStorage(props, bulk), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static LocalDateTime daysAgo(double days) {
        return H2Support.utc(NOW.minus(Duration.ofMinutes((long) (days * 24 * 60))));
    }

    String status(String id) {
        List<String> l = db.jdbc.queryForList("select status from message_request where id = ?", String.class, id);
        return l.isEmpty() ? null : l.get(0);
    }

    // ------------------------------------------------------------------ messages

    @Test
    void purgesOnlyOldTerminalMessages_andTheirChildren() {
        String[] purge = {"DELIVERED", "REJECTED", "CANCELLED", "FAILED"};
        for (String s : purge) {
            db.message("old-" + s, T1, s, daysAgo(100), false, null);
            db.attempt("old-" + s, daysAgo(100));
            db.event("ev-" + s, "old-" + s, daysAgo(100));
            db.outbox("ob-" + s, "old-" + s, "NEW", daysAgo(100), null);
        }
        db.message("old-DLQ-discarded", T1, "DEAD_LETTERED", daysAgo(100), true, null);
        // must survive: not terminal (however old), dead-lettered awaiting an operator, or not old enough
        for (String s : List.of("RETRY_PENDING", "DELIVERY_UNKNOWN", "QUEUED", "PROCESSING", "SENT_TO_PROVIDER",
                "VALIDATED", "ACCEPTED")) {
            db.message("keep-" + s, T1, s, daysAgo(400), false, null);
        }
        db.message("keep-DLQ", T1, "DEAD_LETTERED", daysAgo(400), false, null);
        db.message("recent", T1, "DELIVERED", daysAgo(10), false, null);
        db.message("edge-89", T1, "DELIVERED", daysAgo(89), false, null);
        db.message("edge-91", T1, "DELIVERED", daysAgo(91), false, null);
        db.message("old-but-just-updated", T1, "DELIVERED", daysAgo(100), false, null);
        db.jdbc.update("update message_request set updated_at = ? where id = 'old-but-just-updated'", daysAgo(1));

        RetentionJob.Report report = job(500, 1000).run(false);

        for (String s : purge) {
            assertThat(status("old-" + s)).as("old " + s).isNull();
        }
        assertThat(status("old-DLQ-discarded")).isNull();
        assertThat(status("edge-91")).isNull();
        for (String id : List.of("keep-RETRY_PENDING", "keep-DELIVERY_UNKNOWN", "keep-QUEUED", "keep-PROCESSING",
                "keep-SENT_TO_PROVIDER", "keep-VALIDATED", "keep-ACCEPTED", "keep-DLQ", "recent", "edge-89",
                "old-but-just-updated")) {
            assertThat(status(id)).as(id).isNotNull();
        }
        assertThat(report.counts().get("message_request")).isEqualTo(6); // 4 + discarded DLQ + edge-91
        // children of purged messages are gone (a foreign key would have blocked the parent delete otherwise)
        assertThat(db.count("message_attempt")).isZero();
        assertThat(db.count("delivery_event")).isZero();
        assertThat(db.count("outbox_event")).isZero();
        assertThat(registry.get("messaging.retention.deleted").tag("table", "message_request").counter().count())
                .isEqualTo(6.0);
        assertThat(registry.get("messaging.retention.run").tag("dryRun", "false").timer().count()).isEqualTo(1);
    }

    @Test
    void isIdempotent_andResumable_underABatchBudget() {
        for (int i = 0; i < 1000; i++) {
            db.message("m" + i, T1, "DELIVERED", daysAgo(120), false, null);
        }
        // batch of 100, at most 3 batches per run: each run removes at most 300 rows
        RetentionJob j = job(100, 3);
        assertThat(j.run(false).counts().get("message_request")).isEqualTo(300);
        assertThat(db.count("message_request")).isEqualTo(700);
        assertThat(j.run(false).counts().get("message_request")).isEqualTo(300);
        assertThat(db.count("message_request")).isEqualTo(400);
        job(100, 1000).run(false);
        assertThat(db.count("message_request")).isZero();
        // nothing left: a further run changes nothing and reports zero
        RetentionJob.Report again = job(100, 1000).run(false);
        assertThat(again.total()).isZero();
    }

    @Test
    void largeTable_isPurgedInManySmallTransactions() {
        int n = 6000;
        List<Object[]> batch = new java.util.ArrayList<>();
        LocalDateTime created = daysAgo(200);
        for (int i = 0; i < n; i++) {
            batch.add(new Object[] {"big" + i, T1, "rcp-" + T1, "DELIVERED", "k-" + i, created, created, created});
        }
        db.jdbc.batchUpdate("insert into message_request (id, tenant_id, recipient_id, channel, category, priority, "
                + "status, template_code, template_version_no, template_locale, payload_json, correlation_id, "
                + "idempotency_key, discarded, status_changed_at, created_at, updated_at, version) "
                + "values (?, ?, ?, 'EMAIL', 'TRANSACTIONAL', 'NORMAL', ?, 'T', 1, 'en', '{}', 'c', ?, false, ?, ?, ?, 0)",
                batch);
        tm.begun.set(0);

        RetentionJob.Report report = job(500, 100000).run(false);

        assertThat(report.counts().get("message_request")).isEqualTo(n);
        assertThat(db.count("message_request")).isZero();
        assertThat(tm.begun.get()).as("one transaction per batch of at most 500, not one huge delete")
                .isGreaterThanOrEqualTo(n / 500);
    }

    @Test
    void dryRun_countsButDeletesNothing() {
        db.message("old", T1, "DELIVERED", daysAgo(100), false, null);
        db.attempt("old", daysAgo(100));
        db.audit("a-old", T1, "MESSAGE", "x", daysAgo(500));
        db.jdbc.update("insert into idempotency_record (id, tenant_id, idem_key, request_hash, status, created_at, "
                + "expires_at) values ('i1', ?, 'k', 'h', 'COMPLETED', ?, ?)", T1, daysAgo(10), daysAgo(9));

        RetentionJob.Report report = job(500, 100).run(true);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.counts()).containsEntry("message_request", 1L).containsEntry("audit_event", 1L)
                .containsEntry("idempotency_record", 1L);
        assertThat(db.count("message_request")).isEqualTo(1);
        assertThat(db.count("message_attempt")).isEqualTo(1);
        assertThat(db.count("audit_event")).isEqualTo(1);
        assertThat(db.count("idempotency_record")).isEqualTo(1);
        // no deletion counters in a dry run
        assertThat(registry.find("messaging.retention.deleted").counters()).isEmpty();
    }

    // ------------------------------------------------------------------ legal hold

    @Test
    void legalHold_protectsTenantAndMessageAndItsAuditTrail() {
        db.message("a-old", T1, "DELIVERED", daysAgo(200), false, null);
        db.message("b-old", T2, "DELIVERED", daysAgo(200), false, null);
        db.message("b-held", T2, "DELIVERED", daysAgo(200), false, null);
        db.audit("au-a", T1, "MESSAGE", "a-old", daysAgo(500));
        db.audit("au-b", T2, "MESSAGE", "b-old", daysAgo(500));
        db.audit("au-held", T2, "MESSAGE", "b-held", daysAgo(500));
        db.audit("au-other-held", T2, "TEMPLATE", "b-held", daysAgo(500)); // same id, not a MESSAGE entity: not protected
        db.jdbc.update("insert into legal_hold (id, tenant_id, scope, entity_id, created_at) values ('h1', ?, 'TENANT', "
                + "null, ?)", T1, daysAgo(5));
        db.jdbc.update("insert into legal_hold (id, tenant_id, scope, entity_id, created_at) values ('h2', ?, 'MESSAGE', "
                + "'b-held', ?)", T2, daysAgo(5));
        // a released hold protects nothing
        db.message("c-old", T2, "DELIVERED", daysAgo(200), false, null);
        db.jdbc.update("insert into legal_hold (id, tenant_id, scope, entity_id, created_at, released_at) values ('h3', ?, "
                + "'MESSAGE', 'c-old', ?, ?)", T2, daysAgo(50), daysAgo(40));

        job(500, 100).run(false);

        assertThat(status("a-old")).as("tenant hold").isNotNull();
        assertThat(status("b-held")).as("message hold").isNotNull();
        assertThat(status("b-old")).isNull();
        assertThat(status("c-old")).as("released hold").isNull();
        assertThat(db.jdbc.queryForList("select id from audit_event order by id", String.class))
                .containsExactly("au-a", "au-held");
    }

    @Test
    void auditRetention_usesAuditDays_andHonoursHolds() {
        db.audit("old", T1, "MESSAGE", "m", daysAgo(400));
        db.audit("recent", T1, "MESSAGE", "m", daysAgo(300));
        db.audit("held-old", T2, "MESSAGE", "m2", daysAgo(400));
        db.jdbc.update("insert into legal_hold (id, tenant_id, scope, entity_id, created_at) values ('h', ?, 'TENANT', "
                + "null, ?)", T2, daysAgo(1));

        job(500, 100).run(false);

        assertThat(db.jdbc.queryForList("select id from audit_event where action = 'X' order by id", String.class))
                .containsExactly("held-old", "recent");
    }

    // ------------------------------------------------------------------ events

    @Test
    void deliveryEvents_followEventDays_forTerminalMessagesOnly() {
        db.message("done", T1, "DELIVERED", daysAgo(60), false, null);   // younger than message-days: message stays
        db.event("e-old", "done", daysAgo(45));                           // older than event-days (30): goes
        db.event("e-new", "done", daysAgo(5));
        db.message("unknown", T1, "DELIVERY_UNKNOWN", daysAgo(60), false, null);
        db.event("e-unknown", "unknown", daysAgo(45));                    // needed for reconciliation: stays

        RetentionJob.Report report = job(500, 100).run(false);

        assertThat(report.counts().get("delivery_event")).isEqualTo(1);
        assertThat(db.jdbc.queryForList("select id from delivery_event order by id", String.class))
                .containsExactly("e-new", "e-unknown");
        assertThat(status("done")).isNotNull();
    }

    // ------------------------------------------------------------------ housekeeping tables

    @Test
    void housekeepingTables_areTrimmedByTheirOwnHorizons() {
        // idempotency: expired COMPLETED goes; fresh stays; stale IN_PROGRESS (expired > 1 day) goes, recent stays
        idem("i-expired", "COMPLETED", daysAgo(3), daysAgo(2));
        idem("i-fresh", "COMPLETED", daysAgo(1), H2Support.utc(NOW.plus(Duration.ofHours(10))));
        idem("i-stale-claim", "IN_PROGRESS", daysAgo(5), daysAgo(3));
        idem("i-live-claim", "IN_PROGRESS", daysAgo(0.01), H2Support.utc(NOW.plusSeconds(20)));
        idem("i-recent-expired-claim", "IN_PROGRESS", daysAgo(0.2), daysAgo(0.1));
        // processed_message 7 days
        db.jdbc.update("insert into processed_message (id, dispatch_id, message_id, processed_at) values "
                + "('p1', 'd1', 'm', ?), ('p2', 'd2', 'm', ?)", daysAgo(8), daysAgo(6));
        // outbox: SENT older than 7 days goes; NEW stays regardless; recent SENT stays
        db.message("ob-agg", T1, "QUEUED", daysAgo(1), false, null);
        db.outbox("o-sent-old", "ob-agg", "SENT", daysAgo(9), daysAgo(8));
        db.outbox("o-sent-new", "ob-agg", "SENT", daysAgo(2), daysAgo(1));
        db.outbox("o-new-old", "ob-agg", "NEW", daysAgo(30), null);
        // replay_guard 24 h
        db.jdbc.update("insert into replay_guard (event_id, seen_at) values ('r-old', ?), ('r-new', ?)",
                daysAgo(2), daysAgo(0.1));

        job(500, 100).run(false);

        assertThat(db.jdbc.queryForList("select id from idempotency_record order by id", String.class))
                .containsExactly("i-fresh", "i-live-claim", "i-recent-expired-claim");
        assertThat(db.jdbc.queryForList("select id from processed_message", String.class)).containsExactly("p2");
        assertThat(db.jdbc.queryForList("select id from outbox_event order by id", String.class))
                .containsExactly("o-new-old", "o-sent-new");
        assertThat(db.jdbc.queryForList("select event_id from replay_guard", String.class)).containsExactly("r-new");
    }

    private void idem(String id, String status, LocalDateTime created, LocalDateTime expires) {
        db.jdbc.update("insert into idempotency_record (id, tenant_id, idem_key, request_hash, status, created_at, "
                + "expires_at) values (?, ?, ?, 'h', ?, ?, ?)", id, T1, "k-" + id, status, created, expires);
    }

    @Test
    void bulkJobLedgerFailuresAndFiles_ofOldFinishedJobsArePurged() throws IOException {
        insertJob("old-done", "COMPLETED", daysAgo(100));
        insertJob("old-running", "RUNNING", null);
        insertJob("recent-done", "COMPLETED", daysAgo(5));
        for (String j : List.of("old-done", "old-running", "recent-done")) {
            for (int i = 0; i < 1200; i++) {
                db.jdbc.update("insert into bulk_job_row (job_id, external_ref, line_no, created_at) values (?, ?, ?, ?)",
                        j, "R" + i, i + 2, daysAgo(100));
            }
            db.jdbc.update("insert into bulk_job_failure (id, job_id, line_no, reason, created_at) values (?, ?, 5, 'x', ?)",
                    "f-" + j, j, daysAgo(100));
        }
        Path tenantDir = Files.createDirectories(uploads.resolve(T1));
        Path oldFile = Files.writeString(tenantDir.resolve("11111111-1111-1111-1111-111111111111.csv"), "x");
        Path newFile = Files.writeString(tenantDir.resolve("22222222-2222-2222-2222-222222222222.csv"), "x");
        Path other = Files.writeString(tenantDir.resolve("notes.txt"), "x");
        Files.setLastModifiedTime(oldFile, FileTime.from(NOW.minus(Duration.ofDays(120))));
        Files.setLastModifiedTime(other, FileTime.from(NOW.minus(Duration.ofDays(120))));
        Files.setLastModifiedTime(newFile, FileTime.from(NOW.minus(Duration.ofDays(3))));

        RetentionJob.Report report = job(500, 100).run(false);

        assertThat(report.counts().get("bulk_job_row")).isEqualTo(1200);
        assertThat(db.jdbc.queryForObject("select count(*) from bulk_job_row where job_id = 'old-done'", Long.class)).isZero();
        assertThat(db.jdbc.queryForObject("select count(*) from bulk_job_row where job_id = 'old-running'", Long.class))
                .isEqualTo(1200);
        assertThat(db.jdbc.queryForObject("select count(*) from bulk_job_row where job_id = 'recent-done'", Long.class))
                .isEqualTo(1200);
        assertThat(db.jdbc.queryForList("select id from bulk_job_failure order by id", String.class))
                .containsExactly("f-old-running", "f-recent-done");
        assertThat(oldFile).doesNotExist();
        assertThat(newFile).exists();
        assertThat(other).as("only job CSV files are ever deleted").exists();
        assertThat(db.count("bulk_job")).as("job rows themselves are history and stay").isEqualTo(3);
    }

    private void insertJob(String id, String status, LocalDateTime finished) {
        LocalDateTime t = daysAgo(120);
        db.jdbc.update("insert into bulk_job (id, tenant_id, name, status, finished_at, created_at, updated_at, version) "
                + "values (?, ?, 'j', ?, ?, ?, ?, 0)", id, T1, status, finished, t, t);
    }

    // ------------------------------------------------------------------ audit trail of the run itself

    @Test
    void auditsEachRun_withCountsOnly() {
        db.message("old", T1, "DELIVERED", daysAgo(100), false, null);
        job(500, 100).run(false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit).recordAs(eq("system"), eq("SYSTEM"), eq(null), eq(RetentionJob.AUDIT_ACTION), eq("RETENTION"),
                eq("run"), detail.capture());
        assertThat(detail.getValue()).containsEntry("message_request", 1L).containsEntry("dryRun", false);
        assertThat(detail.getValue().values()).allMatch(v -> v instanceof Number || v instanceof Boolean);
    }

    /** Counts transactions started, to prove the purge works in small batches. */
    static final class CountingTransactionManager extends DataSourceTransactionManager {
        private static final long serialVersionUID = 1L;
        final AtomicInteger begun = new AtomicInteger();

        CountingTransactionManager(javax.sql.DataSource ds) {
            super(ds);
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begun.incrementAndGet();
            super.doBegin(transaction, definition);
        }
    }
}
