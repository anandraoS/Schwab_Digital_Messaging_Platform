package com.example.messaging.messaging;

import com.example.messaging.domain.DlqReason;
import java.time.Duration;

/** What the {@link DispatchHandler} tells the bus to do with a delivery (ack / requeue-with-delay / DLQ). */
public record BusDecision(Type type, Duration delay, DlqReason reason) {

    public enum Type { ACK, REQUEUE, DEAD_LETTER }

    public static BusDecision ack() {
        return new BusDecision(Type.ACK, Duration.ZERO, null);
    }

    public static BusDecision requeue(Duration delay) {
        return new BusDecision(Type.REQUEUE, delay == null ? Duration.ZERO : delay, null);
    }

    public static BusDecision deadLetter(DlqReason reason) {
        return new BusDecision(Type.DEAD_LETTER, Duration.ZERO, reason);
    }
}
