package com.example.messaging.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.messaging.domain.Channel;
import com.example.messaging.domain.DlqReason;
import com.example.messaging.domain.Priority;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

/** The broker-independent parts of the RabbitMQ bus: delay tiers, headers, (de)serialisation, poison decision. */
class RabbitBusSupportTest {

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private DispatchEnvelope envelope(Priority p) {
        return DispatchEnvelope.of("msg-1", "tenant-retail", Channel.SMS, p, 3, "corr-1", Instant.parse("2026-10-18T10:00:00Z"));
    }

    @Test
    void delayTierIsTheSmallestOneThatCoversTheRequestedDelay() {
        assertThat(RabbitBusSupport.tierFor(Duration.ZERO).queue()).isEqualTo("messaging.retry.q.5s");
        assertThat(RabbitBusSupport.tierFor(null).queue()).isEqualTo("messaging.retry.q.5s");
        assertThat(RabbitBusSupport.tierFor(Duration.ofMillis(1)).routingKey()).isEqualTo("retry.5s");
        assertThat(RabbitBusSupport.tierFor(Duration.ofSeconds(5)).routingKey()).isEqualTo("retry.5s");
        assertThat(RabbitBusSupport.tierFor(Duration.ofMillis(5001)).routingKey()).isEqualTo("retry.30s");
        assertThat(RabbitBusSupport.tierFor(Duration.ofSeconds(30)).routingKey()).isEqualTo("retry.30s");
        assertThat(RabbitBusSupport.tierFor(Duration.ofSeconds(31)).routingKey()).isEqualTo("retry.2m");
        assertThat(RabbitBusSupport.tierFor(Duration.ofMinutes(2)).routingKey()).isEqualTo("retry.2m");
        assertThat(RabbitBusSupport.tierFor(Duration.ofMinutes(3)).routingKey()).isEqualTo("retry.10m");
        assertThat(RabbitBusSupport.tierFor(Duration.ofMinutes(10)).routingKey()).isEqualTo("retry.10m");
        // longer than the longest tier: still the longest tier, the remainder is carried by x-not-before
        assertThat(RabbitBusSupport.tierFor(Duration.ofHours(3)).routingKey()).isEqualTo("retry.10m");
        assertThat(RabbitBusSupport.needsNotBefore(Duration.ofMinutes(10))).isFalse();
        assertThat(RabbitBusSupport.needsNotBefore(Duration.ofMinutes(11))).isTrue();
        assertThat(RabbitBusSupport.needsNotBefore(null)).isFalse();
    }

