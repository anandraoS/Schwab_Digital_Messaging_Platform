package com.example.messaging.pipeline;

import com.example.messaging.messaging.DispatchEnvelope;
import com.example.messaging.messaging.DispatchHook;
import com.example.messaging.provider.ProviderResult;
import com.example.messaging.coordination.CoordinationService;
import com.example.messaging.coordination.InMemoryCoordination;
import com.example.messaging.coordination.RateLimitDecision;
import com.example.messaging.coordination.ResilientCoordination;
import com.example.messaging.metrics.MessagingMetrics;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Test-only beans for the pipeline integration tests: an adjustable clock and a programmable dispatch hook. */
@TestConfiguration
public class PipelineTestConfig {

    /** System time plus an adjustable offset: time keeps flowing, tests can jump forward (lock TTLs, reconcile-after). */
    public static final class AdjustableClock extends Clock {
        private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

        public void advance(Duration d) {
            offset.updateAndGet(o -> o.plus(d));
        }

        public void reset() {
            offset.set(Duration.ZERO);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(offset.get());
        }
    }

    /** Programmable fault points in the dispatch consumer; every rule is keyed by message id. */
    public static final class ProgrammableHook implements DispatchHook {
        /** Messages whose worker "dies" right after the provider call (before the outcome is persisted). */
        public final Set<String> crashAfterProviderCall = ConcurrentHashMap.newKeySet();
        /** Messages whose processing throws before anything happens, with the number of remaining throws. */
        public final Map<String, AtomicInteger> failBeforeProcess = new ConcurrentHashMap<>();
        /** Number of times the hook was reached before processing, per message id. */
        public final Map<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();

        /** Thrown to simulate sudden worker death; the consumer must not handle it. */
        public static final class SimulatedWorkerDeath extends RuntimeException {
            public SimulatedWorkerDeath() {
                super("simulated worker death", null, false, false);
            }
        }

        @Override
        public void beforeProcess(DispatchEnvelope envelope, int deliveryCount) {
            deliveries.computeIfAbsent(envelope.messageId(), k -> new AtomicInteger()).incrementAndGet();
            AtomicInteger left = failBeforeProcess.get(envelope.messageId());
            if (left != null && left.getAndDecrement() > 0) {
                throw new IllegalStateException("injected processing failure");
            }
        }

        @Override
        public void afterProviderCall(DispatchEnvelope envelope, ProviderResult result) {
            if (crashAfterProviderCall.remove(envelope.messageId())) {
                throw new SimulatedWorkerDeath();
            }
        }

        public void reset() {
            crashAfterProviderCall.clear();
            failBeforeProcess.clear();
            deliveries.clear();
        }
    }

    /** Switch for the simulated database outage (see {@link #dataSourceFaults()}). */
    public static final class DbOutage {
        public static volatile boolean down;

        private DbOutage() {}
    }

    /** Switch for the simulated Redis outage (see {@link #coordination}). */
    public static final class RedisOutage {
        public static volatile boolean down;

        private RedisOutage() {}
    }

    /** Wraps every {@link DataSource} so that getConnection fails while {@link DbOutage#down} is set. */
    @Bean
    public static BeanPostProcessor dataSourceFaults() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof DataSource ds) || Proxy.isProxyClass(bean.getClass())) {
                    return bean;
                }
                return Proxy.newProxyInstance(bean.getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                        (proxy, method, args) -> {
                            if (DbOutage.down && method.getName().equals("getConnection")) {
                                throw new SQLTransientConnectionException("simulated database outage");
                            }
                            try {
                                return method.invoke(ds, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
            }
        };
    }

    /** In-memory coordination that throws like a dead Redis while {@link RedisOutage#down} is set. */
    static final class SwitchableCoordination implements CoordinationService {
        private final CoordinationService delegate;

        SwitchableCoordination(CoordinationService delegate) {
            this.delegate = delegate;
        }

        private void check() {
            if (RedisOutage.down) {
                throw new IllegalStateException("simulated Redis outage");
            }
        }

        @Override
        public String name() {
            return "switchable";
        }

        @Override
        public Optional<String> getCached(String key) {
            check();
            return delegate.getCached(key);
        }

        @Override
        public void putCached(String key, String value, Duration ttl) {
            check();
            delegate.putCached(key, value, ttl);
        }

        @Override
        public RateLimitDecision tryAcquire(String bucketKey, int ratePerSec, int burst) {
            check();
            return delegate.tryAcquire(bucketKey, ratePerSec, burst);
        }
    }

    /** The production resilience decorator around a "Redis" that can be switched off. */
    @Bean
    @Primary
    public CoordinationService coordination(MessagingMetrics metrics, Clock clock) {
        return new ResilientCoordination(new SwitchableCoordination(new InMemoryCoordination(clock)),
                new InMemoryCoordination(clock), metrics, clock, Duration.ofMillis(300));
    }

    @Bean
    @Primary
    public AdjustableClock testClock() {
        return new AdjustableClock();
    }

    @Bean
    public ProgrammableHook programmableHook() {
        return new ProgrammableHook();
    }
}
