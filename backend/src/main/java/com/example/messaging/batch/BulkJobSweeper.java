package com.example.messaging.batch;

import com.example.messaging.domain.BulkJobStatus;
import com.example.messaging.service.AuditService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Marks bulk jobs that are CREATED/RUNNING but have not made progress for {@code messaging.bulk.stale-after} as
 * {@code ABANDONED} (a crashed JVM leaves them forever RUNNING). Their batch execution, if still open, is closed as
 * FAILED so it cannot block a later manual clean-up. {@code ABANDONED} is terminal and not restartable through the API:
 * an operator decides (see the bulk-job-stuck runbook) because a new job for the same file would not share idempotency
 * keys with the abandoned one.
 */
@Component
public class BulkJobSweeper {

    private static final Logger log = LoggerFactory.getLogger(BulkJobSweeper.class);

    private final JdbcTemplate jdbc;
    private final BulkJobStore store;
    private final BulkExecutionRecovery recovery;
    private final AuditService audit;
    private final BulkProperties props;
    private final Clock clock;

    public BulkJobSweeper(JdbcTemplate jdbc, BulkJobStore store, BulkExecutionRecovery recovery, AuditService audit,
            BulkProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.store = store;
        this.recovery = recovery;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${messaging.bulk.sweep-interval:60s}",
            initialDelayString = "${messaging.bulk.sweep-interval:60s}")
    public void scheduled() {
        if (!props.sweeperEnabled()) {
            return;
        }
        try {
            sweep(props.staleAfter());
        } catch (RuntimeException e) {
            log.warn("bulk job sweep failed: {}", e.getClass().getSimpleName());
        }
    }

    /** @return number of jobs abandoned */
    public int sweep(Duration staleAfter) {
        LocalDateTime cutoff = LocalDateTime.ofInstant(clock.instant().minus(staleAfter), ZoneOffset.UTC);
        List<Map<String, Object>> stale = jdbc.queryForList(
                "select id, tenant_id, spring_batch_execution_id from bulk_job "
                        + "where status in ('CREATED','RUNNING') and updated_at < ?", cutoff);
        int n = 0;
        for (Map<String, Object> row : stale) {
            String id = (String) row.get("id");
            Object exec = row.get("spring_batch_execution_id");
            if (exec instanceof Number num && !recovery.isLive(num.longValue())) {
                recovery.close(num.longValue(), BatchStatus.FAILED, "abandoned by BulkJobSweeper");
            }
            store.markFinishedIfOpen(id, BulkJobStatus.ABANDONED);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("reason", "no progress for " + staleAfter);
            audit.recordAs("system", "SYSTEM", (String) row.get("tenant_id"), "BULK_JOB_ABANDONED",
                    BulkJobService.ENTITY, id, detail);
            log.warn("bulk job {} abandoned (no progress for {})", id, staleAfter);
            n++;
        }
        return n;
    }
}
