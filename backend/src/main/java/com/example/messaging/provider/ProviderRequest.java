package com.example.messaging.provider;

import com.example.messaging.domain.Channel;
import java.time.Duration;
import java.util.Map;

/**
 * Everything a provider needs for one send. {@code idempotencyRef} ({@code messageId:attemptGroup}) lets a compliant
 * provider de-duplicate a retried call. {@code body}/{@code to} are PII: never log them (see
 * {@link com.example.messaging.common.Redaction}).
 */
public record ProviderRequest(
        String messageId,
        String idempotencyRef,
        String tenantId,
        Channel channel,
        String to,
        String subject,
        String body,
        boolean html,
        Map<String, String> options,
        String correlationId,
        Duration timeout) {

    public ProviderRequest {
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    @Override
    public String toString() {
        return "ProviderRequest[messageId=" + messageId + ", idempotencyRef=" + idempotencyRef + ", channel=" + channel
                + ", to=***, body=***]";
    }
}
