package com.example.messaging.messaging;

import static com.example.messaging.messaging.BusTestSupport.bus;
import static com.example.messaging.messaging.BusTestSupport.envelope;
import static com.example.messaging.messaging.BusTestSupport.metrics;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.example.messaging.domain.DlqReason;
import com.example.messaging.domain.Priority;
import com.example.messaging.metrics.MessagingMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InMemoryMessageBusTest {

    private InMemoryMessageBus bus;
    private MessagingMetrics metrics;

    private InMemoryMessageBus start(int concurrency, int poison, int maxDepth) {
        metrics = metrics();
        bus = bus(concurrency, poison, maxDepth, Duration.ofSeconds(5), metrics);
        return bus;
    }

    @AfterEach
    void stop() {
        if (bus != null) {
            bus.stop();
        }
    }

    @Test
    void deliversHigherPriorityFirstAndFifoWithinAPriority() {
        start(1, 5, 100);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe((env, n) -> {
            order.add(env.messageId());
            return BusDecision.ack();
        });
        bus.pause(); // queue everything first so ordering is decided by the queue alone
        bus.start();
        bus.publish(envelope("low-1", Priority.LOW, 1));
        bus.publish(envelope("normal-1", Priority.NORMAL, 1));
        bus.publish(envelope("critical-1", Priority.CRITICAL, 1));
        bus.publish(envelope("normal-2", Priority.NORMAL, 1));
        bus.publish(envelope("high-1", Priority.HIGH, 1));
        bus.publish(envelope("critical-2", Priority.CRITICAL, 1));
        assertThat(bus.stats().totalDepth()).isEqualTo(6);
        bus.resume();
        await().atMost(Duration.ofSeconds(5)).until(() -> order.size() == 6);
        assertThat(order).containsExactly("critical-1", "critical-2", "high-1", "normal-1", "normal-2", "low-1");
    }

    @Test
    void ackRemovesTheEnvelopeAndStatsReturnToZero() throws Exception {
        start(2, 5, 100);
        AtomicInteger seen = new AtomicInteger();
        bus.subscribe((env, n) -> {
            seen.incrementAndGet();
            return BusDecision.ack();
        });
        bus.start();
        for (int i = 0; i < 10; i++) {
            bus.publish(envelope(Priority.NORMAL));
        }
        assertThat(bus.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(seen.get()).isEqualTo(10);
        QueueStats s = bus.stats();
        assertThat(s.totalDepth()).isZero();
        assertThat(s.inFlight()).isZero();
        assertThat(s.dlqDepth()).isZero();
        assertThat(s.oldestAgeMillis()).isNull();
        assertThat(s.consumers()).isEqualTo(2);
    }

    @Test
    void requeueRedeliversNoEarlierThanTheDelayAndCountsDeliveries() throws Exception {
        start(1, 5, 100);
        List<Integer> deliveries = Collections.synchronizedList(new ArrayList<>());
        AtomicLong firstAt = new AtomicLong();
        AtomicLong secondAt = new AtomicLong();
        bus.subscribe((env, n) -> {
            deliveries.add(n);
            if (n == 1) {
                firstAt.set(System.nanoTime());
                return BusDecision.requeue(Duration.ofMillis(1000));
            }
            secondAt.set(System.nanoTime());
            return BusDecision.ack();
        });
        bus.start();
        bus.publish(envelope(Priority.NORMAL));
        await().atMost(Duration.ofSeconds(5)).until(() -> deliveries.size() == 1);
        await().atMost(Duration.ofMillis(900)).until(() -> bus.stats().delayed() == 1); // in the scheduler, not queued
        assertThat(bus.stats().totalDepth()).isZero();
        await().atMost(Duration.ofSeconds(5)).until(() -> deliveries.size() == 2);
        assertThat(deliveries).containsExactly(1, 2);
        assertThat(TimeUnit.NANOSECONDS.toMillis(secondAt.get() - firstAt.get())).isGreaterThanOrEqualTo(950);
        assertThat(bus.awaitIdle(Duration.ofSeconds(2))).isTrue();
    }

    @Test
    void publishDelayedHoldsTheEnvelopeUntilDue() {
        start(1, 5, 100);
        AtomicLong at = new AtomicLong();
        bus.subscribe((env, n) -> {
            at.set(System.nanoTime());
            return BusDecision.ack();
        });
        bus.start();
        long published = System.nanoTime();
        bus.publishDelayed(envelope(Priority.LOW), Duration.ofMillis(250));
        assertThat(bus.stats().delayed()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(5)).until(() -> at.get() != 0);
        assertThat(TimeUnit.NANOSECONDS.toMillis(at.get() - published)).isGreaterThanOrEqualTo(230);
    }

    @Test
    void deadLetterDecisionMovesTheEnvelopeToTheDlqAndCountsIt() throws Exception {
        start(1, 5, 100);
        DispatchEnvelope env = envelope(Priority.HIGH);
        bus.subscribe((e, n) -> BusDecision.deadLetter(DlqReason.RETRIES_EXHAUSTED));
        bus.start();
        bus.publish(env);
        assertThat(bus.awaitIdle(Duration.ofSeconds(5))).isTrue();
        await().atMost(Duration.ofSeconds(2)).until(() -> bus.stats().dlqDepth() == 1);
        assertThat(bus.deadLetters()).singleElement().satisfies(d -> {
            assertThat(d.envelope()).isEqualTo(env);
            assertThat(d.reason()).isEqualTo(DlqReason.RETRIES_EXHAUSTED);
            assertThat(d.deliveryCount()).isEqualTo(1);
        });
        assertThat(metrics.registry().get(MessagingMetrics.DEADLETTER_TOTAL).tag("reason", "RETRIES_EXHAUSTED")
                .counter().count()).isEqualTo(1.0);
        assertThat(bus.purgeDeadLetters()).isEqualTo(1);
        assertThat(bus.stats().dlqDepth()).isZero();
    }

    @Test
    void aHandlerThatKeepsThrowingIsDeadLetteredAsPoisonAfterTheThreshold() throws Exception {
        start(1, 3, 100);
        AtomicInteger calls = new AtomicInteger();
        List<Integer> poisonCalls = Collections.synchronizedList(new ArrayList<>());
        DispatchHandler handler = new DispatchHandler() {
            @Override
            public BusDecision handle(DispatchEnvelope envelope, int deliveryCount) {
                calls.incrementAndGet();
                throw new IllegalStateException("boom");
            }

            @Override
            public void onPoison(DispatchEnvelope envelope, int deliveryCount, Throwable cause) {
                poisonCalls.add(deliveryCount);
            }
        };
        bus.subscribe(handler);
        bus.start();
        bus.publish(envelope(Priority.NORMAL));
        await().atMost(Duration.ofSeconds(10)).until(() -> bus.stats().dlqDepth() == 1);
        Thread.sleep(500); // a poison message must not be redelivered any more
        assertThat(calls.get()).isEqualTo(3);
        assertThat(poisonCalls).containsExactly(3);
        assertThat(bus.deadLetters().get(0).reason()).isEqualTo(DlqReason.POISON_MESSAGE);
        assertThat(bus.stats().totalDepth()).isZero();
        assertThat(bus.stats().delayed()).isZero();
    }

    @Test
    void aFailureCountResetsNothingButASuccessfulRetryStillAcks() throws Exception {
        start(1, 5, 100);
        AtomicInteger calls = new AtomicInteger();
        bus.subscribe((env, n) -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("transient");
            }
            return BusDecision.ack();
        });
        bus.start();
        bus.publish(envelope(Priority.NORMAL));
        assertThat(bus.awaitIdle(Duration.ofSeconds(10))).isTrue();
        assertThat(calls.get()).isEqualTo(3);
        assertThat(bus.stats().dlqDepth()).isZero();
    }

    @Test
    void publishIsRefusedAtTheLimitWithARetryAfterHintButDelayedRedeliveriesAreNot() {
        start(1, 5, 2);
        bus.subscribe((env, n) -> BusDecision.ack());
        bus.pause();
        bus.start();
        bus.publish(envelope(Priority.NORMAL));
        bus.publish(envelope(Priority.NORMAL));
        assertThatThrownBy(() -> bus.publish(envelope(Priority.CRITICAL))).isInstanceOf(BusRejectedException.class)
                .satisfies(e -> assertThat(((BusRejectedException) e).getRetryAfterSeconds()).isGreaterThanOrEqualTo(1));
        // an accepted message is never dropped by backpressure: delayed redelivery bypasses the limit
        bus.publishDelayed(envelope(Priority.NORMAL), Duration.ofMillis(10));
        await().atMost(Duration.ofSeconds(2)).until(() -> bus.stats().totalDepth() == 3);
        assertThat(bus.stats().totalDepth()).isEqualTo(3);
    }

    @Test
    void pauseStopsDeliveriesButNotPublishingAndResumeContinues() throws Exception {
        start(2, 5, 100);
        AtomicInteger seen = new AtomicInteger();
        bus.subscribe((env, n) -> {
            seen.incrementAndGet();
            return BusDecision.ack();
        });
        bus.start();
        bus.pause();
        Thread.sleep(200); // workers finish their current poll
        for (int i = 0; i < 5; i++) {
            bus.publish(envelope(Priority.NORMAL));
        }
        Thread.sleep(300);
        assertThat(seen.get()).isZero();
        assertThat(bus.isPaused()).isTrue();
        assertThat(bus.stats().paused()).isTrue();
        assertThat(bus.stats().totalDepth()).isEqualTo(5);
        bus.resume();
        assertThat(bus.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(seen.get()).isEqualTo(5);
        assertThat(bus.isPaused()).isFalse();
    }

    @Test
    void workersRunInParallelUpToTheConfiguredConcurrencyAndNoFurther() throws Exception {
        start(3, 5, 100);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        bus.subscribe((env, n) -> {
            int now = active.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            active.decrementAndGet();
            return BusDecision.ack();
        });
        bus.start();
        for (int i = 0; i < 9; i++) {
            bus.publish(envelope(Priority.NORMAL));
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> active.get() == 3);
        assertThat(bus.stats().inFlight()).isEqualTo(3);
        assertThat(bus.stats().totalDepth()).isEqualTo(6);
        release.countDown();
        assertThat(bus.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(peak.get()).isEqualTo(3);
    }

    @Test
    void statsReportDepthPerPriorityAndTheAgeOfTheOldestEnvelope() throws Exception {
        start(1, 5, 100);
        bus.subscribe((env, n) -> BusDecision.ack());
        bus.pause();
        bus.start();
        bus.publish(envelope(Priority.LOW));
        Thread.sleep(40);
        bus.publish(envelope(Priority.CRITICAL));
        bus.publish(envelope(Priority.CRITICAL));
        QueueStats s = bus.stats();
        assertThat(s.depthByPriority()).containsEntry(Priority.CRITICAL, 2L).containsEntry(Priority.LOW, 1L)
                .containsEntry(Priority.HIGH, 0L).containsEntry(Priority.NORMAL, 0L);
        assertThat(s.oldestAgeMillis()).isGreaterThanOrEqualTo(40);
        assertThat(s.totalDepth()).isEqualTo(3);
    }

    @Test
    void startAndStopAreIdempotentAndEnvelopesPublishedWhileStoppedAreKept() throws Exception {
        start(1, 5, 100);
        AtomicInteger seen = new AtomicInteger();
        bus.subscribe((env, n) -> {
            seen.incrementAndGet();
            return BusDecision.ack();
        });
        bus.start();
        bus.start();
        assertThat(bus.isRunning()).isTrue();
        bus.stop();
        bus.stop();
        assertThat(bus.isRunning()).isFalse();
        bus.publish(envelope(Priority.NORMAL)); // publishing keeps working, consumption does not
        Thread.sleep(200);
        assertThat(seen.get()).isZero();
        bus.start();
        assertThat(bus.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(seen.get()).isEqualTo(1);
    }

    @Test
    void aNullDecisionIsTreatedAsAck() throws Exception {
        start(1, 5, 100);
        bus.subscribe((env, n) -> null);
        bus.start();
        bus.publish(envelope(Priority.NORMAL));
        assertThat(bus.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(bus.stats().dlqDepth()).isZero();
    }
}
