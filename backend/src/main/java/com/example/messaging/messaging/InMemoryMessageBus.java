package com.example.messaging.messaging;

import com.example.messaging.config.MessagingProperties;
import com.example.messaging.config.PipelineConfig.BackpressureLimits;
import com.example.messaging.config.PipelineProperties;
import com.example.messaging.domain.DlqReason;
import com.example.messaging.domain.Priority;
import com.example.messaging.metrics.MessagingMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * In-process {@link MessageBus} for profiles {@code local} and {@code test}: a bounded priority queue (higher
 * {@link Priority} weight first, FIFO inside a priority), N worker threads, delayed redelivery through a scheduler,
 * a dead-letter list, pause/resume and a graceful stop with the same ack / requeue-with-delay / DLQ semantics as the
 * RabbitMQ implementation.
 *
 * <p>Failure semantics: a handler that throws is treated as a nack with requeue (short exponential delay); after
 * {@code messaging.dispatch.poison-threshold} consecutive failures the envelope is dead-lettered as
 * {@link DlqReason#POISON_MESSAGE} and {@link DispatchHandler#onPoison} is invoked. Publishing is refused with
 * {@link BusRejectedException} once the queue depth reaches the shared backpressure limit; redeliveries (delayed
 * requeues) bypass the limit so an accepted message is never dropped by backpressure.
 */
@Component
@Profile({"local", "test"})
public class InMemoryMessageBus implements MessageBus, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(InMemoryMessageBus.class);
    private static final int DLQ_CAPACITY = 10_000;

    private record Entry(DispatchEnvelope envelope, int deliveryCount, int failures, long seq, long enqueuedAtMs) {}

    private static final class Flight {
        final Entry entry;
        final AtomicBoolean completed = new AtomicBoolean();

        Flight(Entry entry) {
            this.entry = entry;
        }
    }

    /** One dead-lettered envelope. */
    public record DeadLetter(DispatchEnvelope envelope, DlqReason reason, int deliveryCount, Instant at) {}

    private final MessagingProperties props;
    private final PipelineProperties pipeline;
    private final BackpressureLimits limits;
    private final MessagingMetrics metrics;

    private final PriorityBlockingQueue<Entry> queue = new PriorityBlockingQueue<>(64,
            Comparator.<Entry>comparingInt(e -> -e.envelope().priority().weight()).thenComparingLong(Entry::seq));
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong delayed = new AtomicLong();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Deque<DeadLetter> dlq = new ConcurrentLinkedDeque<>();
    private final Map<Long, Flight> flights = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean paused;
    private volatile DispatchHandler handler;
    private volatile ExecutorService workers;
    private final ScheduledExecutorService scheduler;

    public InMemoryMessageBus(MessagingProperties props, PipelineProperties pipeline, BackpressureLimits limits,
            MessagingMetrics metrics) {
        this.props = props;
        this.pipeline = pipeline;
        this.limits = limits;
        this.metrics = metrics;
        ScheduledThreadPoolExecutor s = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "bus-redelivery");
            t.setDaemon(true);
            return t;
        });
        s.setRemoveOnCancelPolicy(true);
        this.scheduler = s;
    }

    // ------------------------------------------------------------------ MessageBus

    @Override
    public void publish(DispatchEnvelope envelope) {
        int depth = queue.size();
        if (depth >= limits.maxQueueDepth()) {
            long retryAfter = Math.min(30, 1 + depth / Math.max(1, props.dispatch().concurrency() * 50L));
            throw new BusRejectedException("Dispatch queue is full (" + depth + " waiting); retry later", retryAfter);
        }
        enqueue(new Entry(envelope, 1, 0, seq.incrementAndGet(), System.currentTimeMillis()));
    }

    @Override
    public void publishDelayed(DispatchEnvelope envelope, Duration delay) {
        redeliver(envelope, 1, 0, delay);
    }

    @Override
    public void subscribe(DispatchHandler handler) {
        this.handler = handler;
    }

    @Override
    public QueueStats stats() {
        Map<Priority, Long> depth = new EnumMap<>(Priority.class);
        for (Priority p : Priority.values()) {
            depth.put(p, 0L);
        }
        long oldest = Long.MAX_VALUE;
        for (Object o : queue.toArray()) {
            Entry e = (Entry) o;
            depth.merge(e.envelope().priority(), 1L, Long::sum);
            oldest = Math.min(oldest, e.enqueuedAtMs());
        }
        Long age = oldest == Long.MAX_VALUE ? null : Math.max(0, System.currentTimeMillis() - oldest);
        return new QueueStats(depth, inFlight.get(), running.get() ? workerCount() : 0, dlq.size(), delayed.get(), age,
                paused);
    }

    @Override
    public void pause() {
        paused = true;
    }

    @Override
    public void resume() {
        paused = false;
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public synchronized void start() {
        if (running.get()) {
            return;
        }
        int n = workerCount();
        running.set(true);
        AtomicInteger idx = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "dispatch-worker-" + idx.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < n; i++) {
            pool.execute(this::workerLoop);
        }
        this.workers = pool;
        log.info("In-memory message bus started with {} workers", n);
    }

    @Override
    public synchronized void stop() {
        if (!running.getAndSet(false)) {
            return;
        }
        ExecutorService pool = workers;
        if (pool == null) {
            return;
        }
        pool.shutdown();
        long timeoutMs = pipeline.shutdownTimeout().toMillis();
        try {
            if (!pool.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                log.warn("In-memory bus did not drain within {} ms; returning {} unacked deliveries to the queue",
                        timeoutMs, flights.size());
                // give the deliveries back FIRST: once a delivery is marked completed its late answer (for instance the
                // result of a handler that was just interrupted) is ignored, so nothing can be acknowledged by accident
                for (Flight f : new ArrayList<>(flights.values())) {
                    if (f.completed.compareAndSet(false, true)) {
                        Entry e = f.entry;
                        enqueue(new Entry(e.envelope(), e.deliveryCount() + 1, e.failures(), seq.incrementAndGet(),
                                e.enqueuedAtMs()));
                    }
                }
                pool.shutdownNow();
                pool.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
        workers = null;
        log.info("In-memory message bus stopped; {} envelopes remain queued", queue.size());
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    // ------------------------------------------------------------------ SmartLifecycle

    @Override
    public boolean isAutoStartup() {
        return false; // started by DispatchWiring once the consumer is subscribed
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000; // stops before the web server and the database pool
    }

    // ------------------------------------------------------------------ test/ops helpers

    public List<DeadLetter> deadLetters() {
        return List.copyOf(dlq);
    }

    /** Removes all dead letters (operator purge, test cleanup). */
    public int purgeDeadLetters() {
        int n = dlq.size();
        dlq.clear();
        return n;
    }

    /**
     * Blocks until nothing is queued, delayed or in flight, or the timeout elapses. Idleness must be observed twice in
     * a row so the instant between a worker taking an envelope and counting it as in flight is never mistaken for it.
     */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (idleNow()) {
                Thread.sleep(30);
                if (idleNow()) {
                    return true;
                }
            }
            Thread.sleep(10);
        }
        return idleNow();
    }

    private boolean idleNow() {
        return queue.isEmpty() && delayed.get() == 0 && inFlight.get() == 0;
    }

    // ------------------------------------------------------------------ internals

    private int workerCount() {
        return Math.max(1, props.dispatch().concurrency());
    }

    private void enqueue(Entry e) {
        queue.add(e);
    }

    private void redeliver(DispatchEnvelope envelope, int deliveryCount, int failures, Duration delay) {
        long delayMs = delay == null ? 0 : Math.max(0, delay.toMillis());
        long enqueuedAt = System.currentTimeMillis() + delayMs;
        if (delayMs == 0) {
            enqueue(new Entry(envelope, deliveryCount, failures, seq.incrementAndGet(), enqueuedAt));
            return;
        }
        delayed.incrementAndGet();
        try {
            scheduler.schedule(() -> {
                try {
                    enqueue(new Entry(envelope, deliveryCount, failures, seq.incrementAndGet(),
                            System.currentTimeMillis()));
                } finally {
                    delayed.decrementAndGet();
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ex) { // scheduler closed during shutdown: keep the envelope
            delayed.decrementAndGet();
            enqueue(new Entry(envelope, deliveryCount, failures, seq.incrementAndGet(), enqueuedAt));
        }
    }

    private void workerLoop() {
        while (running.get()) {
            try {
                DispatchHandler h = handler;
                if (paused || h == null) {
                    Thread.sleep(25);
                    continue;
                }
                Entry e = queue.poll(100, TimeUnit.MILLISECONDS);
                if (e == null) {
                    continue;
                }
                if (!running.get()) { // stop requested while polling: never start new work
                    enqueue(e);
                    return;
                }
                process(h, e);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ex) {
                log.error("Unexpected error in bus worker", ex);
            }
        }
    }

    private void process(DispatchHandler h, Entry e) {
        Flight flight = new Flight(e);
        flights.put(e.seq(), flight);
        inFlight.incrementAndGet();
        try {
            BusDecision decision = null;
            Throwable failure = null;
            try {
                decision = h.handle(e.envelope(), e.deliveryCount());
            } catch (Throwable t) {
                failure = t;
            }
            if (!flight.completed.compareAndSet(false, true)) {
                return; // forced stop already returned the delivery to the queue
            }
            if (failure != null) {
                onFailure(h, e, failure);
            } else if (decision == null || decision.type() == BusDecision.Type.ACK) {
                return;
            } else if (decision.type() == BusDecision.Type.REQUEUE) {
                redeliver(e.envelope(), e.deliveryCount() + 1, e.failures(), decision.delay());
            } else {
                deadLetter(e, decision.reason() == null ? DlqReason.POISON_MESSAGE : decision.reason());
            }
        } finally {
            flights.remove(e.seq());
            inFlight.decrementAndGet();
        }
    }

    private void onFailure(DispatchHandler h, Entry e, Throwable failure) {
        int failures = e.failures() + 1;
        // never log the envelope content: ids only
        log.warn("Handler failed for dispatch {} (delivery {}, failure {}/{}): {}", e.envelope().dispatchId(),
                e.deliveryCount(), failures, props.dispatch().poisonThreshold(), failure.getClass().getSimpleName());
        if (failures >= Math.max(1, props.dispatch().poisonThreshold())) {
            deadLetter(e, DlqReason.POISON_MESSAGE);
            try {
                h.onPoison(e.envelope(), e.deliveryCount(), failure);
            } catch (RuntimeException ex) {
                log.warn("onPoison callback failed for dispatch {}", e.envelope().dispatchId());
            }
            return;
        }
        long backoff = Math.min(2_000L, 50L << Math.min(failures, 6));
        redeliver(e.envelope(), e.deliveryCount() + 1, failures, Duration.ofMillis(backoff));
    }

    private void deadLetter(Entry e, DlqReason reason) {
        dlq.addLast(new DeadLetter(e.envelope(), reason, e.deliveryCount(), Instant.now()));
        while (dlq.size() > DLQ_CAPACITY) {
            dlq.pollFirst();
        }
        metrics.deadLetter(reason);
    }
}
