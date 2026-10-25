package com.example.messaging.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.messaging.callback.ProviderPayloadMapper.CallbackSpec;
import com.example.messaging.callback.ProviderPayloadMapper.NormalizedEvent;
import com.example.messaging.domain.DeliveryEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure normalisation: vendor payloads in, canonical events out. No database, no network. */
class EventNormalizerTest {

    private static final Instant RECEIVED = Instant.parse("2026-10-18T10:00:00Z");
    private final EventNormalizer normalizer = new EventNormalizer(List.of(new SimulatedEmailPayloadMapper(),
            new SimulatedSmsPayloadMapper(), new SimulatedPushPayloadMapper(), new AcmeSmsPayloadMapper()),
            new ObjectMapper());

    private NormalizedEvent normalize(String provider, String json, String headerEventId) {
        return normalizer.normalize(provider, json.getBytes(StandardCharsets.UTF_8), headerEventId, RECEIVED);
    }

    // ------------------------------------------------------------------ the documented examples

    @Test
    void theDocumentedEmailExampleMapsToTheCanonicalEvent() {
        NormalizedEvent e = normalize("simulated-email", "{\"event\":\"delivered\",\"message_id\":\"em-77ab\","
                + "\"event_id\":\"evt-1001\",\"timestamp\":\"2026-10-18T10:15:30Z\",\"meta\":{\"idempotencyRef\":\"3f2c:1\"}}",
                null);
        assertThat(e.providerId()).isEqualTo("simulated-email");
        assertThat(e.providerEventId()).isEqualTo("evt-1001");
        assertThat(e.type()).isEqualTo(DeliveryEventType.DELIVERED);
        assertThat(e.providerMessageId()).isEqualTo("em-77ab");
        assertThat(e.idempotencyRef()).isEqualTo("3f2c:1");
        assertThat(e.occurredAt()).isEqualTo(Instant.parse("2026-10-18T10:15:30Z"));
    }

    @Test
    void everyEmailStatusMapsAsDocumented() {
        record Case(String status, DeliveryEventType type) {}
        for (Case c : List.of(new Case("queued", DeliveryEventType.PROVIDER_ACCEPTED),
                new Case("sent", DeliveryEventType.SENT), new Case("delivered", DeliveryEventType.DELIVERED),
                new Case("hard_bounce", DeliveryEventType.BOUNCED), new Case("soft_bounce", DeliveryEventType.DEFERRED),
                new Case("failed", DeliveryEventType.FAILED), new Case("rejected", DeliveryEventType.REJECTED),
                new Case("open", DeliveryEventType.OPENED), new Case("click", DeliveryEventType.CLICKED),
                new Case("unsubscribe", DeliveryEventType.UNSUBSCRIBED))) {
            assertThat(normalize("simulated-email", "{\"event\":\"" + c.status() + "\",\"message_id\":\"m\",\"event_id\":\"e\"}",
                    null).type()).as(c.status()).isEqualTo(c.type());
        }
        // status matching is case-insensitive
        assertThat(normalize("simulated-email", "{\"event\":\"DELIVERED\",\"message_id\":\"m\",\"event_id\":\"e\"}", null)
                .type()).isEqualTo(DeliveryEventType.DELIVERED);
    }

    @Test
    void smsStatusesFollowSmppStyleCodes() {
        record Case(String status, DeliveryEventType type) {}
        for (Case c : List.of(new Case("ACCEPTD", DeliveryEventType.PROVIDER_ACCEPTED),
                new Case("SENT", DeliveryEventType.SENT), new Case("DELIVRD", DeliveryEventType.DELIVERED),
                new Case("UNDELIV", DeliveryEventType.FAILED), new Case("REJECTD", DeliveryEventType.REJECTED),
                new Case("DEFERRD", DeliveryEventType.DEFERRED))) {
            NormalizedEvent e = normalize("simulated-sms", "{\"status\":\"" + c.status()
                    + "\",\"msg_id\":\"sm-1\",\"event_id\":\"e1\",\"ts\":\"2026-10-18T10:15:30Z\",\"err\":\"X1\"}", null);
            assertThat(e.type()).as(c.status()).isEqualTo(c.type());
            assertThat(e.providerMessageId()).isEqualTo("sm-1");
            assertThat(e.reasonCode()).isEqualTo("X1");
        }
    }

