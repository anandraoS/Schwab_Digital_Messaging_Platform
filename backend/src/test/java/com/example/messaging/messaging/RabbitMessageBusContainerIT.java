package com.example.messaging.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.example.messaging.config.PipelineConfig.BackpressureLimits;
import com.example.messaging.config.RabbitTopologyConfig;
import com.example.messaging.domain.DlqReason;
import com.example.messaging.domain.Priority;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Broker-level behaviour of {@link RabbitMessageBus} against a real RabbitMQ (Testcontainers).
 *
 * <p><b>NOT EXECUTED in the repository's own build</b>: it needs a Docker daemon (none is available here), is
 * excluded from the default compilation and runs only with {@code -Pcontainers}
 * ({@code @Testcontainers(disabledWithoutDocker = true)} skips it when Docker is missing). The pure parts of the bus are
 * covered by {@code RabbitBusSupportTest} and {@code RabbitTopologyTest}, which do run.
 */
@Testcontainers(disabledWithoutDocker = true)
class RabbitMessageBusContainerIT {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private CachingConnectionFactory cf;
    private RabbitAdmin admin;
    private RabbitMessageBus bus;
    private BackpressureLimits limits;

    @BeforeEach
    void connect() {
        cf = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        cf.setUsername(RABBIT.getAdminUsername());
        cf.setPassword(RABBIT.getAdminPassword());
        cf.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        cf.setPublisherReturns(true);
        admin = new RabbitAdmin(cf);
        for (Declarable d : RabbitTopologyConfig.declarables()) {
            if (d instanceof Exchange e) {
                admin.declareExchange(e);
            } else if (d instanceof Queue q) {
                admin.declareQueue(q);
                admin.purgeQueue(q.getName(), false);
            } else if (d instanceof Binding b) {
                admin.declareBinding(b);
            }
        }
        RabbitTemplate template = new RabbitTemplate(cf);
        template.setMandatory(true);
        limits = new BackpressureLimits(1000);
        bus = new RabbitMessageBus(template, cf, admin, BusTestSupport.props(1, 3), BusTestSupport.pipeline(Duration.ofSeconds(5)),
                limits, BusTestSupport.metrics(), json, Clock.systemUTC());
    }

    @AfterEach
    void disconnect() {
        bus.stop();
        cf.destroy();
    }

    @Test
    void publishedEnvelopesAreDeliveredAndAcknowledged() {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe((env, n) -> {
            seen.add(env.messageId() + "#" + n);
            return BusDecision.ack();
        });
        bus.start();
        DispatchEnvelope env = BusTestSupport.envelope("rabbit-1", Priority.NORMAL, 1);
        bus.publish(env);
        await().atMost(Duration.ofSeconds(10)).until(() -> seen.size() == 1);
        assertThat(seen).containsExactly("rabbit-1#1");
        await().atMost(Duration.ofSeconds(5)).until(() -> bus.stats().totalDepth() == 0);
    }

