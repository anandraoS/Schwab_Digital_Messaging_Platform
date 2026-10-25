package com.example.messaging.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.messaging.config.RabbitTopologyConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

/** The topology the application declares must be exactly the one the infrastructure definitions describe. */
class RabbitTopologyTest {

    private final JsonNode definitions = read();

    private static JsonNode read() {
        try {
            return new ObjectMapper().readTree(Files.readString(
                    Path.of("..", "infrastructure", "rabbitmq", "definitions.json")));
        } catch (IOException e) {
            throw new IllegalStateException("infrastructure/rabbitmq/definitions.json must exist", e);
        }
    }

    private final List<Declarable> declared = RabbitTopologyConfig.declarables();

    @Test
    void exchangesMatchTheDefinitionsFile() {
        Set<String> expected = new HashSet<>();
        definitions.get("exchanges").forEach(e -> {
            assertThat(e.get("type").asText()).isEqualTo("direct");
            assertThat(e.get("durable").asBoolean()).isTrue();
            expected.add(e.get("name").asText());
        });
        Set<String> actual = new HashSet<>();
        declared.stream().filter(DirectExchange.class::isInstance).map(DirectExchange.class::cast).forEach(e -> {
            assertThat(e.isDurable()).isTrue();
            assertThat(e.isAutoDelete()).isFalse();
            actual.add(e.getName());
        });
        assertThat(actual).isEqualTo(expected)
                .containsExactlyInAnyOrder(RabbitTopology.EXCHANGE_DISPATCH, RabbitTopology.EXCHANGE_RETRY,
                        RabbitTopology.EXCHANGE_DLX);
    }

    @Test
    void queuesAndTheirArgumentsMatchTheDefinitionsFile() {
        Map<String, Map<String, Object>> expected = new HashMap<>();
        definitions.get("queues").forEach(q -> {
            Map<String, Object> args = new HashMap<>();
            q.get("arguments").fields().forEachRemaining(a -> args.put(a.getKey(),
                    a.getValue().isNumber() ? (Object) a.getValue().asLong() : a.getValue().asText()));
            assertThat(q.get("durable").asBoolean()).isTrue();
            expected.put(q.get("name").asText(), args);
        });
        Map<String, Map<String, Object>> actual = new HashMap<>();
        declared.stream().filter(Queue.class::isInstance).map(Queue.class::cast).forEach(q -> {
            assertThat(q.isDurable()).isTrue();
            Map<String, Object> args = new HashMap<>();
            q.getArguments().forEach((k, v) -> args.put(k, v instanceof Number n ? (Object) n.longValue() : v));
            actual.put(q.getName(), args);
        });
        assertThat(actual.keySet()).isEqualTo(expected.keySet());
        expected.forEach((name, args) -> assertThat(actual.get(name)).as("arguments of %s", name).isEqualTo(args));
    }

    @Test
    void dispatchQueueIsAPriorityQueueThatDeadLettersToTheDlx() {
        Queue q = declared.stream().filter(Queue.class::isInstance).map(Queue.class::cast)
                .filter(x -> x.getName().equals(RabbitTopology.QUEUE_DISPATCH)).findFirst().orElseThrow();
        assertThat(q.getArguments()).containsEntry("x-max-priority", 9)
                .containsEntry("x-dead-letter-exchange", "messaging.dlx")
                .containsEntry("x-dead-letter-routing-key", "dead");
    }

    @Test
    void retryTiersExpireBackIntoTheDispatchExchange() {
        for (RabbitTopology.Tier t : RabbitTopology.TIERS) {
            Queue q = declared.stream().filter(Queue.class::isInstance).map(Queue.class::cast)
                    .filter(x -> x.getName().equals(t.queue())).findFirst().orElseThrow();
            assertThat(((Number) q.getArguments().get("x-message-ttl")).longValue()).isEqualTo(t.ttlMs());
            assertThat(q.getArguments()).containsEntry("x-dead-letter-exchange", "messaging.dispatch")
                    .containsEntry("x-dead-letter-routing-key", "dispatch");
        }
        assertThat(RabbitTopology.TIERS).extracting(RabbitTopology.Tier::ttlMs).isSorted();
    }

    @Test
    void bindingsMatchTheDefinitionsFile() {
        Set<String> expected = new HashSet<>();
        definitions.get("bindings").forEach(b -> expected.add(b.get("source").asText() + "->" + b.get("destination")
                .asText() + "#" + b.get("routing_key").asText()));
        Set<String> actual = new HashSet<>();
        declared.stream().filter(Binding.class::isInstance).map(Binding.class::cast)
                .forEach(b -> actual.add(b.getExchange() + "->" + b.getDestination() + "#" + b.getRoutingKey()));
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void thePolicyBackedLimitsOfTheInfrastructureAreDocumentedNextToTheApplicationLimit() {
        // the broker rejects publishes beyond max-length (reject-publish), which the bus surfaces as BusRejectedException
        JsonNode policy = null;
        for (JsonNode p : definitions.get("policies")) {
            if (p.get("name").asText().equals("dispatch-backpressure")) {
                policy = p;
            }
        }
        assertThat(policy).isNotNull();
        assertThat(policy.get("definition").get("overflow").asText()).isEqualTo("reject-publish");
        assertThat(policy.get("definition").get("max-length").asLong()).isGreaterThan(0);
    }
}