    @Test
    void pushStatusesAndTokenInvalidReason() {
        assertThat(normalize("simulated-push", "{\"type\":\"delivered\",\"notification_id\":\"pn-1\",\"id\":\"e1\"}", null)
                .type()).isEqualTo(DeliveryEventType.DELIVERED);
        assertThat(normalize("simulated-push", "{\"type\":\"accepted\",\"notification_id\":\"pn-1\",\"id\":\"e1\"}", null)
                .type()).isEqualTo(DeliveryEventType.PROVIDER_ACCEPTED);
        NormalizedEvent invalid = normalize("simulated-push",
                "{\"type\":\"token_invalid\",\"notification_id\":\"pn-1\",\"id\":\"e1\",\"error\":\"whatever\"}", null);
        assertThat(invalid.type()).isEqualTo(DeliveryEventType.FAILED);
        assertThat(invalid.reasonCode()).isEqualTo("TOKEN_INVALID");
        assertThat(normalize("simulated-push", "{\"type\":\"opened\",\"notification_id\":\"pn-1\",\"id\":\"e1\"}", null)
                .type()).isEqualTo(DeliveryEventType.OPENED);
    }

    @Test
    void acmeUsesItsOwnShapeWithEpochSecondsAndNoIdempotencyEcho() {
        NormalizedEvent ok = normalize("acme-sms", "{\"ref\":\"acme-9\",\"state\":\"ok\",\"ts\":1792318530,\"id\":\"a-1\"}", null);
        assertThat(ok.type()).isEqualTo(DeliveryEventType.DELIVERED);
        assertThat(ok.providerMessageId()).isEqualTo("acme-9");
        assertThat(ok.idempotencyRef()).isNull();
        assertThat(ok.occurredAt()).isEqualTo(Instant.ofEpochSecond(1792318530L));
        assertThat(normalize("acme-sms", "{\"ref\":\"acme-9\",\"state\":\"fail\",\"id\":\"a-2\"}", null).type())
                .isEqualTo(DeliveryEventType.FAILED);
        assertThat(normalize("acme-sms", "{\"ref\":\"acme-9\",\"state\":\"queued\",\"id\":\"a-3\"}", null).type())
                .isEqualTo(DeliveryEventType.PROVIDER_ACCEPTED);
    }

    // ------------------------------------------------------------------ defaults and fallbacks

    @Test
    void theHeaderEventIdAndReceiveTimeFillInForMissingPayloadFields() {
        NormalizedEvent e = normalize("simulated-email", "{\"event\":\"delivered\",\"message_id\":\"m\"}", "hdr-7");
        assertThat(e.providerEventId()).isEqualTo("hdr-7");
        assertThat(e.occurredAt()).isEqualTo(RECEIVED);
        // the payload id wins over the header when both exist
        assertThat(normalize("simulated-email", "{\"event\":\"delivered\",\"message_id\":\"m\",\"event_id\":\"p-1\"}", "hdr-8")
                .providerEventId()).isEqualTo("p-1");
    }

    @Test
    void anIdempotencyRefAloneIsEnoughToLocateTheMessage() {
        NormalizedEvent e = normalize("simulated-email", "{\"event\":\"delivered\",\"event_id\":\"e\","
                + "\"meta\":{\"idempotencyRef\":\"abc:2\"}}", null);
        assertThat(e.providerMessageId()).isNull();
        assertThat(e.idempotencyRef()).isEqualTo("abc:2");
    }

    // ------------------------------------------------------------------ rejection

    @Test
    void unintelligiblePayloadsAreRejectedWithASafeReason() {
        assertMalformed("simulated-email", "not json", null, "NOT_JSON");
        assertMalformed("simulated-email", "", null, "NOT_A_JSON_OBJECT");
        assertMalformed("simulated-email", "[]", null, "NOT_A_JSON_OBJECT");
        assertMalformed("simulated-email", "\"text\"", null, "NOT_A_JSON_OBJECT");
        assertMalformed("simulated-email", "{\"event\":\"melted\",\"message_id\":\"m\",\"event_id\":\"e\"}", null,
                "UNKNOWN_STATUS");
        assertMalformed("simulated-email", "{\"message_id\":\"m\",\"event_id\":\"e\"}", null, "MISSING_STATUS");
        assertMalformed("simulated-email", "{\"event\":\"delivered\",\"event_id\":\"e\"}", null,
                "MISSING_MESSAGE_REFERENCE");
        assertMalformed("simulated-email", "{\"event\":\"delivered\",\"message_id\":\"m\"}", null, "MISSING_EVENT_ID");
        assertMalformed("simulated-email", "{\"event\":\"delivered\",\"message_id\":\"m\",\"event_id\":\"e\","
                + "\"timestamp\":\"yesterday\"}", null, "BAD_TIMESTAMP");
        assertMalformed("simulated-email", "{\"event\":\"delivered\",\"message_id\":\"has space\",\"event_id\":\"e\"}", null,
                "BAD_ID:message_id");
        assertMalformed("simulated-email", "{\"event\":{\"x\":1},\"message_id\":\"m\",\"event_id\":\"e\"}", null,
                "FIELD_NOT_SCALAR:event");
        assertMalformed("acme-sms", "{\"ref\":\"a\",\"state\":\"ok\",\"ts\":\"noon\",\"id\":\"e\"}", null, "BAD_TIMESTAMP");
        assertMalformed("acme-sms", "{\"state\":\"ok\",\"id\":\"e\"}", null, "MISSING_MESSAGE_REFERENCE");
        assertMalformed("simulated-sms", "{\"status\":\"WHAT\",\"msg_id\":\"m\",\"event_id\":\"e\"}", null, "UNKNOWN_STATUS");
    }

