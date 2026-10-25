package com.example.messaging.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.messaging.domain.Channel;
import com.example.messaging.domain.Priority;
import com.example.messaging.messaging.BusDecision;
import com.example.messaging.messaging.DispatchEnvelope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Races between consumers (and between a consumer and other actors) resolve through compare-and-set, never twice. */
class ConcurrencyIT extends AbstractPipelineIT {

    @Test
    void severalConsumersRacingOnTheSameMessageCauseExactlyOneProviderCall() throws Exception {
        for (int round = 0; round < 5; round++) {
            quiesce();
            String id = submit(newRecipient(RETAIL), "EMAIL");
            awaitStatus(id, "QUEUED");
            DispatchEnvelope env = DispatchEnvelope.of(id, RETAIL, Channel.EMAIL, Priority.NORMAL, 1, "corr", Instant.now());
            int n = 6;
            ExecutorService pool = Executors.newFixedThreadPool(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<BusDecision>> decisions = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<BusDecision> c = () -> {
                    go.await();
                    return consumer.handle(env, 1);
                };
                decisions.add(pool.submit(c));
            }
            long before = email.callCount();
            go.countDown();
            for (Future<BusDecision> f : decisions) {
                assertThat(f.get().type()).isEqualTo(BusDecision.Type.ACK);
            }
            pool.shutdown();
            assertThat(email.callCount() - before).as("round %d", round).isEqualTo(1);
            assertThat(attemptRows(id)).isEqualTo(1);
            assertThat(statusOf(id)).isEqualTo("SENT_TO_PROVIDER");
            assertThat(jdbc.queryForObject("select count(*) from processed_message where dispatch_id = ?", Integer.class,
                    id + ":1")).isEqualTo(1);
            bus.resume();
            awaitIdle();
            assertThat(email.callCount() - before).isEqualTo(1); // the bus copy of the envelope is skipped too
        }
    }

    @Test
    void manyMessagesProcessedByTheWorkerPoolEachSendExactlyOnce() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            ids.add(submit(newRecipient(RETAIL), "EMAIL"));
        }
        for (String id : ids) {
            awaitStatus(id, "SENT_TO_PROVIDER");
        }
        assertThat(email.callCount()).isEqualTo(40);
        assertThat(email.acceptedCount()).isEqualTo(40);
        for (String id : ids) {
            assertThat(attemptRows(id)).isEqualTo(1);
        }
    }

    @Test
    void aCallbackRacingWithTheConsumersOwnPersistenceLeavesOneConsistentOutcome() throws Exception {
        for (int round = 0; round < 6; round++) {
            String id = submit(newRecipient(RETAIL), "EMAIL");
            awaitAttempts(id, 1);
            // deliver the receipt as early as the provider reference exists, whatever the consumer is doing
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
                    .until(() -> email.find(ref(id)).isPresent());
            String eventId = uniqueEventId();
            String body = "{\"event\":\"delivered\",\"event_id\":\"" + eventId + "\",\"meta\":{\"idempotencyRef\":\""
                    + ref(id) + "\"}}";
            String sig = com.example.messaging.callback.SignatureVerifier.header(SECRET, Instant.now().getEpochSecond(),
                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(postCallback("simulated-email", keyIdFor("simulated-email"), sig, eventId, body).status())
                    .isEqualTo(202);
            awaitStatus(id, "DELIVERED");
            awaitIdle();
            assertThat(statusOf(id)).isEqualTo("DELIVERED"); // never regresses to SENT_TO_PROVIDER
            assertThat(eventList(id)).hasSize(1);
        }
    }
}
