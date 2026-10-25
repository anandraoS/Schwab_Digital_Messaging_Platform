package com.example.messaging.provider.simulator;

import com.example.messaging.callback.CallbackService;
import com.example.messaging.callback.EventNormalizer;
import com.example.messaging.callback.ProviderPayloadMapper;
import com.example.messaging.callback.SignatureVerifier;
import com.example.messaging.common.ApiException;
import com.example.messaging.common.Ids;
import com.example.messaging.config.MessagingProperties;
import com.example.messaging.config.SimulatorProperties;
import com.example.messaging.domain.DeliveryEventType;
import com.example.messaging.domain.MessageAttempt;
import com.example.messaging.domain.MessageRequest;
import com.example.messaging.domain.ProviderConfig;
import com.example.messaging.repository.MessageAttemptRepository;
import com.example.messaging.repository.MessageRequestRepository;
import com.example.messaging.repository.ProviderConfigRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Makes the simulators behave like vendors that post webhooks: builds a vendor-shaped payload with the provider's
 * {@link ProviderPayloadMapper}, signs it with the provider's (dummy) secret and hands it to
 * {@link CallbackService}, the same code the HTTP controller uses, so signature, replay guard, normalisation and
 * state handling are all exercised. Can delay, duplicate and reorder events. Profiles local/test only.
 */
@Component
@Profile({"local", "test"})
public class CallbackEmitter implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(CallbackEmitter.class);

    /** One scheduled emission. */
    public record Emitted(String providerId, String eventId, DeliveryEventType type, long delayMs) {}

    private final CallbackService callbacks;
    private final EventNormalizer normalizer;
    private final MessagingProperties props;
    private final SimulatorProperties simulator;
    private final MessageAttemptRepository attempts;
    private final MessageRequestRepository messages;
    private final ProviderConfigRepository configs;
    private final Clock clock;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "simulator-callbacks");
        t.setDaemon(true);
        return t;
    });

    public CallbackEmitter(CallbackService callbacks, EventNormalizer normalizer, MessagingProperties props,
            SimulatorProperties simulator, MessageAttemptRepository attempts, MessageRequestRepository messages,
            ProviderConfigRepository configs, Clock clock) {
        this.callbacks = callbacks;
        this.normalizer = normalizer;
        this.props = props;
        this.simulator = simulator;
        this.attempts = attempts;
        this.messages = messages;
        this.configs = configs;
        this.clock = clock;
    }

    /** Posts one signed callback right now and returns what the platform answered. */
    public CallbackService.Response deliver(String providerId, DeliveryEventType type, String providerMessageId,
            String idempotencyRef, String eventId, Instant occurredAt, String reasonCode) {
        ProviderPayloadMapper mapper = normalizer.mapperFor(providerId)
                .orElseThrow(() -> new IllegalArgumentException("no payload mapper for " + providerId));
        ProviderConfig cfg = configs.findByProviderId(providerId)
                .orElseThrow(() -> new IllegalArgumentException("unknown provider " + providerId));
        byte[] body = mapper.render(new ProviderPayloadMapper.CallbackSpec(eventId, type, providerMessageId,
                idempotencyRef, occurredAt, reasonCode)).getBytes(StandardCharsets.UTF_8);
        String keyId = cfg.getHmacKeyRef();
        String secret = props.callback().secrets().getOrDefault(keyId, simulator.fallbackSecret());
        String signature = SignatureVerifier.header(secret, clock.instant().getEpochSecond(), body);
        return callbacks.handle(providerId, keyId, signature, eventId, body);
    }

    /**
     * Schedules callbacks for the latest provider attempt of a message.
     *
     * @param duplicate   additionally re-sends the very same event (same {@code X-Event-Id}, i.e. a provider retry)
     * @param outOfOrder  additionally delivers an older SENT event after the requested one
     */
    public List<Emitted> emit(String messageId, DeliveryEventType type, long delayMs, boolean duplicate,
            boolean outOfOrder) {
        MessageRequest m = messages.findById(messageId).orElseThrow(() -> new ApiException.NotFoundException("Message"));
        MessageAttempt a = attempts.findFirstByMessageIdOrderByAttemptNoDesc(messageId)
                .filter(x -> x.getProviderId() != null)
                .orElseThrow(() -> new ApiException.ValidationException("messageId",
                        "the message has not been handed to a provider yet"));
        String ref = messageId + ":" + (m.getFallbackIndex() + 1);
        String providerId = a.getProviderId();
        String providerMessageId = a.getProviderMessageId();
        List<Emitted> out = new ArrayList<>();
        String eventId = "sim-" + Ids.newId();
        Instant occurred = clock.instant();
        schedule(delayMs, () -> deliver(providerId, type, providerMessageId, ref, eventId, occurred, null));
        out.add(new Emitted(providerId, eventId, type, delayMs));
        if (duplicate) {
            schedule(delayMs + 25, () -> deliver(providerId, type, providerMessageId, ref, eventId, occurred, null));
            out.add(new Emitted(providerId, eventId, type, delayMs + 25));
        }
        if (outOfOrder) {
            String lateId = "sim-" + Ids.newId();
            Instant older = occurred.minusSeconds(5);
            DeliveryEventType lower = type == DeliveryEventType.SENT ? DeliveryEventType.PROVIDER_ACCEPTED
                    : DeliveryEventType.SENT;
            schedule(delayMs + 50, () -> deliver(providerId, lower, providerMessageId, ref, lateId, older, null));
            out.add(new Emitted(providerId, lateId, lower, delayMs + 50));
        }
        return out;
    }

    /** Used by simulators after accepting a message ({@code messaging.simulator.auto-callbacks=true}). */
    public void scheduleAutomatic(String providerId, String ref, String providerMessageId, Duration delay) {
        long d = Math.max(0, delay.toMillis());
        Instant now = clock.instant();
        schedule(d, () -> deliver(providerId, DeliveryEventType.SENT, providerMessageId, ref, "auto-" + Ids.newId(),
                now, null));
        schedule(d * 2 + 10, () -> deliver(providerId, DeliveryEventType.DELIVERED, providerMessageId, ref,
                "auto-" + Ids.newId(), now.plusMillis(d), null));
    }

    private void schedule(long delayMs, Runnable r) {
        scheduler.schedule(() -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                log.warn("Simulated callback failed: {}", e.getClass().getSimpleName());
            }
        }, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
