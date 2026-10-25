package com.example.messaging.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.messaging.common.Ids;
import com.example.messaging.service.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The batch job end to end through the real API, security chain, JDBC job repository and H2: happy path,
 * validation failures (reported, never echoed), duplicates, skip limit, retry of transient errors, bounded
 * concurrency, rate limit. Chunk size is 10, skip limit 5, concurrency 3.
 */
@TestPropertySource(properties = {
        "messaging.bulk.concurrency=3",
        "messaging.bulk.chunk-size=10",
        "messaging.bulk.skip-limit=5",
        "messaging.bulk.max-rows=500",
        "messaging.bulk.upload-dir=target/bulk-it",
        "messaging.bulk.max-upload-bytes=65536",
        "messaging.bulk.sweeper-enabled=false"})
class BulkJobBatchIT extends AbstractBulkIT {

    @MockitoSpyBean
    NotificationService notifications;
    @Autowired
    MeterRegistry registry;

    @BeforeEach
    void recipients() {
        provisioner.ensure(RETAIL, 300);
    }

    @Test
    void happyPath_everyRowBecomesExactlyOneMessage() throws Exception {
        String auth = bulkSender(RETAIL);
        String id = uploadOk(auth, csv(okRows(1, 35)));

        assertThat(awaitTerminal(id)).isEqualTo("COMPLETED");
        long[] c = counters(id);
        assertThat(c).containsExactly(35, 35, 35, 0, 0); // total, processed, succeeded, failed, duplicate
        assertThat(messageCount(id)).isEqualTo(35);
        assertThat(distinctKeyCount(id)).isEqualTo(35);
        assertThat(openClaims(id)).isZero();

        JsonNode job = getJob(auth, id);
        assertThat(job.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(job.get("startedAt").isNull()).isFalse();
        assertThat(job.get("finishedAt").isNull()).isFalse();
        assertThat(job.get("createdBy").asText()).isEqualTo("bulk-sender-" + RETAIL);
        // messages carry the job id, the deterministic key, a pinned template and the bulk metadata
        assertThat(jdbc.queryForObject("select count(*) from message_request where bulk_job_id = ? and idempotency_key = "
                + "concat('bulk-', bulk_job_id, '-', substr(client_reference, " + ("bulk:".length() + 36 + 2) + "))",
                Long.class, id)).isEqualTo(35);
        assertThat(jdbc.queryForObject("select count(*) from audit_event where entity_type = 'BULK_JOB' and entity_id = ? "
                + "and action = 'BULK_JOB_CREATED'", Long.class, id)).isEqualTo(1);
        // progress gauge reached 100 %
        assertThat(registry.get("messaging.bulk.progress").tag("jobId", id).gauge().value()).isEqualTo(1.0);
    }

    @Test
    void invalidRows_areReportedWithReasonAndHash_neverWithContent() throws Exception {
        String auth = bulkSender(RETAIL);
        String formula = row("SYN-0000004", "en", "1234", "=1+1", "buy", "10", "ACME", "12.50");
        List<String> lines = new ArrayList<>(okRows(1, 3));
        lines.add(formula);                                                       // line 5
        lines.add(row("", "en", "1234", "Pat", "buy", "10", "ACME", "12.50"));      // line 6 missing ref
        lines.add(row("NOPE-77", "en", "1234", "Pat", "buy", "10", "ACME", "12.50")); // line 7 unknown recipient
        lines.add("SYN-0000005,en,1234");                                         // line 8 wrong column count
        lines.add(row("SYN-0000006", "x", "1234", "Pat", "buy", "10", "ACME", "12.50")); // line 9 bad locale
        lines.addAll(okRows(7, 9));
        String id = uploadOk(auth, csv(lines));

        assertThat(awaitTerminal(id)).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(counters(id)).containsExactly(11, 11, 6, 5, 0);
        assertThat(messageCount(id)).isEqualTo(6);

        String body = mvc.perform(get("/api/v1/bulk-jobs/" + id + "/failures").header("Authorization", auth))
                .andReturn().getResponse().getContentAsString();
        JsonNode page = om.readTree(body);
        assertThat(page.get("totalElements").asInt()).isEqualTo(5);
        List<String> reasons = new ArrayList<>();
        List<Long> lineNos = new ArrayList<>();
        page.get("content").forEach(f -> {
            reasons.add(f.get("reason").asText());
            lineNos.add(f.get("lineNo").asLong());
            assertThat(f.get("rawLineHash").asText()).matches("[0-9a-f]{64}");
        });
        assertThat(lineNos).containsExactly(5L, 6L, 7L, 8L, 9L);
        assertThat(reasons.get(0)).startsWith("FORMULA_INJECTION");
        assertThat(reasons.get(1)).startsWith("MISSING_EXTERNAL_REF");
        assertThat(reasons.get(2)).startsWith("RECIPIENT_NOT_FOUND");
        assertThat(reasons.get(3)).startsWith("WRONG_COLUMN_COUNT");
        assertThat(reasons.get(4)).startsWith("INVALID_LOCALE");
        // the stored hash is the SHA-256 of the raw line; the line itself appears nowhere in the API output
        assertThat(page.get("content").get(0).get("rawLineHash").asText()).isEqualTo(Ids.sha256Hex(formula));
        assertThat(body).doesNotContain("=1+1").doesNotContain("NOPE-77").doesNotContain("Pat")
                .doesNotContain("example.test");
        assertThat(jdbc.queryForList("select reason from bulk_job_failure where job_id = ?", String.class, id))
                .noneMatch(r -> r.contains("NOPE-77") || r.contains("=1+1"));
    }

    @Test
    void duplicatesInsideTheFile_areDetectedAcrossChunksAndThreads() throws Exception {
        String auth = bulkSender(RETAIL);
        List<String> lines = new ArrayList<>(okRows(1, 25));
        lines.add(ok(3));   // far away from its first occurrence: another chunk, possibly another thread
        lines.add(ok(15));
        lines.add(ok(15));  // twice more
        String id = uploadOk(auth, csv(lines));

        assertThat(awaitTerminal(id)).isEqualTo("COMPLETED");
        // exactly one of each reference is sent whichever occurrence won the race (concurrency 3)
        assertThat(counters(id)).containsExactly(28, 28, 25, 0, 3);
        assertThat(messageCount(id)).isEqualTo(25);
        assertThat(distinctKeyCount(id)).isEqualTo(25);
        assertThat(failureCount(id, "DUPLICATE_EXTERNAL_REF: first seen on line")).isEqualTo(3);
    }

    @Test
    void skipLimitExceeded_failsTheJobAndExplainsWhy() throws Exception {
        String auth = bulkSender(RETAIL);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            lines.add(row("NOPE-" + i, "en", "1234", "Pat", "buy", "10", "ACME", "12.50"));
        }
        lines.addAll(okRows(1, 30));
        String id = uploadOk(auth, csv(lines));

        assertThat(awaitTerminal(id)).isEqualTo("FAILED");
        assertThat(failureCount(id, "SKIP_LIMIT_EXCEEDED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select line_no from bulk_job_failure where job_id = ? and reason like 'SKIP_LIMIT%'",
                Long.class, id)).isZero();
        assertThat(counters(id)[3]).isLessThanOrEqualTo(8); // never more failures than bad rows
        assertThat(openClaims(id)).isZero();
        // whatever was committed is consistent with the counters (chunk atomicity)
        assertThat(messageCount(id)).isEqualTo(counters(id)[2]);
    }

