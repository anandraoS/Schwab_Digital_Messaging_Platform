package com.example.messaging.provider;

import com.example.messaging.config.PipelineProperties;
import com.example.messaging.metrics.MessagingMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * The single choke point for provider calls (ADR-0002): a Resilience4j circuit breaker per provider plus a hard
 * timeout enforced through a bounded executor, so a hung provider can never pin a consumer thread. Adapters never
 * retry (retry belongs to {@code RetryPolicy}); an open circuit short-circuits to a transient result without
 * touching the provider.
 */
@Component
public class ResilientProviderInvoker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ResilientProviderInvoker.class);
    public static final String CIRCUIT_OPEN = "CIRCUIT_OPEN";

    public enum CircuitState {
        CLOSED(0), HALF_OPEN(1), OPEN(2);

        private final int code;

        CircuitState(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    /** Outcome of a reconciliation lookup. {@code failed} means the lookup itself did not complete. */
    public record LookupResult(Optional<ProviderStatus> status, boolean failed) {}

    private static final class CallFailure extends RuntimeException {
        CallFailure(String message) {
            super(message, null, false, false);
        }
    }

    private final PipelineProperties.Resilience cfg;
    private final MessagingMetrics metrics;
    private final CircuitBreakerRegistry breakers;
    private final ThreadPoolExecutor executor;
    private final ConcurrentHashMap<String, CircuitBreaker> byProvider = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lastError = new ConcurrentHashMap<>();

    public ResilientProviderInvoker(PipelineProperties pipeline, MessagingMetrics metrics) {
        this.cfg = pipeline.resilience();
        this.metrics = metrics;
        this.breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(cfg.failureRateThreshold())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cfg.slidingWindowSize())
                .minimumNumberOfCalls(cfg.minimumCalls())
                .waitDurationInOpenState(cfg.openStateWait())
                .permittedNumberOfCallsInHalfOpenState(cfg.halfOpenCalls())
                // callers consult state() before calling, so the breaker must leave OPEN by itself
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build());
        AtomicInteger n = new AtomicInteger();
        int threads = Math.max(1, cfg.executorThreads());
        this.executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, cfg.executorQueue())), r -> {
                    Thread t = new Thread(r, "provider-call-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    private CircuitBreaker breaker(String providerId) {
        return byProvider.computeIfAbsent(providerId, id -> {
            CircuitBreaker cb = breakers.circuitBreaker(id);
            metrics.bindCircuitState(id, () -> state(id).code());
            return cb;
        });
    }

    public CircuitState state(String providerId) {
        CircuitBreaker cb = byProvider.get(providerId);
        if (cb == null) {
            return CircuitState.CLOSED;
        }
        return switch (cb.getState()) {
            case OPEN, FORCED_OPEN -> CircuitState.OPEN;
            case HALF_OPEN -> CircuitState.HALF_OPEN;
            default -> CircuitState.CLOSED;
        };
    }

    public boolean isOpen(String providerId) {
        return state(providerId) == CircuitState.OPEN;
    }

    /** Last non-success outcome as {@code OUTCOME:CODE} (never contains message content). */
    public Optional<String> lastError(String providerId) {
        return Optional.ofNullable(lastError.get(providerId));
    }

    /** Ops/test helper: forces the circuit open (it half-opens again after the configured wait). */
    public void open(String providerId) {
        breaker(providerId).transitionToOpenState();
    }

    /** Test/ops helper: closes the circuit and forgets the last error. */
    public void reset(String providerId) {
        CircuitBreaker cb = byProvider.get(providerId);
        if (cb != null) {
            cb.reset();
        }
        lastError.remove(providerId);
    }

    /**
     * A reserved right to make one call through the circuit breaker. Callers that must not consume an attempt when the
     * circuit refuses (the dispatch consumer) reserve first, do their bookkeeping, then {@link #invoke(ChannelProvider,
     * ProviderRequest, Permit)} or {@link #release(Permit)} it when they end up not calling.
     */
    public record Permit(String providerId) {}

    /** Reserves a call; empty when the circuit is open or the half-open probes are all in use. */
    public Optional<Permit> acquire(String providerId) {
        return breaker(providerId).tryAcquirePermission() ? Optional.of(new Permit(providerId)) : Optional.empty();
    }

    /** Gives an unused reservation back (the circuit breaker does not count it as a call). */
    public void release(Permit permit) {
        if (permit != null) {
            breaker(permit.providerId()).releasePermission();
        }
    }

    /** Calls the provider with circuit breaker and hard timeout. Never throws. */
    public ProviderResult invoke(ChannelProvider provider, ProviderRequest request) {
        Optional<Permit> permit = acquire(provider.providerId());
        if (permit.isEmpty()) {
            return finish(provider, request, ProviderResult.failure(ProviderOutcome.TRANSIENT_FAILURE, CIRCUIT_OPEN,
                    "circuit breaker is open", 0), 0, false);
        }
        return invoke(provider, request, permit.get());
    }

    /** As {@link #invoke(ChannelProvider, ProviderRequest)} with a reservation made earlier. Never throws. */
    public ProviderResult invoke(ChannelProvider provider, ProviderRequest request, Permit permit) {
        String id = provider.providerId();
        CircuitBreaker cb = breaker(id);
        long start = System.nanoTime();
        Duration timeout = request.timeout() == null ? Duration.ofSeconds(2) : request.timeout();
        Future<ProviderResult> future;
        try {
            future = executor.submit(() -> provider.send(request));
        } catch (RejectedExecutionException e) {
            cb.releasePermission();
            return finish(provider, request, ProviderResult.failure(ProviderOutcome.TRANSIENT_FAILURE, "BULKHEAD_FULL",
                    "provider call executor is saturated", 0), 0, false);
        }
        ProviderResult result;
        try {
            result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (result == null) {
                result = ProviderResult.failure(ProviderOutcome.AMBIGUOUS, "NULL_RESULT", "provider returned nothing", 0);
            }
        } catch (TimeoutException e) {
            future.cancel(true);
            result = ProviderResult.failure(ProviderOutcome.TIMEOUT, "PROVIDER_TIMEOUT",
                    "no answer within " + timeout.toMillis() + " ms", timeout.toMillis());
        } catch (ExecutionException e) {
            // adapters must not throw; if one does we cannot know whether the vendor accepted: stay conservative
            result = ProviderResult.failure(ProviderOutcome.AMBIGUOUS, "PROVIDER_ERROR",
                    e.getCause() == null ? "provider failed" : e.getCause().getClass().getSimpleName(), 0);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            result = ProviderResult.failure(ProviderOutcome.AMBIGUOUS, "INTERRUPTED", "caller interrupted", 0);
        }
        long elapsedNanos = System.nanoTime() - start;
        return finish(provider, request, result, elapsedNanos, true);
    }

    private ProviderResult finish(ChannelProvider provider, ProviderRequest request, ProviderResult raw,
            long elapsedNanos, boolean recordInBreaker) {
        String id = provider.providerId();
        long ms = elapsedNanos / 1_000_000;
        ProviderResult result = new ProviderResult(raw.outcome(), raw.providerMessageId(), raw.errorCode(),
                raw.errorMessage(), recordInBreaker ? Math.max(ms, 0) : raw.latencyMs());
        if (recordInBreaker) {
            CircuitBreaker cb = breaker(id);
            // a permanent rejection is a request problem, not provider ill-health
            if (result.outcome() == ProviderOutcome.ACCEPTED || result.outcome() == ProviderOutcome.PERMANENT_FAILURE) {
                cb.onSuccess(elapsedNanos, TimeUnit.NANOSECONDS);
            } else {
                cb.onError(elapsedNanos, TimeUnit.NANOSECONDS, new CallFailure(result.outcome().name()));
            }
            metrics.providerLatency(id, Duration.ofNanos(Math.max(elapsedNanos, 1)));
        }
        metrics.providerCall(id, request.channel(), result.outcome());
        if (result.outcome() != ProviderOutcome.ACCEPTED) {
            lastError.put(id, result.outcome() + (result.errorCode() == null ? "" : ":" + result.errorCode()));
            log.debug("Provider {} answered {} ({})", id, result.outcome(), result.errorCode());
        }
        return result;
    }

    /** Reconciliation lookup with the same hard timeout; a failed or timed-out lookup is reported, not thrown. */
    public LookupResult lookup(ChannelProvider provider, String idempotencyRef, Duration timeout) {
        Future<Optional<ProviderStatus>> future;
        try {
            future = executor.submit(() -> provider.lookup(idempotencyRef));
        } catch (RejectedExecutionException e) {
            return new LookupResult(Optional.empty(), true);
        }
        try {
            Optional<ProviderStatus> s = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new LookupResult(s == null ? Optional.empty() : s, false);
        } catch (TimeoutException | ExecutionException e) {
            future.cancel(true);
            return new LookupResult(Optional.empty(), true);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return new LookupResult(Optional.empty(), true);
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