    @Test
    void theExceptionNeverEchoesPayloadContent() {
        assertThatThrownBy(() -> normalize("simulated-email", "{\"event\":\"alice@example.test\"}", null))
                .isInstanceOf(MalformedCallbackException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("alice").doesNotContain("example"));
    }

    @Test
    void aProviderWithoutAMapperIsRejectedAndTwoMappersForOneProviderAreAConfigurationError() {
        assertMalformed("vendor-without-mapper", "{}", null, "NO_MAPPER_FOR_PROVIDER");
        assertThat(normalizer.mapperFor("acme-sms")).isPresent();
        assertThat(normalizer.mapperFor("nope")).isEmpty();
        assertThatThrownBy(() -> new EventNormalizer(List.of(new AcmeSmsPayloadMapper(), new AcmeSmsPayloadMapper()),
                new ObjectMapper())).isInstanceOf(IllegalStateException.class).hasMessageContaining("acme-sms");
    }

    private void assertMalformed(String provider, String body, String header, String reason) {
        assertThatThrownBy(() -> normalize(provider, body, header)).as(body).isInstanceOf(MalformedCallbackException.class)
                .hasMessage(reason);
    }

    // ------------------------------------------------------------------ rendering is the inverse of mapping

    @Test
    void renderedVendorPayloadsMapBackToTheSameEvent() {
        Instant at = Instant.parse("2026-10-18T10:15:30Z");
        for (String provider : List.of("simulated-email", "simulated-sms", "simulated-push", "acme-sms")) {
            ProviderPayloadMapper mapper = normalizer.mapperFor(provider).orElseThrow();
            for (DeliveryEventType type : List.of(DeliveryEventType.PROVIDER_ACCEPTED, DeliveryEventType.SENT,
                    DeliveryEventType.DELIVERED)) {
                String json = mapper.render(new CallbackSpec("evt-" + type, type, "pm-1", "msg-1:1", at, null));
                NormalizedEvent e = normalize(provider, json, null);
                assertThat(e.type()).as(provider + " " + type).isEqualTo(type);
                assertThat(e.providerEventId()).isEqualTo("evt-" + type);
                assertThat(e.providerMessageId()).isEqualTo("pm-1");
                assertThat(e.occurredAt()).isEqualTo(at);
            }
        }
        // negative terminal events as well, where the vendor has a status for them
        assertThat(normalize("simulated-email", normalizer.mapperFor("simulated-email").orElseThrow().render(new CallbackSpec(
                "e", DeliveryEventType.BOUNCED, "pm", "r:1", at, null)), null).type()).isEqualTo(DeliveryEventType.BOUNCED);
        assertThat(normalize("simulated-sms", normalizer.mapperFor("simulated-sms").orElseThrow().render(new CallbackSpec(
                "e", DeliveryEventType.FAILED, "pm", "r:1", at, "E9")), null).reasonCode()).isEqualTo("E9");
        assertThat(normalize("acme-sms", normalizer.mapperFor("acme-sms").orElseThrow().render(new CallbackSpec(
                "e", DeliveryEventType.FAILED, "pm", null, at, null)), null).type()).isEqualTo(DeliveryEventType.FAILED);
    }

    @Test
    void renderingATypeTheVendorCannotExpressIsAnError() {
        ProviderPayloadMapper acme = normalizer.mapperFor("acme-sms").orElseThrow();
        assertThatThrownBy(() -> acme.render(new CallbackSpec("e", DeliveryEventType.OPENED, "pm", null, RECEIVED, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
