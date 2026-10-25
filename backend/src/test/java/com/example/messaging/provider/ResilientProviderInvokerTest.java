package com.example.messaging.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.messaging.config.PipelineProperties;
import com.example.messaging.domain.Channel;
import com.example.messaging.metrics.MessagingMetrics;
import com.example.messaging.provider.ResilientProviderInvoker.CircuitState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ResilientProviderInvokerTest {

    private ResilientProviderInvoker invoker;
    private SimpleMeterRegistry registry;
    private MessagingMetrics metrics;

    private ResilientProviderInvoker invoker(int minimumCalls, int window, Duration openWait, int threads, int queue) {
        registry = new SimpleMeterRegistry();
        metrics = new MessagingMetrics(registry);
        invoker = new ResilientProviderInvoker(new PipelineProperties(true, Duration.ofSeconds(1), Duration.ofMillis(250),
                Duration.ofMillis(150), Duration.ofHours(24),
                new PipelineProperties.Scheduler(true, 200, 100, 1000, Duration.ofSeconds(5), Duration.ofMinutes(1), 10000,
                        100, 15000, 50, 5),
                new PipelineProperties.Resilience(50f, window, minimumCalls, openWait, 2, threads, queue), Duration.ofMinutes(10)), metrics);
        return invoker;
    }

    @AfterEach
    void close() {
        if (invoker != null) {
            invoker.destroy();
        }
    }

    /** Provider whose behaviour is a function of the call number; counts calls. */
    private static class FakeProvider implements SmsProvider {
        final AtomicInteger calls = new AtomicInteger();
        final Function<Integer, ProviderResult> behaviour;
        volatile Optional<ProviderStatus> lookupAnswer = Optional.empty();

        FakeProvider(Function<Integer, ProviderResult> behaviour) {
            this.behaviour = behaviour;
        }

        @Override
        public String providerId() {
            return "fake";
        }

        @Override
        public ProviderResult send(ProviderRequest request) {
            return behaviour.apply(calls.incrementAndGet());
        }

        @Override
        public Optional<ProviderStatus> lookup(String idempotencyRef) {
            return lookupAnswer;
        }
    }

    private ProviderRequest request(Duration timeout) {
        return new ProviderRequest("m", "m:1", "t", Channel.SMS, "+1555", null, "body", false, Map.of(), "c", timeout);
    }

    private static ProviderResult ok() {
        return ProviderResult.accepted("pm-1", 1);
    }

    private static ProviderResult transientFailure() {
        return ProviderResult.failure(ProviderOutcome.TRANSIENT_FAILURE, "E5XX", "down", 1);
    }

    @Test
    void aSuccessfulCallPassesThroughAndIsMeasured() {
        invoker(10, 20, Duration.ofSeconds(5), 2, 4);
        FakeProvider p = new FakeProvider(n -> ok());
        ProviderResult r = invoker.invoke(p, request(Duration.ofSeconds(1)));
        assertThat(r.outcome()).isEqualTo(ProviderOutcome.ACCEPTED);
        assertThat(r.providerMessageId()).isEqualTo("pm-1");
        assertThat(invoker.state("fake")).isEqualTo(CircuitState.CLOSED);
        assertThat(invoker.lastError("fake")).isEmpty();
        assertThat(registry.get(MessagingMetrics.PROVIDER_CALLS).tag("provider", "fake").tag("channel", "SMS")
                .tag("outcome", "ACCEPTED").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(MessagingMetrics.PROVIDER_LATENCY).tag("provider", "fake").timer().count()).isEqualTo(1);
    }

    @Test
    void aHungProviderIsCutOffAtTheHardTimeoutAndReportedAsTimeout() {
        invoker(10, 20, Duration.ofSeconds(5), 2, 4);
        CountDownLatch release = new CountDownLatch(1);
        FakeProvider p = new FakeProvider(n -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ok();
        });
        long start = System.nanoTime();
        ProviderResult r = invoker.invoke(p, request(Duration.ofMillis(200)));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(r.outcome()).isEqualTo(ProviderOutcome.TIMEOUT);
        assertThat(r.errorCode()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(ms).isBetween(180L, 3000L);
        assertThat(invoker.lastError("fake")).contains("TIMEOUT:PROVIDER_TIMEOUT");
        release.countDown();
    }

    @Test
    void anAdapterThatThrowsIsTreatedAsAmbiguousBecauseWeCannotKnowWhetherItAccepted() {
        invoker(10, 20, Duration.ofSeconds(5), 2, 4);
        FakeProvider p = new FakeProvider(n -> {
            throw new IllegalStateException("adapter bug with alice@example.test");
        });
        ProviderResult r = invoker.invoke(p, request(Duration.ofSeconds(1)));
        assertThat(r.outcome()).isEqualTo(ProviderOutcome.AMBIGUOUS);
        assertThat(r.errorCode()).isEqualTo("PROVIDER_ERROR");
        assertThat(r.errorMessage()).isEqualTo("IllegalStateException"); // class name only, never the message
        assertThat(invoker.lastError("fake")).contains("AMBIGUOUS:PROVIDER_ERROR");
    }

    @Test
    void aNullAnswerIsAmbiguousToo() {
        invoker(10, 20, Duration.ofSeconds(5), 2, 4);
        FakeProvider p = new FakeProvider(n -> null);
        assertThat(invoker.invoke(p, request(Duration.ofSeconds(1))).outcome()).isEqualTo(ProviderOutcome.AMBIGUOUS);
    }

    @Test
    void theCircuitOpensAfterEnoughFailuresAndThenShortCircuitsWithoutCallingTheProvider() {
        invoker(5, 10, Duration.ofSeconds(30), 2, 4);
        FakeProvider p = new FakeProvider(n -> transientFailure());
        for (int i = 0; i < 5; i++) {
            assertThat(invoker.invoke(p, request(Duration.ofSeconds(1))).outcome())
                    .isEqualTo(ProviderOutcome.TRANSIENT_FAILURE);
        }
        assertThat(invoker.state("fake")).isEqualTo(CircuitState.OPEN);
        assertThat(invoker.isOpen("fake")).isTrue();
        int callsAtOpen = p.calls.get();
        ProviderResult blocked = invoker.invoke(p, request(Duration.ofSeconds(1)));
        assertThat(blocked.outcome()).isEqualTo(ProviderOutcome.TRANSIENT_FAILURE);
        assertThat(blocked.errorCode()).isEqualTo(ResilientProviderInvoker.CIRCUIT_OPEN);
        assertThat(p.calls.get()).isEqualTo(callsAtOpen); // the provider was not touched
        assertThat(registry.get(MessagingMetrics.PROVIDER_CALLS).tag("provider", "fake")
                .tag("outcome", "TRANSIENT_FAILURE").counter().count()).isEqualTo(6.0);
        // the gauge reports 2 = open
        assertThat(registry.get(MessagingMetrics.CIRCUIT_STATE).tag("provider", "fake").gauge().value()).isEqualTo(2.0);
    }

    @Test
    void theCircuitHalfOpensAfterTheWaitAndClosesAgainWhenProbesSucceed() {
        invoker(4, 8, Duration.ofMillis(300), 2, 4);
        boolean[] healthy = {false};
        FakeProvider p = new FakeProvider(n -> healthy[0] ? ok() : transientFailure());
        for (int i = 0; i < 4; i++) {
            invoker.invoke(p, request(Duration.ofSeconds(1)));
        }
        assertThat(invoker.state("fake")).isEqualTo(CircuitState.OPEN);
        healthy[0] = true;
        await().atMost(Duration.ofSeconds(5)).until(() -> invoker.state("fake") == CircuitState.HALF_OPEN);
        assertThat(registry.get(MessagingMetrics.CIRCUIT_STATE).tag("provider", "fake").gauge().value()).isEqualTo(1.0);
        // two permitted probe calls (configured) succeed: closed again
        assertThat(invoker.invoke(p, request(Duration.ofSeconds(1))).isAccepted()).isTrue();
        assertThat(invoker.invoke(p, request(Duration.ofSeconds(1))).isAccepted()).isTrue();
        assertThat(invoker.state("fake")).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void aFailingProbeSendsTheCircuitStraightBackToOpen() {
        invoker(4, 8, Duration.ofMillis(200), 2, 4);
        FakeProvider p = new FakeProvider(n -> transientFailure());
        for (int i = 0; i < 4; i++) {
            invoker.invoke(p, request(Duration.ofSeconds(1)));
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> invoker.state("fake") == CircuitState.HALF_OPEN);
        invoker.invoke(p, request(Duration.ofSeconds(1)));
        invoker.invoke(p, request(Duration.ofSeconds(1)));
        assertThat(invoker.state("fake")).isEqualTo(CircuitState.OPEN);
    }

    @Test
    void permanentRejectionsAreRequestProblemsAndNeverOpenTheCircuit() {
        invoker(4, 8, Duration.ofSeconds(30), 2, 4);
        FakeProvider p = new FakeProvider(n -> ProviderResult.failure(ProviderOutcome.PERMANENT_FAILURE, "BAD_NUMBER", "no", 1));
        for (int i = 0; i < 20; i++) {
            assertThat(invoker.invoke(p, request(Duration.ofSeconds(1))).outcome())
                    .isEqualTo(ProviderOutcome.PERMANENT_FAILURE);
        }
        assertThat(invoker.state("fake")).isEqualTo(CircuitState.CLOSED);
        assertThat(p.calls.get()).isEqualTo(20);
    }

    @Test
    void timeoutsAndAmbiguousAnswersCountAsProviderIllHealth() {
        invoker(4, 8, Duration.ofSeconds(30), 2, 4);
        FakeProvider p = new FakeProvider(n -> ProviderResult.failure(ProviderOutcome.AMBIGUOUS, "LOST", "?", 1));
        for (int i = 0; i < 4; i++) {
            invoker.invoke(p, request(Duration.ofSeconds(1)));
        }
        assertThat(invoker.state("fake")).isEqualTo(CircuitState.OPEN);
    }

    @Test
    void aSaturatedExecutorRejectsFastInsteadOfQueueingForever() throws Exception {
        invoker(100, 200, Duration.ofSeconds(30), 1, 1);
        CountDownLatch release = new CountDownLatch(1);
        FakeProvider slow = new FakeProvider(n -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ok();
        });
        // one running + one queued fill the executor; their callers give up after the timeout
        Thread a = new Thread(() -> invoker.invoke(slow, request(Duration.ofSeconds(3))));
        Thread b = new Thread(() -> invoker.invoke(slow, request(Duration.ofSeconds(3))));
        a.start();
        await().atMost(Duration.ofSeconds(2)).until(() -> slow.calls.get() == 1);
        b.start();
        Thread.sleep(150);
        long start = System.nanoTime();
        ProviderResult rejected = invoker.invoke(slow, request(Duration.ofSeconds(3)));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1000);
        assertThat(rejected.outcome()).isEqualTo(ProviderOutcome.TRANSIENT_FAILURE);
        assertThat(rejected.errorCode()).isEqualTo("BULKHEAD_FULL");
        release.countDown();
        a.join(5000);
        b.join(5000);
    }

    @Test
    void lookupHasItsOwnTimeoutAndReportsFailureInsteadOfThrowing() {
        invoker(10, 20, Duration.ofSeconds(5), 2, 4);
        FakeProvider p = new FakeProvider(n -> ok());
        p.lookupAnswer = Optional.of(new ProviderStatus(ProviderStatus.State.DELIVERED, "pm-1", null));
        var found = invoker.lookup(p, "m:1", Duration.ofSeconds(1));
        assertThat(found.failed()).isFalse();
        assertThat(found.status()).isPresent();
        p.lookupAnswer = Optional.empty(); // provider without lookup capability
        var none = invoker.lookup(p, "m:1", Duration.ofSeconds(1));
        assertThat(none.failed()).isFalse();
        assertThat(none.status()).isEmpty();

        SmsProvider hung = new FakeProvider(n -> ok()) {
            @Override
            public Optional<ProviderStatus> lookup(String idempotencyRef) {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Optional.empty();
            }
        };
        assertThat(invoker.lookup(hung, "m:1", Duration.ofMillis(150)).failed()).isTrue();
        SmsProvider broken = new FakeProvider(n -> ok()) {
            @Override
            public Optional<ProviderStatus> lookup(String idempotencyRef) {
                throw new IllegalStateException("nope");
            }
        };
        assertThat(invoker.lookup(broken, "m:1", Duration.ofSeconds(1)).failed()).isTrue();
    }

    @Test
    void aReservedPermitIsUsedByTheCallAndAnUnusedOneIsGivenBackWithoutCountingAsACall() {
        invoker(100, 200, Duration.ofSeconds(30), 2, 4);
        FakeProvider p = new FakeProvider(n -> ok());
        var permit = invoker.acquire("fake").orElseThrow();
        assertThat(invoker.invoke(p, request(Duration.ofSeconds(1)), permit).isAccepted()).isTrue();
        assertThat(p.calls.get()).isEqualTo(1);
        var unused = invoker.acquire("fake").orElseThrow();
        invoker.release(unused); // never called: not a success and not a failure
        invoker.release(null);   // tolerated
        assertThat(registry.get(MessagingMetrics.PROVIDER_CALLS).tag("provider", "fake").counter().count()).isEqualTo(1.0);
    }

    @Test
    void halfOpenProbesAreReservedOneByOneAndAReleasedProbeCanBeReused() {
        invoker(4, 8, Duration.ofMillis(200), 2, 4); // two half-open probes
        FakeProvider p = new FakeProvider(n -> transientFailure());
        for (int i = 0; i < 4; i++) {
            invoker.invoke(p, request(Duration.ofSeconds(1)));
        }
        assertThat(invoker.acquire("fake")).as("open circuit refuses reservations").isEmpty();
        await().atMost(Duration.ofSeconds(5)).until(() -> invoker.state("fake") == CircuitState.HALF_OPEN);
        var first = invoker.acquire("fake").orElseThrow();
        var second = invoker.acquire("fake").orElseThrow();
        assertThat(invoker.acquire("fake")).as("only two probes are allowed").isEmpty();
        invoker.release(second);
        assertThat(invoker.acquire("fake")).as("a released probe is available again").isPresent();
        invoker.release(first);
    }

    @Test
    void resetAndOpenHelpersControlTheBreaker() {
        invoker(100, 200, Duration.ofSeconds(30), 2, 4);
        FakeProvider p = new FakeProvider(n -> transientFailure());
        invoker.invoke(p, request(Duration.ofSeconds(1)));
        assertThat(invoker.lastError("fake")).isPresent();
        invoker.open("fake");
        assertThat(invoker.isOpen("fake")).isTrue();
        assertThat(invoker.invoke(p, request(Duration.ofSeconds(1))).errorCode())
                .isEqualTo(ResilientProviderInvoker.CIRCUIT_OPEN);
        invoker.reset("fake");
        assertThat(invoker.state("fake")).isEqualTo(CircuitState.CLOSED);
        assertThat(invoker.lastError("fake")).isEmpty();
        assertThat(invoker.state("never-used")).isEqualTo(CircuitState.CLOSED); // unknown providers are closed
    }
}
