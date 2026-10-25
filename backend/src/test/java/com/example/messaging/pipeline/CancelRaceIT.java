package com.example.messaging.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.messaging.domain.Channel;
import com.example.messaging.domain.Priority;
import com.example.messaging.messaging.DispatchEnvelope;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Cancel versus dispatch: whichever compare-and-set wins, the other side must see a consistent world. */
class CancelRaceIT extends AbstractPipelineIT {

    private int cancel(String auth, String id) throws Exception {
        return mvc.perform(post("/api/v1/messages/" + id + "/cancel").header("Authorization", auth)).andReturn()
                .getResponse().getStatus();
    }

    @Test
    void cancelRacingWithTheConsumerEitherCancelsBeforeAnySendOrIsRefusedAfterIt() throws Exception {
        String auth = sender(RETAIL);
        int cancelled = 0;
        int refused = 0;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int round = 0; round < 30; round++) {
            quiesce();
            String id = submit(newRecipient(RETAIL), "EMAIL");
            awaitStatus(id, "QUEUED");
            DispatchEnvelope env = DispatchEnvelope.of(id, RETAIL, Channel.EMAIL, Priority.NORMAL, 1, "c", Instant.now());
            CountDownLatch go = new CountDownLatch(1);
            Future<Integer> cancelResult = pool.submit(() -> {
                go.await();
                return cancel(auth, id);
            });
            Future<?> consume = pool.submit(() -> {
                go.await();
                return consumer.handle(env, 1);
            });
            go.countDown();
            int code = cancelResult.get();
            consume.get();
            bus.resume();
            awaitIdle();
            String status = statusOf(id);
            if (code == 200) {
                cancelled++;
                assertThat(status).as("round %d", round).isEqualTo("CANCELLED");
                assertThat(email.find(ref(id))).as("a cancelled message must never reach the provider").isEmpty();
                assertThat(attemptRows(id)).isZero();
            } else {
                refused++;
                assertThat(code).isEqualTo(409); // too late: it was claimed first
                assertThat(status).isIn("PROCESSING", "SENT_TO_PROVIDER");
                assertThat(attemptRows(id)).isEqualTo(1);
            }
            // never both
            assertThat(email.find(ref(id)).isPresent() && "CANCELLED".equals(status)).isFalse();
            bus.pause();
        }
        pool.shutdown();
        assertThat(cancelled + refused).isEqualTo(30);
    }

    @Test
    void cancellingWhileTheMessageWaitsInRetryBackoffWins() throws Exception {
        quiesce();
        faults.set("simulated-email", com.example.messaging.provider.simulator.FaultInjector.Mode.TRANSIENT, 1.0);
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "QUEUED");
        // attempt 1 runs by hand on the paused bus: it fails transiently and schedules a delayed redelivery
        consumer.handle(DispatchEnvelope.of(id, RETAIL, Channel.EMAIL, Priority.NORMAL, 1, "c", Instant.now()), 1);
        assertThat(statusOf(id)).isEqualTo("RETRY_PENDING");
        assertThat(cancel(sender(RETAIL), id)).isEqualTo(200);
        assertThat(statusOf(id)).isEqualTo("CANCELLED");
        long calls = email.callCount();
        bus.resume(); // the delayed redelivery arrives and finds a cancelled message
        Thread.sleep(500);
        awaitIdle();
        assertThat(email.callCount()).isEqualTo(calls);
        assertThat(statusOf(id)).isEqualTo("CANCELLED");
        assertThat(attemptRows(id)).isEqualTo(1);
    }

    @Test
    void cancelOfASentMessageIsRefusedWithAnIllegalTransitionError() throws Exception {
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "SENT_TO_PROVIDER");
        assertThat(cancel(sender(RETAIL), id)).isEqualTo(409);
        assertThat(statusOf(id)).isEqualTo("SENT_TO_PROVIDER");
    }
}