    @Test
    void transientDatabaseError_isRetriedAndNothingIsSentTwice() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> {
            if (calls.incrementAndGet() == 5) { // mid-chunk: four messages of this chunk are already in the transaction
                throw new TransientDataAccessResourceException("simulated deadlock");
            }
            return inv.callRealMethod();
        }).when(notifications).submitInternal(any(), any(), any(), any(), any());

        String auth = bulkSender(RETAIL);
        String id = uploadOk(auth, csv(okRows(1, 30)));

        assertThat(awaitTerminal(id)).isEqualTo("COMPLETED");
        assertThat(counters(id)).containsExactly(30, 30, 30, 0, 0);
        assertThat(messageCount(id)).isEqualTo(30);
        assertThat(distinctKeyCount(id)).isEqualTo(30);
        assertThat(openClaims(id)).isZero();
        assertThat(calls.get()).isGreaterThan(30); // the failed chunk really was attempted again
    }

    @Test
    void concurrentWriters_neverExceedConfiguredConcurrency() throws Exception {
        doAnswer(inv -> {
            Thread.sleep(4); // keep every chunk busy long enough for the workers to overlap
            return inv.callRealMethod();
        }).when(notifications).submitInternal(any(), any(), any(), any(), any());
        writerStats.reset();

        String id = uploadOk(bulkSender(RETAIL), csv(okRows(1, 200)));

        assertThat(awaitTerminal(id)).isEqualTo("COMPLETED");
        assertThat(writerStats.maxObserved()).isBetween(2, 3); // messaging.bulk.concurrency = 3
        assertThat(counters(id)).containsExactly(200, 200, 200, 0, 0);
        assertThat(distinctKeyCount(id)).isEqualTo(200);
    }

    @Test
    void writerRateLimit_pacesTheJob() throws Exception {
        long t0 = System.nanoTime();
        String id = uploadOk(bulkSender(RETAIL), csv(okRows(1, 60)), p -> p.put("ratePerSecond", "50"));

        assertThat(awaitTerminal(id, Duration.ofSeconds(30))).isEqualTo("COMPLETED");
        long elapsedMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        // 60 permits at 50/s with a 5-permit burst: about 1.1 s. Allow generous slack below, none needed above.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(900);
        assertThat(counters(id)[2]).isEqualTo(60);
        assertThat(jdbc.queryForObject("select rate_limit_per_sec from bulk_job where id = ?", Integer.class, id))
                .isEqualTo(50);
    }

    @Test
    void policyRejectedRecipient_isCountedAsFailedNotSucceeded() throws Exception {
        // CUST-001's e-mail address is on the seeded suppression list (hard bounce): REJECTED at submission
        String auth = bulkSender(RETAIL);
        List<String> hashes = jdbc.queryForList("select address_hash from suppression where tenant_id = ? "
                + "and channel = 'EMAIL'", String.class, RETAIL);
        String suppressedRef = jdbc.queryForList("select external_ref, email from recipient where tenant_id = ? "
                + "and external_ref like 'CUST-%'", RETAIL).stream()
                .filter(r -> hashes.contains(Ids.addressHash((String) r.get("email"))))
                .map(r -> (String) r.get("external_ref")).findFirst().orElseThrow();
        List<String> lines = new ArrayList<>(okRows(1, 4));
        lines.add(row(suppressedRef, "en", "1234", "Pat", "buy", "10", "ACME", "12.50"));
        String id = uploadOk(auth, csv(lines));

        assertThat(awaitTerminal(id)).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(counters(id)).containsExactly(5, 5, 4, 1, 0);
        assertThat(failureCount(id, "POLICY_REJECTED: SUPPRESSED")).isEqualTo(1);
        // the REJECTED message exists (audit trail) but was never queued
        assertThat(jdbc.queryForObject("select count(*) from message_request where bulk_job_id = ? "
                + "and status = 'REJECTED'", Long.class, id)).isEqualTo(1);
    }
}
