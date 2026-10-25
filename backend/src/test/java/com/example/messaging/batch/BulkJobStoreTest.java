package com.example.messaging.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.messaging.domain.BulkJobStatus;
import com.example.messaging.metrics.MessagingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The ledger, failure records and counters, against a real schema. */
class BulkJobStoreTest {

    static final String TENANT = "tenant-a";
    static final String JOB = "job-1";

    H2Support db;
    BulkJobStore store;
    TransactionTemplate tx;
    SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        db = new H2Support();
        db.tenant(TENANT);
        LocalDateTime now = LocalDateTime.now();
        db.jdbc.update("insert into bulk_job (id, tenant_id, name, status, total, created_at, updated_at, version) "
                + "values (?, ?, 'j', 'RUNNING', 100, ?, ?, 0)", JOB, TENANT, now, now);
        var tm = new DataSourceTransactionManager(db.dataSource);
        registry = new SimpleMeterRegistry();
        store = new BulkJobStore(db.jdbc, Clock.systemUTC(), new MessagingMetrics(registry), tm);
        tx = new TransactionTemplate(tm);
    }

    long[] counters() {
        return store.counters(JOB); // total, processed, succeeded, failed, duplicate
    }

    @Test
    void claimRow_distinguishesNewReplayAndDuplicate() {
        assertThat(store.claimRow(JOB, "R1", 2)).isEqualTo(new BulkJobStore.ClaimResult(BulkJobStore.Claim.NEW, 2));
        assertThat(store.claimRow(JOB, "R1", 2)).isEqualTo(new BulkJobStore.ClaimResult(BulkJobStore.Claim.SAME_LINE, 2));
        assertThat(store.claimRow(JOB, "R1", 9)).isEqualTo(new BulkJobStore.ClaimResult(BulkJobStore.Claim.DUPLICATE, 2));
        // references are per job
        db.jdbc.update("insert into bulk_job (id, tenant_id, name, status, created_at, updated_at, version) "
                + "values ('job-2', ?, 'j', 'RUNNING', ?, ?, 0)", TENANT, LocalDateTime.now(), LocalDateTime.now());
        assertThat(store.claimRow("job-2", "R1", 5).claim()).isEqualTo(BulkJobStore.Claim.NEW);
    }

    @Test
    void claimRow_isCommittedImmediately_evenIfTheCallersTransactionRollsBack() {
        tx.executeWithoutResult(s -> {
            store.claimRow(JOB, "R1", 2);
            s.setRollbackOnly();
        });
        assertThat(db.count("bulk_job_row")).isEqualTo(1);
    }

    @Test
    void claimRow_underARace_hasExactlyOneWinner() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<BulkJobStore.ClaimResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            long line = 2 + i;
            results.add(pool.submit(() -> {
                go.await();
                return store.claimRow(JOB, "SAME-REF", line);
            }));
        }
        go.countDown();
        List<BulkJobStore.ClaimResult> all = new ArrayList<>();
        for (var f : results) {
            all.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertThat(all.stream().filter(r -> r.claim() == BulkJobStore.Claim.NEW)).hasSize(1);
        assertThat(all.stream().filter(r -> r.claim() == BulkJobStore.Claim.DUPLICATE)).hasSize(threads - 1);
        long winnerLine = all.stream().filter(r -> r.claim() == BulkJobStore.Claim.NEW).findFirst().orElseThrow().firstLine();
        assertThat(all.stream().filter(r -> r.claim() == BulkJobStore.Claim.DUPLICATE)
                .map(BulkJobStore.ClaimResult::firstLine)).containsOnly(winnerLine);
        assertThat(db.count("bulk_job_row")).isEqualTo(1);
    }

    @Test
    void countersAreWrittenOncePerTransaction_atCommit() {
        tx.executeWithoutResult(s -> {
            store.addSucceeded(JOB, 7);
            store.recordFailure(JOB, 10, BulkJobStore.Outcome.FAILED, "RECIPIENT_NOT_FOUND", "h1");
            store.recordFailure(JOB, 11, BulkJobStore.Outcome.FAILED, "INVALID_LOCALE", "h2");
            // nothing is visible to others before commit
            assertThat(counters()).containsExactly(100, 0, 0, 0, 0);
        });
        assertThat(counters()).containsExactly(100, 9, 7, 2, 0);
        assertThat(db.count("bulk_job_failure")).isEqualTo(2);
        assertThat(registry.get("messaging.bulk.progress").tag("jobId", JOB).gauge().value()).isEqualTo(0.09);
    }

    @Test
    void aRolledBackChunk_leavesNoCounterAndNoFailureRecord() {
        tx.executeWithoutResult(s -> {
            store.addSucceeded(JOB, 7);
            store.recordFailure(JOB, 10, BulkJobStore.Outcome.FAILED, "X", null);
            s.setRollbackOnly();
        });
        assertThat(counters()).containsExactly(100, 0, 0, 0, 0);
        assertThat(db.count("bulk_job_failure")).isZero();
        // and the thread is clean for the next transaction
        tx.executeWithoutResult(s -> store.addSucceeded(JOB, 3));
        assertThat(counters()).containsExactly(100, 3, 3, 0, 0);
    }

    @Test
    void failureRecording_isIdempotentPerLine() {
        assertThat(inTx(() -> store.recordFailure(JOB, 10, BulkJobStore.Outcome.FAILED, "X", "h"))).isTrue();
        assertThat(inTx(() -> store.recordFailure(JOB, 10, BulkJobStore.Outcome.FAILED, "X", "h"))).isFalse();
        assertThat(counters()).containsExactly(100, 1, 0, 1, 0);
    }

    @Test
    void duplicateRecording_isIndependentOfTheChunk_andIdempotent() {
        tx.executeWithoutResult(s -> {
            assertThat(store.recordDuplicate(JOB, 22, "DUPLICATE_EXTERNAL_REF: first seen on line 6", "h")).isTrue();
            s.setRollbackOnly(); // the chunk is rolled back ...
        });
        assertThat(counters()).as("... the duplicate stays counted").containsExactly(100, 1, 0, 0, 1);
        assertThat(store.recordDuplicate(JOB, 22, "DUPLICATE_EXTERNAL_REF: first seen on line 6", "h")).isFalse();
        assertThat(counters()).containsExactly(100, 1, 0, 0, 1);
    }

    @Test
    void outsideATransaction_deltasAreAppliedImmediately() {
        store.addSucceeded(JOB, 5);
        assertThat(counters()).containsExactly(100, 5, 5, 0, 0);
        store.addSucceeded(JOB, 0);
        assertThat(counters()).containsExactly(100, 5, 5, 0, 0);
    }

    @Test
    void manyConcurrentChunks_neverLoseACounterUpdate() throws Exception {
        int threads = 8;
        int chunksEach = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            fs.add(pool.submit(() -> {
                for (int c = 0; c < chunksEach; c++) {
                    tx.executeWithoutResult(s -> store.addSucceeded(JOB, 10));
                }
            }));
        }
        for (var f : fs) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertThat(counters()).containsExactly(100, 2000, 2000, 0, 0);
    }

    @Test
    void releaseIdempotencyClaims_removesOnlyOpenClaims() {
        LocalDateTime now = LocalDateTime.now();
        db.jdbc.update("insert into idempotency_record (id, tenant_id, idem_key, request_hash, status, created_at, "
                + "expires_at) values ('a', ?, 'k-open', 'h', 'IN_PROGRESS', ?, ?), "
                + "('b', ?, 'k-done', 'h', 'COMPLETED', ?, ?)", TENANT, now, now, TENANT, now, now);
        store.releaseIdempotencyClaims(TENANT, List.of("k-open", "k-done", "k-unknown"));
        assertThat(db.jdbc.queryForList("select idem_key from idempotency_record", String.class)).containsExactly("k-done");
    }

    @Test
    void statusTransitions_areGuarded() {
        store.markRunning(JOB, 42L);
        assertThat(db.jdbc.queryForObject("select spring_batch_execution_id from bulk_job where id = ?", Long.class, JOB))
                .isEqualTo(42L);
        assertThat(store.casStatus(JOB, BulkJobStatus.CREATED, BulkJobStatus.FAILED)).isFalse(); // it is RUNNING
        assertThat(store.casStatus(JOB, BulkJobStatus.RUNNING, BulkJobStatus.STOPPED)).isTrue();
        store.markFinishedIfOpen(JOB, BulkJobStatus.COMPLETED); // STOPPED is not open: untouched
        assertThat(status()).isEqualTo("STOPPED");
        store.casStatus(JOB, BulkJobStatus.STOPPED, BulkJobStatus.RUNNING);
        store.markFinishedIfOpen(JOB, BulkJobStatus.ABANDONED);
        assertThat(status()).isEqualTo("ABANDONED");
        assertThat(db.jdbc.queryForObject("select finished_at from bulk_job where id = ?", Object.class, JOB)).isNotNull();
    }

    private String status() {
        return db.jdbc.queryForObject("select status from bulk_job where id = ?", String.class, JOB);
    }

    private <T> T inTx(java.util.function.Supplier<T> s) {
        return tx.execute(st -> s.get());
    }
}
