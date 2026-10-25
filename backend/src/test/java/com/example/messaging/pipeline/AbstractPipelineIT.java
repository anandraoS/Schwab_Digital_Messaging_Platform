package com.example.messaging.pipeline;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.messaging.AbstractIntegrationTest;
import com.example.messaging.callback.EventNormalizer;
import com.example.messaging.callback.ProviderPayloadMapper;
import com.example.messaging.callback.SignatureVerifier;
import com.example.messaging.domain.DeliveryEventType;
import com.example.messaging.messaging.InMemoryMessageBus;
import com.example.messaging.provider.ResilientProviderInvoker;
import com.example.messaging.provider.simulator.AcmeSmsProvider;
import com.example.messaging.provider.simulator.FaultInjector;
import com.example.messaging.provider.simulator.SimulatedEmailProvider;
import com.example.messaging.provider.simulator.SimulatedProviderBase;
import com.example.messaging.provider.simulator.SimulatedPushProvider;
import com.example.messaging.provider.simulator.SimulatedSmsProvider;
import com.example.messaging.provider.ProviderRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Base for tests that run the real asynchronous pipeline: outbox publisher, in-memory bus with worker threads,
 * dispatch consumer, schedulers, simulators and the callback path, with fast backoff and an adjustable clock. All
 * subclasses share one Spring context (same properties and imports). Tests use fresh recipients so suppression and
 * state never leak between cases, and every case restores simulators, circuits, clock and consumers.
 */
@SpringBootTest(properties = {
        "messaging.pipeline.auto-start=true",
        "messaging.pipeline.scheduler.enabled=true",
        "messaging.pipeline.scheduler.outbox-interval-ms=50",
        "messaging.pipeline.scheduler.poller-interval-ms=200",
        "messaging.pipeline.scheduler.retry-grace=1s",
        "messaging.pipeline.scheduler.sweeper-interval-ms=500",
        "messaging.pipeline.scheduler.reconcile-interval-ms=1000",
        "messaging.pipeline.rate-limit-requeue-delay=100ms",
        "messaging.pipeline.resilience.open-state-wait=1s",
        "messaging.dispatch.concurrency=4",
        "messaging.dispatch.max-attempts=4",
        "messaging.dispatch.backoff-base-ms=40",
        "messaging.dispatch.backoff-cap-ms=200",
        "messaging.dispatch.poison-threshold=3",
        "messaging.simulator.seed=42"
})
@Import(PipelineTestConfig.class)
public abstract class AbstractPipelineIT extends AbstractIntegrationTest {

    protected static final String SECRET = "local-dev-secret-not-for-production";

    @Autowired
    protected PipelineTestConfig.AdjustableClock clock;
    @Autowired
    protected PipelineTestConfig.ProgrammableHook hook;
    @Autowired
    protected FaultInjector faults;
    @Autowired
    protected com.example.messaging.config.PipelineConfig.BackpressureLimits limits;
    @Autowired
    protected InMemoryMessageBus bus;
    @Autowired
    protected ResilientProviderInvoker invoker;
    @Autowired
    protected ProviderRegistry registry;
    @Autowired
    protected EventNormalizer normalizer;
    @Autowired
    protected SimulatedEmailProvider email;
    @Autowired
    protected SimulatedSmsProvider sms;
    @Autowired
    protected SimulatedPushProvider push;
    @Autowired
    protected AcmeSmsProvider acme;
    @Autowired
    protected com.example.messaging.service.ReconciliationService reconciliation;
    @Autowired
    protected com.example.messaging.scheduler.StuckMessageSweeper sweeper;
    @Autowired
    protected com.example.messaging.scheduler.OutboxPublisher outboxPublisher;
    @Autowired
    protected com.example.messaging.messaging.DispatchConsumer consumer;
    @Autowired
    protected io.micrometer.core.instrument.MeterRegistry meters;

    @BeforeEach
    void pipelineBefore() throws Exception {
        faults.reset();
        bus.resume();
        retireLeftovers(); // first let leftovers drain, then zero the simulator counters
        restoreWorld();
    }

    /** JDBC parameter for a TIMESTAMP column: the application stores UTC wall-clock values (no zone conversion). */
    protected static java.time.LocalDateTime utc(Instant i) {
        return java.time.LocalDateTime.ofInstant(i, java.time.ZoneOffset.UTC);
    }

    /**
     * Messages left in flight by earlier test cases live in the shared database. When a test moves the clock forward
     * the sweeper and reconciliation would legitimately pick them up and call the (reset) simulators, polluting the
     * counters of the current test, so every case starts by retiring them.
     */
    protected void retireLeftovers() throws Exception {
        bus.awaitIdle(Duration.ofSeconds(10));
        jdbc.update("update message_request set status = 'CANCELLED', status_reason = 'test cleanup' where status not in "
                + "('DELIVERED','REJECTED','CANCELLED','FAILED','DEAD_LETTERED')");
        bus.awaitIdle(Duration.ofSeconds(10)); // in-flight work and delayed redeliveries find cancelled messages
        bus.purgeDeadLetters();
    }

