package com.example.messaging.messaging;

import java.util.List;

/**
 * Names and parameters of the RabbitMQ topology. Single source of truth for {@code RabbitTopologyConfig} and
 * {@code RabbitMessageBus}; a test asserts that it matches {@code infrastructure/rabbitmq/definitions.json}.
 */
public final class RabbitTopology {

    public static final String EXCHANGE_DISPATCH = "messaging.dispatch";
    public static final String EXCHANGE_RETRY = "messaging.retry";
    public static final String EXCHANGE_DLX = "messaging.dlx";

    public static final String QUEUE_DISPATCH = "messaging.dispatch.q";
    public static final String QUEUE_DLQ = "messaging.dlq";

    public static final String ROUTING_DISPATCH = "dispatch";
    public static final String ROUTING_DEAD = "dead";

    /** RabbitMQ {@code x-max-priority}; {@code Priority#weight()} is already within 0..9. */
    public static final int MAX_PRIORITY = 9;

    /** A retry queue: messages wait {@code ttlMs} there and are then dead-lettered back to the dispatch exchange. */
    public record Tier(String queue, String routingKey, long ttlMs) {}

    /** Delay tiers, ascending. Broker-side TTL queues cannot delay by arbitrary amounts, hence tiers. */
    public static final List<Tier> TIERS = List.of(
            new Tier("messaging.retry.q.5s", "retry.5s", 5_000L),
            new Tier("messaging.retry.q.30s", "retry.30s", 30_000L),
            new Tier("messaging.retry.q.2m", "retry.2m", 120_000L),
            new Tier("messaging.retry.q.10m", "retry.10m", 600_000L));

    private RabbitTopology() {}
}
