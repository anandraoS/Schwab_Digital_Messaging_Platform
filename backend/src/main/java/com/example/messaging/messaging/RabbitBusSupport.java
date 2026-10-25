package com.example.messaging.messaging;

import com.example.messaging.domain.DlqReason;
import com.example.messaging.messaging.RabbitTopology.Tier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

/**
 * The pure, broker-independent parts of the RabbitMQ bus: delay-tier selection, message headers, envelope
 * (de)serialisation and the poison decision. Unit-testable without a broker.
 */
public final class RabbitBusSupport {

    /** 1-based delivery attempt of this envelope (incremented by the bus on every republish). */
    public static final String H_ATTEMPT = "x-attempt";
    /** Consecutive handler failures of this envelope. */
    public static final String H_FAILURES = "x-failures";
    /** Epoch millis before which the envelope must not be handled (delays beyond the longest tier). */
    public static final String H_NOT_BEFORE = "x-not-before";
    public static final String H_DLQ_REASON = "x-dlq-reason";
    public static final String H_DISPATCH_ID = "x-dispatch-id";

    private RabbitBusSupport() {}

    /** The smallest tier whose TTL is at least {@code delay}; the longest tier for anything longer. */
    public static Tier tierFor(Duration delay) {
        long ms = delay == null ? 0 : Math.max(0, delay.toMillis());
        for (Tier t : RabbitTopology.TIERS) {
            if (ms <= t.ttlMs()) {
                return t;
            }
        }
        return RabbitTopology.TIERS.get(RabbitTopology.TIERS.size() - 1);
    }

    /** Portion of {@code delay} that the chosen tier cannot cover (to be waited out via {@link #H_NOT_BEFORE}). */
    public static boolean needsNotBefore(Duration delay) {
        return delay != null && delay.toMillis() > RabbitTopology.TIERS.get(RabbitTopology.TIERS.size() - 1).ttlMs();
    }

    public static byte[] body(DispatchEnvelope envelope, ObjectMapper json) {
        try {
            return json.writeValueAsBytes(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise dispatch envelope", e);
        }
    }

    /** @throws IllegalArgumentException when the body is not a valid envelope (poison) */
    public static DispatchEnvelope read(byte[] body, ObjectMapper json) {
        try {
            DispatchEnvelope e = json.readValue(body, DispatchEnvelope.class);
            if (e == null || e.messageId() == null || e.dispatchId() == null || e.attemptNo() < 1) {
                throw new IllegalArgumentException("incomplete dispatch envelope");
            }
            return e;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("unreadable dispatch envelope", e);
        }
    }

    /** Builds the AMQP message for an envelope: persistent, priority from {@code Priority#weight()}, headers. */
    public static Message message(DispatchEnvelope envelope, ObjectMapper json, int attempt, int failures,
            Instant notBefore, DlqReason dlqReason) {
        MessageProperties p = new MessageProperties();
        p.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        p.setContentEncoding("UTF-8");
        p.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        p.setPriority(Math.min(RabbitTopology.MAX_PRIORITY, Math.max(0, envelope.priority().weight())));
        p.setMessageId(envelope.dispatchId());
        p.setCorrelationId(envelope.correlationId());
        p.setHeader(H_DISPATCH_ID, envelope.dispatchId());
        p.setHeader(H_ATTEMPT, attempt);
        p.setHeader(H_FAILURES, failures);
        if (notBefore != null) {
            p.setHeader(H_NOT_BEFORE, notBefore.toEpochMilli());
        }
        if (dlqReason != null) {
            p.setHeader(H_DLQ_REASON, dlqReason.name());
        }
        return new Message(body(envelope, json), p);
    }

    public static int intHeader(MessageProperties p, String name, int fallback) {
        Object v = p.getHeaders().get(name);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return fallback;
    }

    /** How long the envelope still has to wait according to {@link #H_NOT_BEFORE}; zero when due. */
    public static Duration remaining(MessageProperties p, Instant now) {
        Object v = p.getHeaders().get(H_NOT_BEFORE);
        if (v instanceof Number n) {
            long ms = n.longValue() - now.toEpochMilli();
            return ms > 0 ? Duration.ofMillis(ms) : Duration.ZERO;
        }
        return Duration.ZERO;
    }

    /** True when the failure count has reached the poison threshold. */
    public static boolean isPoison(int failures, int threshold) {
        return failures >= Math.max(1, threshold);
    }
}
