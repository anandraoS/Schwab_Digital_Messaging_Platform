package com.example.messaging.provider.simulator;

import com.example.messaging.config.SimulatorProperties;
import com.example.messaging.provider.ChannelProvider;
import com.example.messaging.provider.ProviderOutcome;
import com.example.messaging.provider.ProviderRequest;
import com.example.messaging.provider.ProviderResult;
import com.example.messaging.provider.ProviderStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Behaviour shared by all simulated providers: provider-side de-duplication by {@code idempotencyRef}, fault
 * injection, an in-memory record of what was "accepted" (so {@link #lookup} works for reconciliation) and optional
 * signed callbacks. No real network or provider is ever involved.
 */
public abstract class SimulatedProviderBase implements ChannelProvider {

    /** What the simulated vendor remembers about an accepted message. */
    public record Stored(String providerMessageId, String idempotencyRef, ProviderStatus.State state, Instant at) {}

    private static final int MAX_REMEMBERED = 50_000;
    private static final int MAX_REQUESTS = 1_000;

    protected final FaultInjector faults;
    protected final SimulatorProperties props;
    protected final Clock clock;
    private final ObjectProvider<CallbackEmitter> emitter;

    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong dedupeHits = new AtomicLong();
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, Stored> byRef = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Stored> eldest) {
            return size() > MAX_REMEMBERED;
        }
    };

    /** Last request seen per idempotency reference (synthetic content, kept in memory only, for tests and dev tools). */
    private final Map<String, ProviderRequest> requests = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ProviderRequest> eldest) {
            return size() > MAX_REQUESTS;
        }
    };

    protected SimulatedProviderBase(FaultInjector faults, SimulatorProperties props, Clock clock,
            ObjectProvider<CallbackEmitter> emitter) {
        this.faults = faults;
        this.props = props;
        this.clock = clock;
        this.emitter = emitter;
    }

    /** Prefix of the provider message ids this simulator hands out, e.g. {@code em-}. */
    protected abstract String idPrefix();

    @Override
    public ProviderResult send(ProviderRequest request) {
        long start = System.nanoTime();
        calls.incrementAndGet();
        String ref = request.idempotencyRef();
        synchronized (requests) {
            requests.put(ref, request);
        }
        // a compliant provider de-duplicates on the idempotency reference before anything else
        synchronized (byRef) {
            Stored existing = ref == null ? null : byRef.get(ref);
            if (existing != null && existing.state() != ProviderStatus.State.FAILED) {
                dedupeHits.incrementAndGet();
                return ProviderResult.accepted(existing.providerMessageId(), elapsedMs(start));
            }
        }
        if (request.to() != null && request.to().contains("+hardbounce@")) {
            return ProviderResult.failure(ProviderOutcome.PERMANENT_FAILURE, "HARD_BOUNCE",
                    "mailbox does not exist", elapsedMs(start));
        }
        switch (faults.next(providerId())) {
            case TRANSIENT:
                return ProviderResult.failure(ProviderOutcome.TRANSIENT_FAILURE, "SIM_UNAVAILABLE",
                        "simulated 5xx before acceptance", elapsedMs(start));
            case PERMANENT:
                return ProviderResult.failure(ProviderOutcome.PERMANENT_FAILURE, "SIM_REJECTED",
                        "simulated permanent rejection", elapsedMs(start));
            case RATE_LIMIT:
                return ProviderResult.failure(ProviderOutcome.RATE_LIMITED, "SIM_RATE_LIMIT",
                        "simulated 429", elapsedMs(start));
            case TIMEOUT:
                sleepBeyondTimeout(request);
                return ProviderResult.failure(ProviderOutcome.TIMEOUT, "SIM_TIMEOUT",
                        "simulated slow provider", elapsedMs(start));
            case AMBIGUOUS:
                accept(ref); // the vendor did accept it, but the response is "lost"
                return ProviderResult.failure(ProviderOutcome.AMBIGUOUS, "SIM_LOST_RESPONSE",
                        "accepted but response lost", elapsedMs(start));
            default:
                String id = accept(ref);
                return ProviderResult.accepted(id, elapsedMs(start));
        }
    }

    private void sleepBeyondTimeout(ProviderRequest request) {
        long ms = props.timeoutSleepMs() > 0 ? props.timeoutSleepMs()
                : (request.timeout() == null ? Duration.ofSeconds(2) : request.timeout()).toMillis() + 500;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String accept(String ref) {
        String id = idPrefix() + Long.toString(System.currentTimeMillis(), 36) + "-" + sequence.incrementAndGet();
        synchronized (byRef) {
            Stored existing = byRef.get(ref);
            if (existing != null && existing.state() != ProviderStatus.State.FAILED) {
                dedupeHits.incrementAndGet(); // concurrent duplicate that raced past the first check
                return existing.providerMessageId();
            }
            byRef.put(ref, new Stored(id, ref, ProviderStatus.State.ACCEPTED, clock.instant()));
        }
        CallbackEmitter e = emitter.getIfAvailable();
        if (props.autoCallbacks() && e != null) {
            e.scheduleAutomatic(providerId(), ref, id, props.callbackDelay());
        }
        return id;
    }

    @Override
    public Optional<ProviderStatus> lookup(String idempotencyRef) {
        Stored s;
        synchronized (byRef) {
            s = byRef.get(idempotencyRef);
        }
        if (s == null) {
            return Optional.of(new ProviderStatus(ProviderStatus.State.NOT_RECEIVED, null, null));
        }
        return Optional.of(new ProviderStatus(s.state(), s.providerMessageId(), s.at()));
    }

    // ------------------------------------------------------------------ simulator helpers (tests, dev tools)

    public long callCount() {
        return calls.get();
    }

    public long dedupeHits() {
        return dedupeHits.get();
    }

    public int acceptedCount() {
        synchronized (byRef) {
            return byRef.size();
        }
    }

    public Optional<Stored> find(String idempotencyRef) {
        synchronized (byRef) {
            return Optional.ofNullable(byRef.get(idempotencyRef));
        }
    }

    /** The most recent request received for an idempotency reference (never logged). */
    public Optional<ProviderRequest> lastRequest(String idempotencyRef) {
        synchronized (requests) {
            return Optional.ofNullable(requests.get(idempotencyRef));
        }
    }

    /** Lets a test/dev tool change what the vendor will answer to {@link #lookup} (e.g. after a callback). */
    public void setState(String idempotencyRef, ProviderStatus.State state) {
        synchronized (byRef) {
            Stored s = byRef.get(idempotencyRef);
            if (s != null) {
                byRef.put(idempotencyRef, new Stored(s.providerMessageId(), s.idempotencyRef(), state, clock.instant()));
            }
        }
    }

    /** Forgets everything (counters included). */
    public void resetSimulator() {
        synchronized (byRef) {
            byRef.clear();
        }
        synchronized (requests) {
            requests.clear();
        }
        calls.set(0);
        dedupeHits.set(0);
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }
}