    @Test
    void higherPriorityIsDeliveredFirstWhileTheConsumerIsBehind() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe((env, n) -> {
            order.add(env.messageId());
            return BusDecision.ack();
        });
        for (String id : List.of("low-1", "normal-1", "critical-1", "high-1")) {
            Priority p = id.startsWith("low") ? Priority.LOW : id.startsWith("normal") ? Priority.NORMAL
                    : id.startsWith("critical") ? Priority.CRITICAL : Priority.HIGH;
            bus.publish(BusTestSupport.envelope(id, p, 1));
        }
        bus.start(); // consumers start after everything is queued: the broker orders by priority
        await().atMost(Duration.ofSeconds(10)).until(() -> order.size() == 4);
        assertThat(order).containsExactly("critical-1", "high-1", "normal-1", "low-1");
    }

    @Test
    void aRequeueWithDelayComesBackThroughTheRetryTierNoEarlierThanTheTier() {
        List<Long> at = Collections.synchronizedList(new ArrayList<>());
        List<Integer> counts = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe((env, n) -> {
            at.add(System.nanoTime());
            counts.add(n);
            return n == 1 ? BusDecision.requeue(Duration.ofSeconds(1)) : BusDecision.ack();
        });
        bus.start();
        bus.publish(BusTestSupport.envelope("rabbit-delay", Priority.NORMAL, 1));
        await().atMost(Duration.ofSeconds(20)).until(() -> at.size() == 2);
        assertThat(counts).containsExactly(1, 2);
        // a 1 s request is rounded up to the 5 s tier
        assertThat(Duration.ofNanos(at.get(1) - at.get(0)).toMillis()).isGreaterThanOrEqualTo(4_500);
    }

    @Test
    void aDeadLetterDecisionEndsInTheDlq() {
        bus.subscribe((env, n) -> BusDecision.deadLetter(DlqReason.RETRIES_EXHAUSTED));
        bus.start();
        bus.publish(BusTestSupport.envelope("rabbit-dead", Priority.NORMAL, 1));
        await().atMost(Duration.ofSeconds(10)).until(() -> admin.getQueueInfo(RabbitTopology.QUEUE_DLQ).getMessageCount() == 1);
        Message m = new RabbitTemplate(cf).receive(RabbitTopology.QUEUE_DLQ, 5_000);
        assertThat(m).isNotNull();
        assertThat(m.getMessageProperties().getHeaders()).containsEntry(RabbitBusSupport.H_DLQ_REASON, "RETRIES_EXHAUSTED");
    }

    @Test
    void aHandlerThatKeepsThrowingIsDeadLetteredAfterThePoisonThreshold() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger poison = new AtomicInteger();
        bus.subscribe(new DispatchHandler() {
            @Override
            public BusDecision handle(DispatchEnvelope envelope, int deliveryCount) {
                calls.incrementAndGet();
                throw new IllegalStateException("boom");
            }

            @Override
            public void onPoison(DispatchEnvelope envelope, int deliveryCount, Throwable cause) {
                poison.incrementAndGet();
            }
        });
        bus.start();
        bus.publish(BusTestSupport.envelope("rabbit-poison", Priority.NORMAL, 1));
        await().atMost(Duration.ofSeconds(30)).until(() -> poison.get() == 1);
        assertThat(calls.get()).isEqualTo(3); // poison-threshold = 3
        assertThat(admin.getQueueInfo(RabbitTopology.QUEUE_DLQ).getMessageCount()).isEqualTo(1);
    }

    @Test
    void anUnreadableMessageGoesStraightToTheDlq() {
        bus.subscribe((env, n) -> BusDecision.ack());
        bus.start();
        new RabbitTemplate(cf).send(RabbitTopology.EXCHANGE_DISPATCH, RabbitTopology.ROUTING_DISPATCH,
                new Message("not json".getBytes(StandardCharsets.UTF_8), new MessageProperties()));
        await().atMost(Duration.ofSeconds(10)).until(() -> admin.getQueueInfo(RabbitTopology.QUEUE_DLQ).getMessageCount() == 1);
    }

    @Test
    void publishIsRefusedWhenTheQueueIsAtTheLimit() {
        limits.setMaxQueueDepth(1);
        bus.publish(BusTestSupport.envelope("full-1", Priority.NORMAL, 1)); // no consumer: stays ready
        await().atMost(Duration.ofSeconds(5)).until(() -> admin.getQueueInfo(RabbitTopology.QUEUE_DISPATCH).getMessageCount() == 1);
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            try {
                bus.publish(BusTestSupport.envelope("full-2", Priority.NORMAL, 1));
                return false;
            } catch (BusRejectedException e) {
                return true;
            }
        });
        assertThatThrownBy(() -> bus.publish(BusTestSupport.envelope("full-3", Priority.NORMAL, 1)))
                .isInstanceOf(BusRejectedException.class);
    }

    @Test
    void pauseStopsDeliveriesAndResumeContinues() {
        AtomicInteger seen = new AtomicInteger();
        bus.subscribe((env, n) -> {
            seen.incrementAndGet();
            return BusDecision.ack();
        });
        bus.start();
        bus.pause();
        bus.publish(BusTestSupport.envelope("paused-1", Priority.NORMAL, 1));
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).until(() -> seen.get() == 0);
        assertThat(bus.isPaused()).isTrue();
        bus.resume();
        await().atMost(Duration.ofSeconds(10)).until(() -> seen.get() == 1);
    }
}
