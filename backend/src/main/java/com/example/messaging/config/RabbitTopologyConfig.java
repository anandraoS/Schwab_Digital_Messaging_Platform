package com.example.messaging.config;

import com.example.messaging.messaging.RabbitTopology;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * RabbitMQ topology, identical to {@code infrastructure/rabbitmq/definitions.json}: direct exchanges
 * {@code messaging.dispatch / retry / dlx}; the priority queue {@code messaging.dispatch.q}
 * ({@code x-max-priority=9}, dead-letters to the DLX with key {@code dead}); {@code messaging.dlq}; and the TTL retry
 * queues (5 s, 30 s, 2 m, 10 m) that dead-letter back to the dispatch exchange once the delay elapsed. Declared
 * idempotently by Spring's {@code RabbitAdmin} when the connection opens. Profile {@code rabbit} only.
 */
@Configuration
@Profile("rabbit")
public class RabbitTopologyConfig {

    @Bean
    public Declarables messagingTopology() {
        return new Declarables(declarables());
    }

    /** Public so tests (and tooling) can inspect or declare the topology without a Spring context. */
    public static List<Declarable> declarables() {
        DirectExchange dispatch = new DirectExchange(RabbitTopology.EXCHANGE_DISPATCH, true, false);
        DirectExchange retry = new DirectExchange(RabbitTopology.EXCHANGE_RETRY, true, false);
        DirectExchange dlx = new DirectExchange(RabbitTopology.EXCHANGE_DLX, true, false);

        Queue dispatchQueue = QueueBuilder.durable(RabbitTopology.QUEUE_DISPATCH)
                .withArgument("x-queue-type", "classic")
                .withArgument("x-max-priority", RabbitTopology.MAX_PRIORITY)
                .deadLetterExchange(RabbitTopology.EXCHANGE_DLX)
                .deadLetterRoutingKey(RabbitTopology.ROUTING_DEAD)
                .build();
        Queue dlq = QueueBuilder.durable(RabbitTopology.QUEUE_DLQ).withArgument("x-queue-type", "classic").build();

        List<Declarable> all = new ArrayList<>(List.of(dispatch, retry, dlx, dispatchQueue, dlq,
                BindingBuilder.bind(dispatchQueue).to(dispatch).with(RabbitTopology.ROUTING_DISPATCH),
                BindingBuilder.bind(dlq).to(dlx).with(RabbitTopology.ROUTING_DEAD)));
        for (RabbitTopology.Tier tier : RabbitTopology.TIERS) {
            Queue q = QueueBuilder.durable(tier.queue())
                    .withArgument("x-queue-type", "classic")
                    .ttl((int) tier.ttlMs())
                    .deadLetterExchange(RabbitTopology.EXCHANGE_DISPATCH)
                    .deadLetterRoutingKey(RabbitTopology.ROUTING_DISPATCH)
                    .build();
            Binding b = BindingBuilder.bind(q).to(retry).with(tier.routingKey());
            all.add(q);
            all.add(b);
        }
        return all;
    }
}
