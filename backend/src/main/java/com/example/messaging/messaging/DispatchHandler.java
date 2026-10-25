package com.example.messaging.messaging;

/**
 * Consumer callback registered with {@link MessageBus#subscribe}. Implemented by stage 2 ({@code DispatchConsumer}).
 * If the handler throws, the bus treats the delivery as a nack with requeue; after the configured poison threshold the
 * bus dead-letters it with {@code DlqReason.POISON_MESSAGE}.
 */
@FunctionalInterface
public interface DispatchHandler {

    /**
     * @param deliveryCount 1 for the first delivery of this envelope, incremented on each redelivery
     */
    BusDecision handle(DispatchEnvelope envelope, int deliveryCount);

    /**
     * Called by the bus after IT dead-lettered an envelope on its own (handler kept throwing up to the poison
     * threshold, or the envelope was unreadable). Lets the consumer mirror the dead-lettering in the database.
     * Never called for dead-letters the handler itself requested through {@link BusDecision#deadLetter}.
     * Implementations must not throw.
     */
    default void onPoison(DispatchEnvelope envelope, int deliveryCount, Throwable cause) {
    }
}
