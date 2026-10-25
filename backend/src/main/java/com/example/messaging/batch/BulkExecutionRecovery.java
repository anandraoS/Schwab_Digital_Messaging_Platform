package com.example.messaging.batch;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.stereotype.Component;

/**
 * Knows which batch executions really run in this JVM and can close executions that no longer do.
 *
 * <p>A JVM that dies mid-job leaves its {@code BATCH_JOB_EXECUTION} in STARTED forever, and Spring Batch then refuses
 * to restart the job ("already running"). {@link #close} marks such an execution (and its open step executions) with a
 * final status so the normal restart path works again. It must only be applied to an execution that is not alive here:
 * jobs run on the node that accepted the request (the live set is per JVM), which is the documented single-node
 * assumption of the bulk module.
 */
@Component
public class BulkExecutionRecovery {

    private static final Logger log = LoggerFactory.getLogger(BulkExecutionRecovery.class);

    private final Set<Long> live = ConcurrentHashMap.newKeySet();
    private final JobExplorer explorer;
    private final JobRepository repository;

    public BulkExecutionRecovery(JobExplorer explorer, JobRepository repository) {
        this.explorer = explorer;
        this.repository = repository;
    }

    void started(long executionId) {
        live.add(executionId);
    }

    void finished(long executionId) {
        live.remove(executionId);
    }

    /** True while a thread of this JVM is executing the batch execution. */
    public boolean isLive(long executionId) {
        return live.contains(executionId);
    }

    /**
     * Closes an execution that is still open in the repository: job and step executions get {@code status} and an end
     * time. No-op when it is already finished.
     */
    public void close(long executionId, BatchStatus status, String reason) {
        JobExecution e = explorer.getJobExecution(executionId);
        if (e == null || (!e.isRunning() && !e.isStopping())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (StepExecution se : e.getStepExecutions()) {
            BatchStatus s = se.getStatus();
            if (s == BatchStatus.STARTING || s == BatchStatus.STARTED || s == BatchStatus.STOPPING) {
                se.setStatus(status);
                se.setExitStatus(new ExitStatus(status.name(), reason));
                se.setEndTime(now);
                repository.update(se);
            }
        }
        e.setStatus(status);
        e.setExitStatus(new ExitStatus(status.name(), reason));
        e.setEndTime(now);
        repository.update(e);
        log.warn("batch execution {} closed as {} ({})", executionId, status, reason);
    }
}
