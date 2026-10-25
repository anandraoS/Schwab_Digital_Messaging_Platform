package com.example.messaging.messaging;

import com.example.messaging.config.MessagingProperties;
import com.example.messaging.config.PipelineConfig.BackpressureLimits;
import com.example.messaging.config.PipelineProperties;
import com.example.messaging.domain.DlqReason;
import com.example.messaging.domain.Priority;
import com.example.messaging.messaging.RabbitTopology.Tier;
import com.example.messaging.metrics.MessagingMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ {@link MessageBus} (profile {@code rabbit}, ADR-0001). Topology: see {@link RabbitTopology} and
 * {@code RabbitTopologyConfig}. Semantics identical to the in-memory bus:
 * <ul>
 *   <li>publish: persistent message with priority (0-9) to {@code messaging.dispatch}, waiting for the broker's
 *       publisher confirm; a refused publish or a down broker becomes {@link BusRejectedException};</li>
 *   <li>delayed delivery / requeue: publish to {@code messaging.retry} with the routing key of the smallest TTL tier
 *       that covers the delay, the tier queue dead-letters back to the dispatch queue; delays longer than the longest
 *       tier carry an {@code x-not-before} header and are re-delayed on arrival;</li>
 *   <li>consumers: manual acks, bounded prefetch, {@code messaging.dispatch.concurrency} consumer threads. A handler
 *       decision is applied by republishing first and acknowledging afterwards, so a crash in between duplicates
 *       (consumer dedupe) but never loses;</li>
 *   <li>poison: handler exceptions are counted in a header; at {@code poison-threshold} the envelope goes to
 *       {@code messaging.dlx} (DLQ) and {@link DispatchHandler#onPoison} is called; unreadable bodies go straight to the
 *       DLQ;</li>
 *   <li>graceful stop: the listener container stops consuming and waits up to the shutdown timeout; unacknowledged
 *       deliveries return to the queue when their channel closes.</li>
 * </ul>
 * NOT executed in this repository's build (no broker available); the pure parts are unit-tested in
 * {@code RabbitBusSupportTest} and the broker behaviour is covered by {@code RabbitMessageBusContainerIT} (profile
 * {@code containers}, requires Docker).
 */
@Component
@Profile("rabbit")
public class RabbitMessageBus implements MessageBus, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RabbitMessageBus.class);
    private static final long STATS_CACHE_MS = 1_000;

    private final RabbitTemplate template;
    private final ConnectionFactory connectionFactory;
    private final RabbitAdmin admin;
    private final MessagingProperties props;
    private final PipelineProperties pipeline;
    private final BackpressureLimits limits;
    private final MessagingMetrics metrics;
    private final ObjectMapper json;
    private final Clock clock;

    private volatile DispatchHandler handler;
    private volatile SimpleMessageListenerContainer container;
    private volatile boolean running;
    private volatile boolean paused;
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile QueueStats cachedStats;
    private volatile long cachedStatsAt;

    public RabbitMessageBus(RabbitTemplate template, ConnectionFactory connectionFactory, RabbitAdmin admin,
            MessagingProperties props, PipelineProperties pipeline, BackpressureLimits limits,
            MessagingMetrics metrics, ObjectMapper json, Clock clock) {
        this.template = template;
        this.connectionFactory = connectionFactory;
        this.admin = admin;
        this.props = props;
        this.pipeline = pipeline;
        this.limits = limits;
        this.metrics = metrics;
        this.json = json;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ publishing

    @Override
    public void publish(DispatchEnvelope envelope) {
        long depth = readyDepth();
        if (depth >= limits.maxQueueDepth()) {
            throw new BusRejectedException("Dispatch queue is full (" + depth + " waiting); retry later",
                    Math.min(30, 1 + depth / Math.max(1, props.dispatch().concurrency() * 50L)));
        }
        send(RabbitTopology.EXCHANGE_DISPATCH, RabbitTopology.ROUTING_DISPATCH,
                RabbitBusSupport.message(envelope, json, 1, 0, null, null));
    }

    @Override
    public void publishDelayed(DispatchEnvelope envelope, Duration delay) {
        republishDelayed(envelope, delay, 1, 0);
    }

    private void republishDelayed(DispatchEnvelope envelope, Duration delay, int attempt, int failures) {
        Tier tier = RabbitBusSupport.tierFor(delay);
        var notBefore = RabbitBusSupport.needsNotBefore(delay) ? clock.instant().plus(delay) : null;
        send(RabbitTopology.EXCHANGE_RETRY, tier.routingKey(),
                RabbitBusSupport.message(envelope, json, attempt, failures, notBefore, null));
    }

    private void deadLetter(DispatchEnvelope envelope, int attempt, int failures, DlqReason reason) {
        send(RabbitTopology.EXCHANGE_DLX, RabbitTopology.ROUTING_DEAD,
                RabbitBusSupport.message(envelope, json, attempt, failures, null, reason));
        metrics.deadLetter(reason);
    }

    /** Publishes and waits for the broker's confirm. */
    private void send(String exchange, String routingKey, Message message) {
        try {
            template.invoke(ops -> {
                ops.send(exchange, routingKey, message);
                ops.waitForConfirmsOrDie(5_000);
                return null;
            });
        } catch (RuntimeException e) {
            throw new BusRejectedException("Message broker did not accept the publish (" + e.getClass().getSimpleName()
                    + ")", 5);
        }
    }

    // ------------------------------------------------------------------ consuming

    @Override
    public void subscribe(DispatchHandler handler) {
        this.handler = handler;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        if (!paused) {
            startContainer();
        }
    }

    private void startContainer() {
        int n = Math.max(1, props.dispatch().concurrency());
        SimpleMessageListenerContainer c = new SimpleMessageListenerContainer(connectionFactory);
        c.setQueueNames(RabbitTopology.QUEUE_DISPATCH);
        c.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        c.setPrefetchCount(Math.max(1, props.dispatch().prefetch()));
        c.setConcurrentConsumers(n);
        c.setMaxConcurrentConsumers(n);
        c.setShutdownTimeout(pipeline.shutdownTimeout().toMillis());
        c.setDefaultRequeueRejected(true);
        c.setMessageListener((ChannelAwareMessageListener) this::onMessage);
        c.afterPropertiesSet();
        c.start();
        this.container = c;
        log.info("RabbitMQ consumers started: concurrency={}, prefetch={}", n, props.dispatch().prefetch());
    }

    private void stopContainer() {
        SimpleMessageListenerContainer c = container;
        if (c != null) {
            c.stop(); // waits for in-flight handlers up to the shutdown timeout
            c.destroy();
            container = null;
        }
    }

    private void onMessage(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        DispatchHandler h = handler;
        DispatchEnvelope env;
        try {
            env = RabbitBusSupport.read(message.getBody(), json);
        } catch (RuntimeException e) {
            log.warn("Unreadable dispatch message: routing to DLQ as poison");
            rawToDlq(message);
            channel.basicAck(tag, false);
            return;
        }
        int attempt = RabbitBusSupport.intHeader(message.getMessageProperties(), RabbitBusSupport.H_ATTEMPT, 1);
        int failures = RabbitBusSupport.intHeader(message.getMessageProperties(), RabbitBusSupport.H_FAILURES, 0);
        Duration wait = RabbitBusSupport.remaining(message.getMessageProperties(), clock.instant());
        try {
            if (!wait.isZero()) { // delay longer than the longest tier: wait it out in another hop
                republishDelayed(env, wait, attempt, failures);
                channel.basicAck(tag, false);
                return;
            }
            if (h == null) {
                channel.basicNack(tag, false, true);
                return;
            }
            inFlight.incrementAndGet();
            try {
                apply(h, env, attempt, failures, channel, tag);
            } finally {
                inFlight.decrementAndGet();
            }
        } catch (BusRejectedException e) {
            channel.basicNack(tag, false, true); // could not republish: leave it on the queue
        }
    }

    private void apply(DispatchHandler h, DispatchEnvelope env, int attempt, int failures, Channel channel, long tag)
            throws IOException {
        BusDecision decision;
        try {
            decision = h.handle(env, attempt);
        } catch (RuntimeException e) {
            int f = failures + 1;
            log.warn("Handler failed for dispatch {} (failure {}/{}): {}", env.dispatchId(), f,
                    props.dispatch().poisonThreshold(), e.getClass().getSimpleName());
            if (RabbitBusSupport.isPoison(f, props.dispatch().poisonThreshold())) {
                deadLetter(env, attempt, f, DlqReason.POISON_MESSAGE);
                try {
                    h.onPoison(env, attempt, e);
                } catch (RuntimeException ex) {
                    log.warn("onPoison callback failed for dispatch {}", env.dispatchId());
                }
            } else {
                republishDelayed(env, Duration.ofSeconds(1), attempt + 1, f);
            }
            channel.basicAck(tag, false);
            return;
        }
        if (decision == null || decision.type() == BusDecision.Type.ACK) {
            channel.basicAck(tag, false);
        } else if (decision.type() == BusDecision.Type.REQUEUE) {
            republishDelayed(env, decision.delay(), attempt + 1, failures);
            channel.basicAck(tag, false);
        } else {
            deadLetter(env, attempt, failures, decision.reason() == null ? DlqReason.POISON_MESSAGE : decision.reason());
            channel.basicAck(tag, false);
        }
    }

    private void rawToDlq(Message message) {
        message.getMessageProperties().setHeader(RabbitBusSupport.H_DLQ_REASON, DlqReason.POISON_MESSAGE.name());
        send(RabbitTopology.EXCHANGE_DLX, RabbitTopology.ROUTING_DEAD, message);
        metrics.deadLetter(DlqReason.POISON_MESSAGE);
    }

    // ------------------------------------------------------------------ control and stats

    @Override
    public synchronized void pause() {
        paused = true;
        if (running) {
            stopContainer();
        }
    }

    @Override
    public synchronized void resume() {
        paused = false;
        if (running && container == null) {
            startContainer();
        }
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public synchronized void stop() {
        running = false;
        stopContainer();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return false; // started by DispatchWiring
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }

    private long readyDepth() {
        return stats().totalDepth();
    }

    /**
     * RabbitMQ only reports the total ready count of a queue, not a per-priority breakdown: the total is reported under
     * {@code NORMAL} (documented deviation). Values are cached for one second; on broker errors the last snapshot is
     * returned.
     */
    @Override
    public QueueStats stats() {
        long now = System.currentTimeMillis();
        QueueStats cached = cachedStats;
        if (cached != null && now - cachedStatsAt < STATS_CACHE_MS) {
            return cached;
        }
        try {
            QueueInformation dispatch = admin.getQueueInfo(RabbitTopology.QUEUE_DISPATCH);
            QueueInformation dlq = admin.getQueueInfo(RabbitTopology.QUEUE_DLQ);
            long delayed = 0;
            for (Tier t : RabbitTopology.TIERS) {
                QueueInformation q = admin.getQueueInfo(t.queue());
                delayed += q == null ? 0 : q.getMessageCount();
            }
            Map<Priority, Long> depth = new EnumMap<>(Priority.class);
            for (Priority p : Priority.values()) {
                depth.put(p, 0L);
            }
            depth.put(Priority.NORMAL, dispatch == null ? 0L : (long) dispatch.getMessageCount());
            cached = new QueueStats(depth, inFlight.get(), dispatch == null ? 0 : dispatch.getConsumerCount(),
                    dlq == null ? 0 : dlq.getMessageCount(), delayed, null, paused);
        } catch (RuntimeException e) {
            log.warn("Could not read RabbitMQ queue statistics: {}", e.getClass().getSimpleName());
            if (cached == null) {
                Map<Priority, Long> depth = new EnumMap<>(Priority.class);
                for (Priority p : Priority.values()) {
                    depth.put(p, 0L);
                }
                cached = new QueueStats(depth, inFlight.get(), 0, 0, 0, null, paused);
            }
        }
        cachedStats = cached;
        cachedStatsAt = now;
        return cached;
    }
}