    @AfterEach
    void pipelineAfter() {
        restoreWorld();
    }

    /** Puts simulators, circuits, clock, hooks, consumers and provider configuration back to the defaults. */
    protected void restoreWorld() {
        PipelineTestConfig.DbOutage.down = false;
        PipelineTestConfig.RedisOutage.down = false;
        faults.reset();
        hook.reset();
        clock.reset();
        bus.resume();
        limits.setMaxQueueDepth(5000);
        for (String id : List.of("simulated-email", "simulated-sms", "simulated-push", "acme-sms")) {
            invoker.reset(id);
        }
        for (SimulatedProviderBase p : List.of(email, sms, push, acme)) {
            p.resetSimulator();
        }
        jdbc.update("update provider_config set enabled = true, timeout_ms = 2000, rate_limit_per_sec = 100 "
                + "where provider_id in ('simulated-email','simulated-sms')");
        jdbc.update("update provider_config set enabled = true, timeout_ms = 2000, rate_limit_per_sec = 200 "
                + "where provider_id = 'simulated-push'");
        jdbc.update("update provider_config set enabled = false, timeout_ms = 3000, rate_limit_per_sec = 50 "
                + "where provider_id = 'acme-sms'");
        registry.invalidate();
    }

    // ------------------------------------------------------------------ data helpers

    /** Inserts a fresh synthetic recipient with full contact data and default preferences; returns its id. */
    protected String newRecipient(String tenant) {
        return newRecipient(tenant, null);
    }

    /** As {@link #newRecipient(String)} with a chosen email address (null = generated). */
    protected String newRecipient(String tenant, String emailAddress) {
        String id = "rcp-t-" + UUID.randomUUID().toString().substring(0, 12);
        String ref = "TREF-" + UUID.randomUUID();
        jdbc.update("insert into recipient (id, tenant_id, external_ref, display_name, email, phone_e164, push_token, "
                + "locale, time_zone, deleted, created_at, updated_at, version) values (?,?,?,?,?,?,?,?,?,?,?,?,0)",
                id, tenant, ref, "Test " + id, emailAddress != null ? emailAddress : id + "@example.test",
                "+1555" + String.format("%07d", Math.abs(id.hashCode()) % 10_000_000), "push-" + id, "en", "UTC", false,
                utc(Instant.now()), utc(Instant.now()));
        // quiet_start == quiet_end disables quiet hours, so tests never depend on the time of day
        jdbc.update("insert into recipient_preference (recipient_id, marketing_opt_in, email_enabled, sms_enabled, "
                + "push_enabled, quiet_start, quiet_end, created_at, updated_at, version) values "
                + "(?,false,true,true,true,TIME '00:00:00',TIME '00:00:00',?,?,0)", id,
                utc(Instant.now()), utc(Instant.now()));
        return id;
    }

    /** WELCOME_PUSH (TRANSACTIONAL) push request. */
    protected Map<String, Object> pushRequest(String recipientId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientReference", "push-" + UUID.randomUUID());
        body.put("recipientId", recipientId);
        body.put("channel", "PUSH");
        body.put("category", "TRANSACTIONAL");
        body.put("priority", "NORMAL");
        body.put("template", Map.of("code", "WELCOME_PUSH", "locale", "en-US"));
        body.put("variables", Map.of("firstName", "Test"));
        return body;
    }

    protected double counter(String name, String... tags) {
        var search = meters.find(name);
        for (int i = 0; i < tags.length; i += 2) {
            search = search.tag(tags[i], tags[i + 1]);
        }
        return search.counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    protected Map<String, Object> request(String recipientId, String channel) {
        Map<String, Object> body = tradeConfirmation(recipientId);
        body.put("channel", channel);
        return body;
    }

    /** Submits through the real API as the retail sender and returns the message id. */
    protected String submit(String recipientId, String channel) throws Exception {
        return submitOk(sender(RETAIL), request(recipientId, channel));
    }

    protected String submitWithFallback(String recipientId, String channel, String... fallbacks) throws Exception {
        Map<String, Object> body = request(recipientId, channel);
        body.put("fallbackChannels", List.of(fallbacks));
        return submitOk(sender(RETAIL), body);
    }

    protected Map<String, Object> row(String messageId) {
        return jdbc.queryForMap("select status, status_reason, attempt_count, max_attempts, channel_used, dlq_reason, "
                + "locked_until, next_attempt_at, fallback_index from message_request where id = ?", messageId);
    }

    protected int attemptCount(String messageId) {
        return jdbc.queryForObject("select attempt_count from message_request where id = ?", Integer.class, messageId);
    }

    protected int attemptRows(String messageId) {
        return jdbc.queryForObject("select count(*) from message_attempt where message_id = ?", Integer.class, messageId);
    }

    protected List<Map<String, Object>> attemptList(String messageId) {
        return jdbc.queryForList("select attempt_no, channel, provider_id, state, outcome, provider_message_id, "
                + "error_code from message_attempt where message_id = ? order by attempt_no", messageId);
    }

    protected List<Map<String, Object>> eventList(String messageId) {
        return jdbc.queryForList("select provider_id, provider_event_id, type, applied, ignored_reason from "
                + "delivery_event where message_id = ? order by received_at, id", messageId);
    }

    protected long auditCount(String messageId, String action) {
        return jdbc.queryForObject("select count(*) from audit_event where entity_id = ? and action = ?", Long.class,
                messageId, action);
    }

    protected SimulatedProviderBase providerOf(String messageId) {
        String pid = jdbc.queryForObject("select provider_id from message_attempt where message_id = ? "
                + "order by attempt_no desc limit 1", String.class, messageId);
        return (SimulatedProviderBase) registry.adapter(pid).orElseThrow();
    }

    /** idempotency reference the platform used for the first attempt group of a message. */
    protected static String ref(String messageId) {
        return messageId + ":1";
    }

    // ------------------------------------------------------------------ waiting

    protected void awaitStatus(String messageId, String... statuses) {
        List<String> wanted = List.of(statuses);
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(25))
                .alias("message " + messageId + " to reach " + wanted)
                .until(() -> wanted.contains(statusOf(messageId)));
    }

