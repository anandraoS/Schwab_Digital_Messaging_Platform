package com.example.messaging.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.messaging.config.SimulatorProperties;
import com.example.messaging.domain.Channel;
import com.example.messaging.provider.simulator.AcmeSmsProvider;
import com.example.messaging.provider.simulator.CallbackEmitter;
import com.example.messaging.provider.simulator.FaultInjector;
import com.example.messaging.provider.simulator.FaultInjector.Mode;
import com.example.messaging.provider.simulator.SimulatedEmailProvider;
import com.example.messaging.provider.simulator.SimulatedProviderBase;
import com.example.messaging.provider.simulator.SimulatedPushProvider;
import com.example.messaging.provider.simulator.SimulatedSmsProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Adapter conformance: every outcome of the {@link ChannelProvider} contract, provider-side dedupe and lookup. */
class SimulatedProviderTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"), ZoneOffset.UTC);
    private final FaultInjector faults = new FaultInjector(7L);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<CallbackEmitter> noEmitter = mock(ObjectProvider.class);

    private SimulatorProperties props(boolean auto, long sleepMs) {
        return new SimulatorProperties(auto, Duration.ofMillis(10), 7L, sleepMs, "x");
    }

    private SimulatedEmailProvider email() {
        return new SimulatedEmailProvider(faults, props(false, 0), clock, noEmitter);
    }

    private ProviderRequest request(Channel channel, String ref, String to) {
        return new ProviderRequest("msg-1", ref, "tenant-retail", channel, to, "Subject", "Body", false, Map.of(),
                "corr", Duration.ofMillis(200));
    }

    @Test
    void successIsAcceptedWithAProviderMessageIdAndLookupShowsIt() {
        SimulatedEmailProvider p = email();
        ProviderResult r = p.send(request(Channel.EMAIL, "msg-1:1", "a@example.test"));
        assertThat(r.outcome()).isEqualTo(ProviderOutcome.ACCEPTED);
        assertThat(r.providerMessageId()).startsWith("em-");
        assertThat(r.isAccepted()).isTrue();
        assertThat(r.errorCode()).isNull();
        assertThat(p.callCount()).isEqualTo(1);
        ProviderStatus s = p.lookup("msg-1:1").orElseThrow();
        assertThat(s.state()).isEqualTo(ProviderStatus.State.ACCEPTED);
        assertThat(s.providerMessageId()).isEqualTo(r.providerMessageId());
        assertThat(s.at()).isEqualTo(clock.instant());
    }

    @Test
    void lookupOfAReferenceTheProviderNeverSawSaysNotReceived() {
        SimulatedEmailProvider p = email();
        ProviderStatus s = p.lookup("never-sent:1").orElseThrow();
        assertThat(s.state()).isEqualTo(ProviderStatus.State.NOT_RECEIVED);
        assertThat(s.providerMessageId()).isNull();
    }

    @Test
    void theProviderDeduplicatesOnTheIdempotencyReferenceAndAnswersWithTheSameId() {
        SimulatedSmsProvider p = new SimulatedSmsProvider(faults, props(false, 0), clock, noEmitter);
        ProviderResult first = p.send(request(Channel.SMS, "msg-1:1", "+15550100001"));
        ProviderResult second = p.send(request(Channel.SMS, "msg-1:1", "+15550100001"));
        ProviderResult other = p.send(request(Channel.SMS, "msg-2:1", "+15550100001"));
        assertThat(second.providerMessageId()).isEqualTo(first.providerMessageId());
        assertThat(other.providerMessageId()).isNotEqualTo(first.providerMessageId());
        assertThat(p.callCount()).isEqualTo(3);
        assertThat(p.acceptedCount()).isEqualTo(2);
        assertThat(p.dedupeHits()).isEqualTo(1);
        // dedupe takes precedence over fault injection: a retry of an accepted message is never "failed"
        faults.set("simulated-sms", Mode.PERMANENT, 1.0);
        assertThat(p.send(request(Channel.SMS, "msg-1:1", "+15550100001")).outcome()).isEqualTo(ProviderOutcome.ACCEPTED);
    }

    @Test
    void concurrentDuplicateSendsCreateOneProviderMessage() throws Exception {
        SimulatedPushProvider p = new SimulatedPushProvider(faults, props(false, 0), clock, noEmitter);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        java.util.List<Future<ProviderResult>> results = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return p.send(request(Channel.PUSH, "msg-1:1", "token"));
            }));
        }
        go.countDown();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Future<ProviderResult> f : results) {
            ids.add(f.get().providerMessageId());
        }
        pool.shutdown();
        assertThat(ids).hasSize(1);
        assertThat(p.acceptedCount()).isEqualTo(1);
    }

    @Test
    void transientPermanentAndRateLimitedFailuresCarryTheirOutcomeAndLeaveNoTrace() {
        SimulatedEmailProvider p = email();
        faults.set("simulated-email", Mode.TRANSIENT, 1.0);
        ProviderResult t = p.send(request(Channel.EMAIL, "m:1", "a@example.test"));
        assertThat(t.outcome()).isEqualTo(ProviderOutcome.TRANSIENT_FAILURE);
        assertThat(t.errorCode()).isEqualTo("SIM_UNAVAILABLE");
        assertThat(t.providerMessageId()).isNull();
        faults.set("simulated-email", Mode.PERMANENT, 1.0);
        assertThat(p.send(request(Channel.EMAIL, "m:1", "a@example.test")).outcome())
                .isEqualTo(ProviderOutcome.PERMANENT_FAILURE);
        faults.set("simulated-email", Mode.RATE_LIMIT, 1.0);
        assertThat(p.send(request(Channel.EMAIL, "m:1", "a@example.test")).outcome()).isEqualTo(ProviderOutcome.RATE_LIMITED);
        assertThat(p.acceptedCount()).isZero();
        assertThat(p.lookup("m:1").orElseThrow().state()).isEqualTo(ProviderStatus.State.NOT_RECEIVED);
        assertThat(ProviderOutcome.TRANSIENT_FAILURE.isRetryable()).isTrue();
        assertThat(ProviderOutcome.RATE_LIMITED.isRetryable()).isTrue();
        assertThat(ProviderOutcome.PERMANENT_FAILURE.isRetryable()).isFalse();
    }

    @Test
    void ambiguousMeansTheProviderAcceptedButTheAnswerIsLost() {
        SimulatedEmailProvider p = email();
        faults.set("simulated-email", Mode.AMBIGUOUS, 1.0);
        ProviderResult r = p.send(request(Channel.EMAIL, "m:1", "a@example.test"));
        assertThat(r.outcome()).isEqualTo(ProviderOutcome.AMBIGUOUS);
        assertThat(r.outcome().isUncertain()).isTrue();
        assertThat(r.providerMessageId()).isNull(); // the response was lost
        assertThat(p.acceptedCount()).isEqualTo(1);
        assertThat(p.lookup("m:1").orElseThrow().state()).isEqualTo(ProviderStatus.State.ACCEPTED);
        // a retry after the outage is deduplicated by the provider
        faults.reset();
        ProviderResult retry = p.send(request(Channel.EMAIL, "m:1", "a@example.test"));
        assertThat(retry.outcome()).isEqualTo(ProviderOutcome.ACCEPTED);
        assertThat(retry.providerMessageId()).isEqualTo(p.lookup("m:1").orElseThrow().providerMessageId());
        assertThat(p.acceptedCount()).isEqualTo(1);
    }

    @Test
    void timeoutSleepsBeyondTheRequestTimeoutAndNeverAcceptsAnything() {
        SimulatedSmsProvider p = new SimulatedSmsProvider(faults, props(false, 250), clock, noEmitter);
        faults.set("simulated-sms", Mode.TIMEOUT, 1.0);
        long start = System.nanoTime();
        ProviderResult r = p.send(request(Channel.SMS, "m:1", "+15550100001"));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(r.outcome()).isEqualTo(ProviderOutcome.TIMEOUT);
        assertThat(ms).isGreaterThanOrEqualTo(240);
        assertThat(p.acceptedCount()).isZero();
        assertThat(p.lookup("m:1").orElseThrow().state()).isEqualTo(ProviderStatus.State.NOT_RECEIVED);
    }

    @Test
    void withoutAnExplicitSleepTheTimeoutModeSleepsRequestTimeoutPlusAMargin() {
        SimulatedSmsProvider p = new SimulatedSmsProvider(faults, props(false, 0), clock, noEmitter);
        faults.set("simulated-sms", Mode.TIMEOUT, 1.0);
        ProviderRequest r = new ProviderRequest("m", "m:1", "t", Channel.SMS, "+1", null, "b", false, Map.of(), "c",
                Duration.ofMillis(100));
        long start = System.nanoTime();
        p.send(r);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isGreaterThanOrEqualTo(590);
    }

    @Test
    void anInterruptedTimeoutReturnsPromptlyAndKeepsTheInterruptFlag() throws Exception {
        SimulatedSmsProvider p = new SimulatedSmsProvider(faults, props(false, 30_000), clock, noEmitter);
        faults.set("simulated-sms", Mode.TIMEOUT, 1.0);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Boolean> f = pool.submit(() -> {
            ProviderResult r = p.send(request(Channel.SMS, "m:1", "+15550100001"));
            return r.outcome() == ProviderOutcome.TIMEOUT && Thread.currentThread().isInterrupted();
        });
        Thread.sleep(100);
        f.cancel(true);
        pool.shutdownNow();
        assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue(); // did not sleep the full 30 s
    }

    @Test
    void aSpecialAddressSimulatesAHardBounceRegardlessOfTheFaultMode() {
        SimulatedEmailProvider p = email();
        ProviderResult r = p.send(request(Channel.EMAIL, "m:1", "user+hardbounce@example.test"));
        assertThat(r.outcome()).isEqualTo(ProviderOutcome.PERMANENT_FAILURE);
        assertThat(r.errorCode()).isEqualTo("HARD_BOUNCE");
        assertThat(p.acceptedCount()).isZero();
    }

    @Test
    void simulatorStateCanBeSteeredAndResetForTests() {
        SimulatedEmailProvider p = email();
        p.send(request(Channel.EMAIL, "m:1", "a@example.test"));
        p.setState("m:1", ProviderStatus.State.DELIVERED);
        assertThat(p.lookup("m:1").orElseThrow().state()).isEqualTo(ProviderStatus.State.DELIVERED);
        p.setState("unknown:1", ProviderStatus.State.FAILED); // no-op for unknown references
        assertThat(p.find("unknown:1")).isEmpty();
        assertThat(p.lastRequest("m:1")).isPresent();
        assertThat(p.lastRequest("m:1").orElseThrow().body()).isEqualTo("Body");
        p.resetSimulator();
        assertThat(p.callCount()).isZero();
        assertThat(p.acceptedCount()).isZero();
        assertThat(p.lastRequest("m:1")).isEmpty();
    }

    @Test
    void autoCallbacksAreScheduledOnlyWhenEnabledAndAnEmitterExists() {
        CallbackEmitter emitter = mock(CallbackEmitter.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<CallbackEmitter> present = mock(ObjectProvider.class);
        when(present.getIfAvailable()).thenReturn(emitter);
        SimulatedEmailProvider on = new SimulatedEmailProvider(faults, props(true, 0), clock, present);
        ProviderResult r = on.send(request(Channel.EMAIL, "m:1", "a@example.test"));
        verify(emitter).scheduleAutomatic(eq("simulated-email"), eq("m:1"), eq(r.providerMessageId()), any(Duration.class));

        CallbackEmitter silent = mock(CallbackEmitter.class);
        when(present.getIfAvailable()).thenReturn(silent);
        new SimulatedEmailProvider(faults, props(false, 0), clock, present)
                .send(request(Channel.EMAIL, "m:2", "a@example.test"));
        verify(silent, never()).scheduleAutomatic(any(), any(), any(), any());
        // enabled but no emitter bean (prod profiles): nothing happens and nothing fails
        when(present.getIfAvailable()).thenReturn(null);
        assertThat(new SimulatedEmailProvider(faults, props(true, 0), clock, present)
                .send(request(Channel.EMAIL, "m:3", "a@example.test")).isAccepted()).isTrue();
    }

    @Test
    void theFourSimulatorsDeclareTheirChannelAndIdAndAreOneKindOfAdapter() {
        SimulatedProviderBase[] all = {email(), new SimulatedSmsProvider(faults, props(false, 0), clock, noEmitter),
                new SimulatedPushProvider(faults, props(false, 0), clock, noEmitter),
                new AcmeSmsProvider(faults, props(false, 0), clock, noEmitter)};
        assertThat(all[0].providerId()).isEqualTo("simulated-email");
        assertThat(all[0].channel()).isEqualTo(Channel.EMAIL);
        assertThat(all[1].channel()).isEqualTo(Channel.SMS);
        assertThat(all[2].channel()).isEqualTo(Channel.PUSH);
        assertThat(all[3].providerId()).isEqualTo("acme-sms");
        assertThat(all[3].channel()).isEqualTo(Channel.SMS);
        assertThat(all[3].send(request(Channel.SMS, "m:1", "+1")).providerMessageId()).startsWith("acme-");
        assertThat(all[0]).isInstanceOf(EmailProvider.class);
        assertThat(all[1]).isInstanceOf(SmsProvider.class);
        assertThat(all[2]).isInstanceOf(PushNotificationProvider.class);
    }

    @Test
    void theRequestNeverPrintsAddressesOrContent() {
        ProviderRequest r = request(Channel.EMAIL, "m:1", "secret.person@example.test");
        assertThat(r.toString()).doesNotContain("secret.person").doesNotContain("Body").contains("m:1");
    }
}
