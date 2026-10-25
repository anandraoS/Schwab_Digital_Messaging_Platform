package com.example.messaging.messaging;

import java.time.Duration;

/**
 * Port to the asynchronous transport (ADR-0001). Stage 2 supplies {@code InMemoryMessageBus} (profiles local/test) and
 * {@code RabbitMessageBus} (profile rabbit). Semantics: at-least-once delivery; the handler's {@link BusDecision}
 * drives ack / requeue-with-delay / dead-letter; higher {@link com.example.messaging.domain.Priority} weights are
 * delivered first.
 */
public interface MessageBus {

    /** @throws BusRejectedException when the backpressure threshold is reached or the transport is down */
    void publish(DispatchEnvelope envelope);

    /** Redelivers the envelope no earlier than {@code delay} from now (retry backoff, rate-limit requeue). */
    void publishDelayed(DispatchEnvelope envelope, Duration delay);

    /** Registers the single consumer callback; consumers start on {@link #start()}. */
    void subscribe(DispatchHandler handler);

    QueueStats stats();

    /** Stops handing deliveries to workers (in-flight work finishes); publishing still works. */
    void pause();

    void resume();

    boolean isPaused();

    /** Starts consumer workers. Idempotent. */
    void start();

    /** Graceful stop: no new deliveries, waits for in-flight work, unacked deliveries return to the queue. */
    void stop();

    boolean isRunning();
}