    /** Pauses the consumers and waits until no worker can still pick something up (poll timeout is 100 ms). */
    protected void quiesce() throws Exception {
        bus.pause();
        Thread.sleep(200);
        awaitIdleWorkers();
    }

    private void awaitIdleWorkers() {
        await().atMost(Duration.ofSeconds(10)).until(() -> bus.stats().inFlight() == 0);
    }

    protected void awaitAttempts(String messageId, int count) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(25))
                .alias(count + " attempt rows of " + messageId)
                .until(() -> attemptRows(messageId) >= count);
    }

    /** Waits until the bus has nothing queued, delayed or in flight. */
    protected void awaitIdle() throws InterruptedException {
        if (!bus.awaitIdle(Duration.ofSeconds(20))) {
            throw new AssertionError("bus did not become idle");
        }
    }

    // ------------------------------------------------------------------ callbacks through the real HTTP path

    protected record CallbackResponse(int status, String result, String body) {}

    protected CallbackResponse postCallback(String providerId, String keyId, String signatureHeader, String eventId,
            String body) throws Exception {
        var req = post("/api/v1/callbacks/" + providerId).contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8));
        if (keyId != null) {
            req.header("X-Provider-Key-Id", keyId);
        }
        if (signatureHeader != null) {
            req.header("X-Signature", signatureHeader);
        }
        if (eventId != null) {
            req.header("X-Event-Id", eventId);
        }
        MvcResult r = mvc.perform(req).andReturn();
        String text = r.getResponse().getContentAsString();
        String result = null;
        try {
            var n = om.readTree(text);
            result = n.has("result") ? n.get("result").asText() : n.has("code") ? n.get("code").asText() : null;
        } catch (Exception ignored) {
            // not JSON
        }
        return new CallbackResponse(r.getResponse().getStatus(), result, text);
    }

    protected static String keyIdFor(String providerId) {
        return providerId + "-key";
    }

    /** Vendor-shaped payload for the latest attempt of a message. */
    protected String payload(String messageId, DeliveryEventType type, String eventId, Instant occurredAt) {
        Map<String, Object> a = jdbc.queryForMap("select provider_id, provider_message_id from message_attempt "
                + "where message_id = ? order by attempt_no desc limit 1", messageId);
        String providerId = (String) a.get("provider_id");
        ProviderPayloadMapper mapper = normalizer.mapperFor(providerId).orElseThrow();
        return mapper.render(new ProviderPayloadMapper.CallbackSpec(eventId, type, (String) a.get("provider_message_id"),
                ref(messageId), occurredAt, null));
    }

    /** Signs with the provider's configured dummy secret and posts through MockMvc (same path as a webhook). */
    protected CallbackResponse sendCallback(String messageId, DeliveryEventType type, String eventId) throws Exception {
        return sendCallback(messageId, type, eventId, Instant.now());
    }

    protected CallbackResponse sendCallback(String messageId, DeliveryEventType type, String eventId,
            Instant occurredAt) throws Exception {
        String providerId = jdbc.queryForObject("select provider_id from message_attempt where message_id = ? "
                + "order by attempt_no desc limit 1", String.class, messageId);
        String body = payload(messageId, type, eventId, occurredAt);
        // the server validates timestamps against the (adjustable) application clock
        String sig = SignatureVerifier.header(SECRET, clock.instant().getEpochSecond(),
                body.getBytes(StandardCharsets.UTF_8));
        return postCallback(providerId, keyIdFor(providerId), sig, eventId, body);
    }

    protected static String uniqueEventId() {
        return "evt-" + UUID.randomUUID();
    }

    protected Map<String, Object> flatten(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