    @Test
    void messagesArePersistentCarryThePriorityAndTheBookkeepingHeaders() {
        Instant notBefore = Instant.parse("2026-10-18T12:00:00Z");
        Message m = RabbitBusSupport.message(envelope(Priority.CRITICAL), json, 4, 2, notBefore, DlqReason.POISON_MESSAGE);
        MessageProperties p = m.getMessageProperties();
        assertThat(p.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(p.getPriority()).isEqualTo(9);
        assertThat(p.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(p.getMessageId()).isEqualTo("msg-1:3");
        assertThat(p.getCorrelationId()).isEqualTo("corr-1");
        assertThat(RabbitBusSupport.intHeader(p, RabbitBusSupport.H_ATTEMPT, 1)).isEqualTo(4);
        assertThat(RabbitBusSupport.intHeader(p, RabbitBusSupport.H_FAILURES, 0)).isEqualTo(2);
        assertThat(p.getHeaders()).containsEntry(RabbitBusSupport.H_NOT_BEFORE, notBefore.toEpochMilli())
                .containsEntry(RabbitBusSupport.H_DLQ_REASON, "POISON_MESSAGE")
                .containsEntry(RabbitBusSupport.H_DISPATCH_ID, "msg-1:3");
        for (Priority pr : Priority.values()) {
            assertThat(RabbitBusSupport.message(envelope(pr), json, 1, 0, null, null).getMessageProperties()
                    .getPriority()).isEqualTo(pr.weight());
        }
        // optional headers are absent when not needed
        MessageProperties plain = RabbitBusSupport.message(envelope(Priority.LOW), json, 1, 0, null, null)
                .getMessageProperties();
        assertThat(plain.getHeaders()).doesNotContainKeys(RabbitBusSupport.H_NOT_BEFORE, RabbitBusSupport.H_DLQ_REASON);
    }

    @Test
    void theBodyHoldsIdentifiersOnlyAndRoundTrips() {
        DispatchEnvelope env = envelope(Priority.NORMAL);
        Message m = RabbitBusSupport.message(env, json, 1, 0, null, null);
        String body = new String(m.getBody(), StandardCharsets.UTF_8);
        assertThat(body).contains("msg-1").contains("tenant-retail").doesNotContain("@").doesNotContain("body");
        assertThat(RabbitBusSupport.read(m.getBody(), json)).isEqualTo(env);
    }

    @Test
    void unreadableOrIncompleteBodiesArePoison() {
        assertThatThrownBy(() -> RabbitBusSupport.read("not json".getBytes(StandardCharsets.UTF_8), json))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RabbitBusSupport.read("{}".getBytes(StandardCharsets.UTF_8), json))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RabbitBusSupport.read(("{\"dispatchId\":\"x:0\",\"messageId\":\"x\",\"attemptNo\":0,"
                + "\"channel\":\"SMS\",\"priority\":\"LOW\"}").getBytes(StandardCharsets.UTF_8), json))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RabbitBusSupport.read(("{\"dispatchId\":\"x:1\",\"messageId\":\"x\",\"attemptNo\":1,"
                + "\"channel\":\"FAX\",\"priority\":\"LOW\"}").getBytes(StandardCharsets.UTF_8), json))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RabbitBusSupport.read(new byte[0], json)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void notBeforeIsRespectedAndHeadersDefaultSafely() {
        Instant now = Instant.parse("2026-10-18T10:00:00Z");
        MessageProperties p = new MessageProperties();
        assertThat(RabbitBusSupport.remaining(p, now)).isEqualTo(Duration.ZERO);
        assertThat(RabbitBusSupport.intHeader(p, RabbitBusSupport.H_ATTEMPT, 1)).isEqualTo(1);
        p.setHeader(RabbitBusSupport.H_NOT_BEFORE, now.plusSeconds(90).toEpochMilli());
        assertThat(RabbitBusSupport.remaining(p, now)).isEqualTo(Duration.ofSeconds(90));
        assertThat(RabbitBusSupport.remaining(p, now.plusSeconds(90))).isEqualTo(Duration.ZERO);
        assertThat(RabbitBusSupport.remaining(p, now.plusSeconds(3600))).isEqualTo(Duration.ZERO);
        p.setHeader(RabbitBusSupport.H_ATTEMPT, "not a number");
        assertThat(RabbitBusSupport.intHeader(p, RabbitBusSupport.H_ATTEMPT, 7)).isEqualTo(7);
        p.setHeader(RabbitBusSupport.H_FAILURES, 4L); // brokers may deliver longs
        assertThat(RabbitBusSupport.intHeader(p, RabbitBusSupport.H_FAILURES, 0)).isEqualTo(4);
    }

    @Test
    void poisonDecisionFollowsTheThreshold() {
        assertThat(RabbitBusSupport.isPoison(1, 3)).isFalse();
        assertThat(RabbitBusSupport.isPoison(2, 3)).isFalse();
        assertThat(RabbitBusSupport.isPoison(3, 3)).isTrue();
        assertThat(RabbitBusSupport.isPoison(9, 3)).isTrue();
        assertThat(RabbitBusSupport.isPoison(1, 0)).isTrue(); // a threshold below 1 behaves like 1
    }
}
