package com.example.messaging.messaging;

import com.example.messaging.config.PipelineProperties;
import com.example.messaging.metrics.MessagingMetrics;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Connects the {@link DispatchConsumer} to the {@link MessageBus}, binds the queue gauges and (unless
 * {@code messaging.pipeline.auto-start=false}) starts the bus workers once the application context is up. The bus
 * itself stops gracefully through its own {@link SmartLifecycle} phase.
 */
@Component
public class DispatchWiring implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DispatchWiring.class);

    private final MessageBus bus;
    private final DispatchConsumer consumer;
    private final PipelineProperties pipeline;
    private final MessagingMetrics metrics;
    private volatile boolean running;

    public DispatchWiring(MessageBus bus, DispatchConsumer consumer, PipelineProperties pipeline,
            MessagingMetrics metrics) {
        this.bus = bus;
        this.consumer = consumer;
        this.pipeline = pipeline;
        this.metrics = metrics;
    }

    @PostConstruct
    void bindGauges() {
        metrics.bindQueueStats(bus::stats);
        bus.subscribe(consumer); // subscribe early so tests that start the bus by hand work too
    }

    @Override
    public void start() {
        bus.subscribe(consumer);
        if (pipeline.autoStart()) {
            bus.start();
        } else {
            log.info("Dispatch consumers not started (messaging.pipeline.auto-start=false)");
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false; // the bus drains and stops in its own lifecycle phase
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 2000; // starts before everything that could publish, stops after the bus
    }
}
