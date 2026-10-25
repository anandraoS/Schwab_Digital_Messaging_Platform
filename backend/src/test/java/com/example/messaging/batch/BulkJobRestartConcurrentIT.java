package com.example.messaging.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Restart with four chunk workers: the reader cannot checkpoint an exact position, so the restarted execution re-reads
 * the file from the top. The ledger and the deterministic idempotency keys must still give exactly one message per
 * distinct row and exactly one record per rejected row.
 */
@TestPropertySource(properties = {
        "messaging.bulk.concurrency=4",
        "messaging.bulk.chunk-size=10",
        "messaging.bulk.skip-limit=5",
        "messaging.bulk.max-rows=500",
        "messaging.bulk.upload-dir=target/bulk-it-restart-mt",
        "messaging.bulk.sweeper-enabled=false"})
class BulkJobRestartConcurrentIT extends AbstractRestartIT {

    @Test
    void multiThreadedJob_restartedFromTheTop_producesNoDuplicates() throws Exception {
        String auth = bulkSender(RETAIL);
        crashOnCalls = Set.of(25);
        String id = uploadOk(auth, scenarioFile());

        assertThat(awaitTerminal(id)).isEqualTo("FAILED");
        long committed = messageCount(id);
        assertThat(committed).isLessThan(VALID_UNIQUE);
        assertThat(counters(id)[2]).as("counters equal what was really committed").isEqualTo(committed);
        assertThat(openClaims(id)).isZero();

        crashOnCalls = Set.of();
        assertThat(restart(auth, id).getResponse().getStatus()).isEqualTo(200);
        assertThat(awaitTerminal(id)).isEqualTo("COMPLETED_WITH_ERRORS");
        assertExactlyOnce(id);
        // replayed rows were recognised, not sent again
        assertThat(jdbc.queryForObject("select count(*) from audit_event where action = 'MESSAGE_ACCEPTED' "
                + "and entity_id in (select id from message_request where bulk_job_id = ?)", Long.class, id))
                .isEqualTo(VALID_UNIQUE);
    }
}
