package com.example.messaging.messaging;

import com.example.messaging.config.MessagingProperties;
import com.example.messaging.config.PipelineConfig.BackpressureLimits;
import com.example.messaging.domain.OutboxEvent;
import com.example.messaging.repository.OutboxEventRepository;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * API-side backpressure (ADR-0009 point 6, CONTRACT 6): when the dispatch backlog (envelopes waiting on the bus plus
 * outbox rows that are due but not yet published) reaches {@code messaging.backpressure.max-queue-depth}, new
 * submissions are refused with {@link BusRejectedException} (HTTP 503 + {@code Retry-After}) instead of growing the
 * backlog without bound. Clients retry with the same idempotency key.
 */
@Component
public class BackpressureGuard {

    private final MessageBus bus;
    private final OutboxEventRepository outbox;
    private final BackpressureLimits limits;
    private final MessagingProperties props;
    private final Clock clock;

    public BackpressureGuard(MessageBus bus, OutboxEventRepository outbox, BackpressureLimits limits,
            MessagingProperties props, Clock clock) {
        this.bus = bus;
        this.outbox = outbox;
        this.limits = limits;
        this.props = props;
        this.clock = clock;
    }

    /** @throws BusRejectedException when the backlog is at or above the limit */
    public void check() {
        long backlog = bus.stats().totalDepth()
                + outbox.countByStatusAndAvailableAtLessThanEqual(OutboxEvent.NEW, clock.instant());
        if (backlog >= limits.maxQueueDepth()) {
            long retryAfter = Math.min(30, 1 + backlog / Math.max(1, props.dispatch().concurrency() * 50L));
            throw new BusRejectedException("Dispatch backlog is full (" + backlog + " waiting); retry later",
                    retryAfter);
        }
    }
}
