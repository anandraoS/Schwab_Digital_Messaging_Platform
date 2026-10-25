package com.example.messaging.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.messaging.pipeline.AbstractPipelineIT;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * The sweeper's contract (ADR-0010): only PROCESSING messages with an expired lock and SENT_TO_PROVIDER messages past
 * the receipt timeout become DELIVERY_UNKNOWN; never anything else, never twice, never a resend.
 */
class StuckMessageSweeperTest extends AbstractPipelineIT {

    private String processingMessage(Instant lockedUntil) throws Exception {
        quiesce();
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "QUEUED");
        forceStatus(id, "PROCESSING");
        jdbc.update("update message_request set locked_until = ? where id = ?", utc(lockedUntil), id);
        return id;
    }

    @Test
    void aLockThatHasNotExpiredIsLeftAlone() throws Exception {
        String id = processingMessage(Instant.now().plus(Duration.ofMinutes(1)));
        sweeper.sweep();
        sweeper.sweep();
        assertThat(statusOf(id)).isEqualTo("PROCESSING");
        assertThat(auditCount(id, "STUCK_SWEEP")).isZero();
    }

    @Test
    void anExpiredLockBecomesDeliveryUnknownWithAuditAndWithoutAnyProviderCall() throws Exception {
        String id = processingMessage(Instant.now().minus(Duration.ofSeconds(5)));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> {
            sweeper.sweep();
            return statusOf(id).equals("DELIVERY_UNKNOWN");
        });
        assertThat(auditCount(id, "STUCK_SWEEP")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status_reason from message_request where id = ?", String.class, id))
                .startsWith("STUCK_SWEEP");
        assertThat(email.callCount()).isZero();
        assertThat(jdbc.queryForObject("select detail_json from audit_event where entity_id = ? and action = "
                + "'STUCK_SWEEP'", String.class, id)).contains("PROCESSING").contains("lock expired");
    }

    @Test
    void sweepingIsIdempotentAndRacingSweepersMoveEachMessageExactlyOnce() throws Exception {
        List<String> ids = new ArrayList<>();
        quiesce();
        for (int i = 0; i < 5; i++) {
            String id = submit(newRecipient(RETAIL), "EMAIL");
            awaitStatus(id, "QUEUED");
            forceStatus(id, "PROCESSING");
            jdbc.update("update message_request set locked_until = ? where id = ?", utc(Instant.now().minusSeconds(30)), id);
            ids.add(id);
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> moved = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            moved.add(pool.submit(() -> {
                go.await();
                return sweeper.sweep();
            }));
        }
        go.countDown();
        int total = 0;
        for (Future<Integer> f : moved) {
            total += f.get();
        }
        pool.shutdown();
        // the scheduler may have swept some first, but nothing is ever counted twice
        assertThat(total).isLessThanOrEqualTo(5);
        for (String id : ids) {
            assertThat(statusOf(id)).isEqualTo("DELIVERY_UNKNOWN");
            assertThat(auditCount(id, "STUCK_SWEEP")).isEqualTo(1);
        }
        assertThat(sweeper.sweep()).isZero();
    }

    @Test
    void aSentMessageWithoutReceiptBecomesUnknownOnlyAfterTheReceiptTimeout() throws Exception {
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "SENT_TO_PROVIDER");
        sweeper.sweep();
        assertThat(statusOf(id)).isEqualTo("SENT_TO_PROVIDER"); // 15 minutes have not passed
        clock.advance(Duration.ofMinutes(14));
        sweeper.sweep();
        assertThat(statusOf(id)).isEqualTo("SENT_TO_PROVIDER");
        clock.advance(Duration.ofMinutes(2));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> {
            sweeper.sweep();
            return statusOf(id).equals("DELIVERY_UNKNOWN");
        });
        assertThat(auditCount(id, "STUCK_SWEEP")).isEqualTo(1);
        assertThat(email.callCount()).isEqualTo(1); // the receipt is missing, not the message: no resend
    }

    @Test
    void healthyAndTerminalMessagesAreNeverTouched() throws Exception {
        String delivered = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(delivered, "SENT_TO_PROVIDER");
        sendCallback(delivered, com.example.messaging.domain.DeliveryEventType.DELIVERED, uniqueEventId());
        String queued = processingMessage(Instant.now().minus(Duration.ofSeconds(5)));
        forceStatus(queued, "QUEUED"); // not processing any more although a stale lock is left behind
        clock.advance(Duration.ofHours(2));
        sweeper.sweep();
        assertThat(statusOf(delivered)).isEqualTo("DELIVERED");
        assertThat(statusOf(queued)).isEqualTo("QUEUED");
        assertThat(auditCount(delivered, "STUCK_SWEEP")).isZero();
        assertThat(auditCount(queued, "STUCK_SWEEP")).isZero();
    }
}
