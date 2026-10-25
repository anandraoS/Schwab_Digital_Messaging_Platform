package com.example.messaging.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.example.messaging.callback.SignatureVerifier;
import com.example.messaging.domain.DeliveryEventType;
import com.example.messaging.provider.simulator.FaultInjector.Mode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Logs are an exfiltration channel: whatever the pipeline does (success, retries, failures, callbacks, poison,
 * sweeping, reconciliation), no log line, exception text or MDC value may contain a recipient address, a rendered body,
 * a variable value, a bearer token or a callback secret.
 */
class LoggingPrivacyIT extends AbstractPipelineIT {

    @Test
    void capturedLogsNeverContainAddressesRenderedContentOrSecrets() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Logger app = (Logger) LoggerFactory.getLogger("com.example.messaging");
        Level previous = app.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        app.setLevel(Level.DEBUG);
        try {
            exerciseThePipeline();
        } finally {
            app.setLevel(previous);
            root.detachAppender(appender);
        }

        String tag = lastTag;
        List<String> forbidden = List.of(tag + "@example.test", tag + "+hardbounce", "PRIVACYSYM42", "was executed at",
                "Trade confirmed", "Trade confirmation for account", SECRET, "Bearer ", "eyJ", "alice.privacy@example.test",
                "+15550199", "push-" + tag);
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent e : new ArrayList<>(appender.list)) {
            StringBuilder sb = new StringBuilder(e.getFormattedMessage());
            for (IThrowableProxy t = e.getThrowableProxy(); t != null; t = t.getCause()) {
                sb.append(' ').append(t.getMessage());
            }
            e.getMDCPropertyMap().forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
            lines.add(sb.toString());
        }
        assertThat(lines).as("the capture must actually contain the pipeline's log output").hasSizeGreaterThan(8);
        assertThat(lines).anyMatch(l -> l.contains("Callback rejected"));
        assertThat(lines).anyMatch(l -> l.contains("Callback payload rejected"));
        assertThat(lines).anyMatch(l -> l.contains("Provider simulated-email answered"));
        for (String f : forbidden) {
            assertThat(lines).as("log lines must not contain '%s'", f).noneMatch(l -> l.contains(f));
        }
    }

    private String lastTag;

    private void exerciseThePipeline() throws Exception {
        lastTag = "pv" + UUID.randomUUID().toString().substring(0, 8);
        String rcp = newRecipient(RETAIL, lastTag + "@example.test");
        jdbc.update("update recipient set push_token = ?, phone_e164 = ? where id = ?", "push-" + lastTag,
                "+15550199" + String.format("%03d", Math.abs(lastTag.hashCode()) % 1000), rcp);

        Map<String, Object> body = request(rcp, "EMAIL");
        body.put("variables", Map.of("accountLast4", "4242", "quantity", "7", "symbol", "PRIVACYSYM42", "price", "9.99"));

        // success path with callbacks
        String ok = submitOk(sender(RETAIL), body);
        awaitStatus(ok, "SENT_TO_PROVIDER");
        sendCallback(ok, DeliveryEventType.DELIVERED, uniqueEventId());
        sendCallback(ok, DeliveryEventType.DELIVERED, uniqueEventId()); // stale
        sendCallback(ok, DeliveryEventType.BOUNCED, uniqueEventId());   // conflict

        // retries and dead letter
        faults.set("simulated-email", Mode.TRANSIENT, 1.0);
        String dead = submitOk(sender(RETAIL), body);
        awaitStatus(dead, "DEAD_LETTERED");
        faults.reset();
        mvc.perform(post("/api/v1/dead-letters/" + dead + "/replay").header("Authorization", operator(RETAIL)));
        awaitStatus(dead, "SENT_TO_PROVIDER");

        // permanent failure with fallback, hard bounce
        faults.set("simulated-email", Mode.PERMANENT, 1.0);
        Map<String, Object> fb = new LinkedHashMap<>(body);
        fb.put("fallbackChannels", List.of("SMS"));
        String viaSms = submitOk(sender(RETAIL), fb);
        awaitStatus(viaSms, "SENT_TO_PROVIDER");
        faults.reset();

        // ambiguous, sweeper, reconciliation
        faults.set("simulated-email", Mode.AMBIGUOUS, 1.0);
        String unknown = submitOk(sender(RETAIL), body);
        awaitStatus(unknown, "DELIVERY_UNKNOWN");
        faults.reset();
        reconciliation.reconcileOne(unknown);

        // poison
        quiesce();
        String poison = submitOk(sender(RETAIL), body);
        awaitStatus(poison, "QUEUED");
        hook.failBeforeProcess.put(poison, new java.util.concurrent.atomic.AtomicInteger(100));
        bus.resume();
        awaitStatus(poison, "DEAD_LETTERED");

        // hostile callbacks: bad signature, malformed body with PII, a reason carrying an address
        String pii = "{\"event\":\"exploded\",\"note\":\"alice.privacy@example.test\"}";
        postCallback("simulated-email", keyIdFor("simulated-email"), "t=1,v1=00", "e-bad", pii);
        postCallback("simulated-email", keyIdFor("simulated-email"),
                SignatureVerifier.header(SECRET, Instant.now().getEpochSecond(), pii.getBytes(StandardCharsets.UTF_8)),
                "e-malformed", pii);
        String reason = "{\"event\":\"failed\",\"message_id\":\"x\",\"event_id\":\"e-r\",\"meta\":{\"idempotencyRef\":\""
                + ref(ok) + "\"},\"reason\":\"mailbox alice.privacy@example.test is full\"}";
        postCallback("simulated-email", keyIdFor("simulated-email"),
                SignatureVerifier.header(SECRET, Instant.now().getEpochSecond(), reason.getBytes(StandardCharsets.UTF_8)),
                "e-reason", reason);

        // sweeper path
        clock.advance(Duration.ofMinutes(20));
        Thread.sleep(1500);
    }
}
