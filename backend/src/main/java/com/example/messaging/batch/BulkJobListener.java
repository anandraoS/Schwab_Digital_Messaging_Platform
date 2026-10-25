package com.example.messaging.batch;

import com.example.messaging.common.Ids;
import com.example.messaging.domain.BulkJobStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Drives the {@code bulk_job.status} transitions:
 * CREATED to RUNNING (beforeJob) to COMPLETED / COMPLETED_WITH_ERRORS / FAILED / STOPPED (afterJob).
 * ABANDONED is set only by {@link BulkJobSweeper}; a job abandoned meanwhile is not overwritten.
 */
public class BulkJobListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(BulkJobListener.class);

    private final BulkJobStore store;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int skipLimit;
    private final BulkExecutionRecovery recovery;
    private final BulkUploadStorage storage;
    private final boolean deleteFileOnCompletion;

    public BulkJobListener(BulkJobStore store, JdbcTemplate jdbc, Clock clock, int skipLimit,
            BulkExecutionRecovery recovery, BulkUploadStorage storage, boolean deleteFileOnCompletion) {
        this.recovery = recovery;
        this.storage = storage;
        this.deleteFileOnCompletion = deleteFileOnCompletion;
        this.store = store;
        this.jdbc = jdbc;
        this.clock = clock;
        this.skipLimit = skipLimit;
    }

    @Override
    public void beforeJob(JobExecution execution) {
        String jobId = jobId(execution);
        // line 0 is the job-level failure slot: clear a reason left by an earlier attempt
        jdbc.update("delete from bulk_job_failure where job_id = ? and line_no = 0", jobId);
        recovery.started(execution.getId());
        store.markRunning(jobId, execution.getId());
        log.info("bulk job {} started (execution {})", jobId, execution.getId());
    }

    @Override
    public void afterJob(JobExecution execution) {
        try {
            finish(execution);
        } finally {
            recovery.finished(execution.getId());
        }
    }

    private void finish(JobExecution execution) {
        String jobId = jobId(execution);
        BulkJobStatus status;
        BatchStatus batch = execution.getStatus();
        if (batch == BatchStatus.COMPLETED) {
            long[] c = store.counters(jobId);
            status = c[3] > 0 ? BulkJobStatus.COMPLETED_WITH_ERRORS : BulkJobStatus.COMPLETED;
        } else if (batch == BatchStatus.STOPPED || batch == BatchStatus.STOPPING) {
            status = BulkJobStatus.STOPPED;
        } else {
            status = BulkJobStatus.FAILED;
            recordJobFailure(jobId, execution);
        }
        store.markFinishedIfOpen(jobId, status);
        if (deleteFileOnCompletion && (status == BulkJobStatus.COMPLETED || status == BulkJobStatus.COMPLETED_WITH_ERRORS)) {
            String file = execution.getJobParameters().getString(BulkJobConfig.PARAM_FILE);
            if (file != null) {
                storage.deleteQuietly(java.nio.file.Path.of(file));
            }
        }
        log.info("bulk job {} finished with {} (batch status {})", jobId, status, batch);
    }

    private void recordJobFailure(String jobId, JobExecution execution) {
        String reason = "STEP_FAILED";
        for (Throwable t : execution.getAllFailureExceptions()) {
            reason = describe(t);
            break;
        }
        jdbc.update("insert into bulk_job_failure (id, job_id, line_no, reason, raw_line_hash, created_at) "
                + "values (?, ?, 0, ?, null, ?)", Ids.newId(), jobId, reason,
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    private String describe(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof SkipLimitExceededException) {
                return "SKIP_LIMIT_EXCEEDED: more than " + skipLimit + " rows were rejected";
            }
        }
        if (t instanceof BulkRowException b) {
            return b.getMessage();
        }
        // exception messages may echo input: report the type of the root cause only (Batch wraps the real failure)
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return "STEP_FAILED: " + root.getClass().getSimpleName();
    }

    private static String jobId(JobExecution e) {
        return e.getJobParameters().getString(BulkJobConfig.PARAM_JOB_ID);
    }
}
