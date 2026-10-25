package com.example.messaging.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * CONTRACT 7 / reliability scenario 9 ("bulk job interrupted and restarted"). With one chunk worker the reader
 * checkpoints after every committed chunk, so a restart resumes from the last committed chunk. Lives in its own class
 * because {@code FailureRecoveryIT} belongs to another module of this project.
 */
@TestPropertySource(properties = {
        "messaging.bulk.concurrency=1",
        "messaging.bulk.chunk-size=10",
        "messaging.bulk.skip-limit=5",
        "messaging.bulk.max-rows=500",
        "messaging.bulk.upload-dir=target/bulk-it-restart",
        "messaging.bulk.sweeper-enabled=false"})
class FailureRecoveryBulkIT extends AbstractRestartIT {

    @Test
    void scenario09_bulkJobRestart_resumesWithoutDuplicates() throws Exception {
        String auth = bulkSender(RETAIL);
        crashOnCalls = Set.of(25); // the third chunk: 4 of its messages are already in the open transaction
        String id = uploadOk(auth, scenarioFile());

        // ---- the "crash": the job fails, only whole chunks are committed
        assertThat(awaitTerminal(id)).isEqualTo("FAILED");
        assertThat(calls.get()).isGreaterThanOrEqualTo(25);
        assertThat(messageCount(id)).as("two committed chunks of ten, the half-written third chunk rolled back")
                .isEqualTo(20);
        // succeeded commits atomically with the chunk; the duplicate of line 22 was recorded independently (see
        // BulkJobStore#recordDuplicate) and is already counted, exactly once
        assertThat(counters(id)).containsExactly(60, 21, 20, 0, 1);
        assertThat(openClaims(id)).as("idempotency claims of the rolled back chunk were released").isZero();
        Map<String, Object> jobFailure = jdbc.queryForMap("select line_no, reason from bulk_job_failure where job_id = ? "
                + "and line_no = 0", id);
        assertThat(jobFailure.get("line_no")).isEqualTo(0L);
        assertThat((String) jobFailure.get("reason")).startsWith("STEP_FAILED: IllegalStateException");

        // ---- restart through the API
        crashOnCalls = Set.of();
        assertThat(restart(auth, id).getResponse().getStatus()).isEqualTo(200);
        assertThat(awaitTerminal(id)).isEqualTo("COMPLETED_WITH_ERRORS");

        assertExactlyOnce(id);
        // the job-level failure of the first attempt is gone, the row failures are the real ones
        assertThat(jdbc.queryForList("select line_no from bulk_job_failure where job_id = ? order by line_no",
                Long.class, id)).containsExactly(22L, 43L, 59L, 60L, 61L);
        assertThat(jdbc.queryForList("select reason from bulk_job_failure where job_id = ? order by line_no", String.class,
                id)).satisfiesExactly(
                        r -> assertThat(r).isEqualTo("DUPLICATE_EXTERNAL_REF: first seen on line 6"),
                        r -> assertThat(r).startsWith("RECIPIENT_NOT_FOUND"),
                        r -> assertThat(r).isEqualTo("DUPLICATE_EXTERNAL_REF: first seen on line 32"),
                        r -> assertThat(r).startsWith("WRONG_COLUMN_COUNT"),
                        r -> assertThat(r).isEqualTo("DUPLICATE_EXTERNAL_REF: first seen on line 2"));

        // resumed, not re-read: two executions of one job instance, the second read only the remaining 40 rows
        List<Long> reads = jdbc.queryForList("select se.read_count from batch_step_execution se "
                + "join batch_job_execution je on je.job_execution_id = se.job_execution_id "
                + "join batch_job_execution_params p on p.job_execution_id = je.job_execution_id "
                + "where p.parameter_name = 'bulkJobId' and p.parameter_value = ? order by je.job_execution_id",
                Long.class, id);
        assertThat(reads).hasSize(2);
        assertThat(reads.get(1)).as("rows read by the restarted execution").isEqualTo(40L);
        assertThat(jdbc.queryForObject("select count(*) from audit_event where entity_id = ? "
                + "and action = 'BULK_JOB_RESTARTED'", Long.class, id)).isEqualTo(1);
    }

    @Test
    void uninterruptedRun_reconcilesEveryRow() throws Exception {
        String id = uploadOk(bulkSender(RETAIL), scenarioFile());
        assertThat(awaitTerminal(id)).isEqualTo("COMPLETED_WITH_ERRORS");
        assertExactlyOnce(id);
    }

    @Test
    void interruptedTwice_stillExactlyOncePerRow() throws Exception {
        String auth = bulkSender(RETAIL);
        crashOnCalls = Set.of(12, 33); // first run dies in chunk 2, the restart dies again later
        String id = uploadOk(auth, scenarioFile());

        assertThat(awaitTerminal(id)).isEqualTo("FAILED");
        assertThat(restart(auth, id).getResponse().getStatus()).isEqualTo(200);
        assertThat(awaitTerminal(id)).isEqualTo("FAILED");
        long afterSecondCrash = messageCount(id);
        assertThat(afterSecondCrash).isLessThan(VALID_UNIQUE);
        assertThat(counters(id)[2]).isEqualTo(afterSecondCrash);

        assertThat(restart(auth, id).getResponse().getStatus()).isEqualTo(200);
        assertThat(awaitTerminal(id)).isEqualTo("COMPLETED_WITH_ERRORS");
        assertExactlyOnce(id);
    }

    @Test
    void restartOfAFailedJobWhoseFileWasRemoved_isRefused() throws Exception {
        String auth = bulkSender(RETAIL);
        crashOnCalls = Set.of(3);
        String id = uploadOk(auth, scenarioFile());
        assertThat(awaitTerminal(id)).isEqualTo("FAILED");
        java.nio.file.Files.delete(java.nio.file.Path.of("target/bulk-it-restart").toAbsolutePath()
                .resolve(RETAIL).resolve(id + ".csv"));
        assertThat(restart(auth, id).getResponse().getStatus()).isEqualTo(409);
        assertThat(statusOfJob(id)).isEqualTo("FAILED"); // state untouched
    }
}
