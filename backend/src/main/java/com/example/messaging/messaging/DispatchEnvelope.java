package com.example.messaging.messaging;

import com.example.messaging.domain.Channel;
import com.example.messaging.domain.Priority;
import java.time.Instant;

/**
 * What travels on the bus: identifiers only, never message content or PII.
 * {@code dispatchId} is {@code messageId:attemptNo}, unique per (message, attempt) and the consumer dedupe key.
 */
public record DispatchEnvelope(
        String dispatchId,
        String messageId,
        String tenantId,
        Channel channel,
        Priority priority,
        int attemptNo,
        String correlationId,
        Instant enqueuedAt) {

    public static DispatchEnvelope of(String messageId, String tenantId, Channel channel, Priority priority,
            int attemptNo, String correlationId, Instant enqueuedAt) {
        return new DispatchEnvelope(messageId + ":" + attemptNo, messageId, tenantId, channel, priority, attemptNo,
                correlationId, enqueuedAt);
    }
}
