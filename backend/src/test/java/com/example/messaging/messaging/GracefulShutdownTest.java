package com.example.messaging.messaging;

import static com.example.messaging.messaging.BusTestSupport.bus;
import static com.example.messaging.messaging.BusTestSupport.envelope;
import static com.example.messaging.messaging.BusTestSupport.metrics;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.messaging.domain.Priority;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Graceful stop of the in-memory bus: drain in-flight work, stop taking new work, return unacked work on timeout. */
class GracefulShutdownTest {

    @Test
    void stopLetsInFlightDeliveriesFinishAndTakesNoNewOnes() throws Exception {
        InMemoryMessageBus bus = bus(2, 5, 100, Duration.ofSeconds(5), metrics());
        AtomicInteger started = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        CountDownLatch bothRunning = new CountDownLatch(2);
        bus.subscribe((env, n) -> {
            started.incrementAndGet();
            bothRunning.countDown();
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.incrementAndGet();
            return BusDecision.ack();
        });
        bus.start();
        for (int i = 0; i < 6; i++) {
            bus.publish(envelope(Priority.NORMAL));
        }
        assertThat(bothRunning.await(5, TimeUnit.SECONDS)).isTrue();

        long t0 = System.nanoTime();
        bus.stop();
        long stopMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        // the two in-flight deliveries completed (stop waited for them), nothing else was started
        assertThat(finished.get()).isEqualTo(started.get());
        assertThat(started.get()).isBetween(2, 4); // a worker may poll once more before it sees the stop flag
        assertThat(stopMs).isGreaterThanOrEqualTo(300).isLessThan(4000);
        assertThat(bus.isRunning()).isFalse();
        assertThat(bus.stats().inFlight()).isZero();
        // the rest stays on the queue for the next consumer, nothing was lost
        assertThat(bus.stats().totalDepth() + finished.get()).isEqualTo(6);
        int seenAtStop = started.get();
        Thread.sleep(300);
        assertThat(started.get()).isEqualTo(seenAtStop);

        // a restart (the next instance in real life) picks the leftovers up
        bus.start();
        assertThat(bus.awaitIdle(Duration.ofSeconds(10))).isTrue();
        assertThat(finished.get()).isEqualTo(6);
        bus.stop();
    }

    @Test
    void deliveriesThatDoNotFinishWithinTheTimeoutGoBackToTheQueue() throws Exception {
        InMemoryMessageBus bus = bus(1, 5, 100, Duration.ofMillis(300), metrics());
        AtomicInteger deliveries = new AtomicInteger();
        CountDownLatch inHandler = new CountDownLatch(1);
        bus.subscribe((env, n) -> {
            deliveries.incrementAndGet();
            inHandler.countDown();
            try {
                Thread.sleep(30_000); // a hung provider call
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return BusDecision.ack();
        });
        bus.start();
        DispatchEnvelope env = envelope(Priority.HIGH);
        bus.publish(env);
        assertThat(inHandler.await(5, TimeUnit.SECONDS)).isTrue();

        long t0 = System.nanoTime();
        bus.stop();
        long stopMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertThat(stopMs).isGreaterThanOrEqualTo(250).isLessThan(5000); // bounded by the shutdown timeout
        await().atMost(Duration.ofSeconds(5)).until(() -> bus.stats().inFlight() == 0);
        // the unacknowledged delivery is back on the queue, as a redelivery
        assertThat(bus.stats().totalDepth()).isEqualTo(1);
        assertThat(bus.stats().dlqDepth()).isZero();
        assertThat(deliveries.get()).isEqualTo(1);

        // the next consumer receives it with an incremented delivery count
        AtomicInteger countSeen = new AtomicInteger();
        InMemoryMessageBus next = bus; // same instance restarted with a well-behaved handler
        next.subscribe((e, n) -> {
            countSeen.set(n);
            return BusDecision.ack();
        });
        next.start();
        assertThat(next.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(countSeen.get()).isEqualTo(2);
        next.stop();
    }

    @Test
    void stoppingABusThatNeverStartedIsHarmless() {
        InMemoryMessageBus bus = bus(1, 5, 100, Duration.ofSeconds(1), metrics());
        bus.stop();
        assertThat(bus.isRunning()).isFalse();
        assertThat(bus.stats().consumers()).isZero();
    }
}
