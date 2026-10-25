package com.example.messaging.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.messaging.service.NotificationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Shared scenario for the restart tests. The file has 60 data rows: 55 distinct valid recipients, 3 duplicates of
 * earlier rows (lines 22, 59, 61) and 2 invalid rows (lines 43, 60). A "worker crash" is injected by making
 * {@code NotificationService.submitInternal} throw a non-skippable, non-retryable exception on chosen call numbers.
 */
abstract class AbstractRestartIT extends AbstractBulkIT {

    static final int VALID_UNIQUE = 55;
    static final int TOTAL_ROWS = 60;

    @MockitoSpyBean
    NotificationService notifications;

    final AtomicInteger calls = new AtomicInteger();
    volatile Set<Integer> crashOnCalls = Set.of();

    @BeforeEach
    void prepare() {
        provisioner.ensure(RETAIL, 300);
        calls.set(0);
        crashOnCalls = Set.of();
        doAnswer(inv -> {
            if (crashOnCalls.contains(calls.incrementAndGet())) {
                throw new IllegalStateException("simulated worker crash");
            }
            return inv.callRealMethod();
        }).when(notifications).submitInternal(any(), any(), any(), any(), any());
    }

    static byte[] scenarioFile() {
        List<String> lines = new ArrayList<>();
        lines.addAll(okRows(1, 20));                 // lines 2-21
        lines.add(ok(5));                            // line 22: duplicate of line 6
        lines.addAll(okRows(21, 40));                // lines 23-42
        lines.add(row("NOPE-1", "en", "1234", "Pat", "buy", "10", "ACME", "12.50")); // line 43: unknown recipient
        lines.addAll(okRows(41, 55));                // lines 44-58
        lines.add(ok(30));                           // line 59: duplicate of line 32
        lines.add("SYN-0000099,en,1234");            // line 60: wrong column count
        lines.add(ok(1));                            // line 61: duplicate of line 2
        return csv(lines);
    }

    MvcResult restart(String auth, String id) throws Exception {
        return mvc.perform(post("/api/v1/bulk-jobs/" + id + "/restart").header("Authorization", auth)).andReturn();
    }

    /** The invariants that must hold after the job finally completes, however often it was interrupted. */
    void assertExactlyOnce(String id) {
        assertThat(counters(id)).as(() -> failureSummary(id)).containsExactly(TOTAL_ROWS, TOTAL_ROWS, VALID_UNIQUE, 2, 3);
        assertThat(messageCount(id)).isEqualTo(VALID_UNIQUE);
        assertThat(distinctKeyCount(id)).isEqualTo(VALID_UNIQUE);
        assertThat(jdbc.queryForObject("select count(distinct recipient_id) from message_request where bulk_job_id = ?",
                Long.class, id)).isEqualTo(VALID_UNIQUE);
        assertThat(jdbc.queryForList("select idempotency_key from message_request where bulk_job_id = ? "
                + "group by idempotency_key having count(*) > 1", String.class, id)).isEmpty();
        assertThat(openClaims(id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event o join message_request m on m.id = o.aggregate_id "
                + "where m.bulk_job_id = ?", Long.class, id)).isEqualTo(VALID_UNIQUE); // one outbox row per message
        // every reconciled failure is recorded exactly once
        assertThat(jdbc.queryForObject("select count(*) from bulk_job_failure where job_id = ? and line_no > 0",
                Long.class, id)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(distinct line_no) from bulk_job_failure where job_id = ?",
                Long.class, id)).isEqualTo(5);
    }
}
