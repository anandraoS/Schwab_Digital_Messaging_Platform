package com.example.messaging.provider.simulator;

import com.example.messaging.config.SimulatorProperties;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Shared fault source for every simulated provider: one {@link Mode} per provider id with an optional probability.
 * Deterministic when a seed is configured ({@code messaging.simulator.seed}); tests reset it after each case.
 */
@Component
public class FaultInjector {

    /** Configurable modes (the REST API exposes exactly these). */
    public enum Mode { NONE, TRANSIENT, PERMANENT, RATE_LIMIT, TIMEOUT, AMBIGUOUS, ALTERNATING }

    /** What one call actually experiences. */
    public enum Fault { NONE, TRANSIENT, PERMANENT, RATE_LIMIT, TIMEOUT, AMBIGUOUS }

    public record Setting(Mode mode, double probability) {}

    private static final Setting OFF = new Setting(Mode.NONE, 1.0);

    private final Map<String, Setting> settings = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Random random;

    @Autowired
    public FaultInjector(SimulatorProperties props) {
        this(props.seed());
    }

    public FaultInjector(Long seed) {
        this.random = seed == null ? new Random() : new Random(seed);
    }

    /**
     * @param probability chance in [0,1] that a call is faulted (ignored for {@link Mode#ALTERNATING}, which fails
     *                    every other call starting with the first)
     */
    public void set(String providerId, Mode mode, double probability) {
        if (probability < 0 || probability > 1 || Double.isNaN(probability)) {
            throw new IllegalArgumentException("probability must be within [0,1]");
        }
        settings.put(providerId, new Setting(mode == null ? Mode.NONE : mode, probability));
        counters.remove(providerId);
    }

    public Setting get(String providerId) {
        return settings.getOrDefault(providerId, OFF);
    }

    public void reset() {
        settings.clear();
        counters.clear();
    }

    /** Decides the fault for the next call of {@code providerId}. */
    public Fault next(String providerId) {
        Setting s = get(providerId);
        switch (s.mode()) {
            case NONE:
                return Fault.NONE;
            case ALTERNATING:
                long n = counters.computeIfAbsent(providerId, k -> new AtomicLong()).incrementAndGet();
                return n % 2 == 1 ? Fault.TRANSIENT : Fault.NONE;
            default:
                boolean hit;
                synchronized (random) {
                    hit = random.nextDouble() < s.probability();
                }
                return hit ? Fault.valueOf(s.mode().name()) : Fault.NONE;
        }
    }
}
