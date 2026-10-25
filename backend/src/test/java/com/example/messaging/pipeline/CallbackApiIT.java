package com.example.messaging.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.messaging.callback.SignatureVerifier;
import com.example.messaging.domain.DeliveryEventType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** The callback endpoint as a provider (or an attacker) sees it: authentication, replay, malformed input, tenancy. */
class CallbackApiIT extends AbstractPipelineIT {

    private static final String EMAIL = "simulated-email";

    private String sentMessage() throws Exception {
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "SENT_TO_PROVIDER");
        return id;
    }

    private String sig(String body) {
        return SignatureVerifier.header(SECRET, Instant.now().getEpochSecond(), body.getBytes(StandardCharsets.UTF_8));
    }

    private String sigAt(String body, Instant at) {
        return SignatureVerifier.header(SECRET, at.getEpochSecond(), body.getBytes(StandardCharsets.UTF_8));
    }

    private String delivered(String id) {
        return payload(id, DeliveryEventType.DELIVERED, uniqueEventId(), Instant.now());
    }

    private void assertUntouched(String id) {
        assertThat(statusOf(id)).isEqualTo("SENT_TO_PROVIDER");
        assertThat(eventList(id)).isEmpty();
    }

    // ------------------------------------------------------------------ authentication

    @Test
    void aBadSignatureIsRejectedAndChangesNothing() throws Exception {
        String id = sentMessage();
        double before = counter("messaging.callbacks", "result", "invalid_signature");
        String body = delivered(id);
        String good = sig(body);
        String bad = good.substring(0, good.length() - 1) + (good.endsWith("0") ? "1" : "0");
        CallbackResponse r = postCallback(EMAIL, keyIdFor(EMAIL), bad, uniqueEventId(), body);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.result()).isEqualTo("INVALID_SIGNATURE");
        assertThat(r.body()).doesNotContain(SECRET);
        assertUntouched(id);
        assertThat(counter("messaging.callbacks", "result", "invalid_signature") - before).isEqualTo(1.0);
    }

    @Test
    void aTamperedBodyIsRejectedBecauseTheSignatureCoversTheRawBytes() throws Exception {
        String id = sentMessage();
        String body = delivered(id);
        String sig = sig(body);
        assertThat(postCallback(EMAIL, keyIdFor(EMAIL), sig, uniqueEventId(), body.replace("delivered", "hard_bounce"))
                .status()).isEqualTo(401);
        // even insignificant whitespace changes the bytes
        assertThat(postCallback(EMAIL, keyIdFor(EMAIL), sig, uniqueEventId(), body + " ").status()).isEqualTo(401);
        assertUntouched(id);
    }

    @Test
    void timestampsOutsideTheToleranceAreRejectedWhileOnesInsideAreAccepted() throws Exception {
        String id = sentMessage();
        String body = delivered(id);
        assertThat(postCallback(EMAIL, keyIdFor(EMAIL), sigAt(body, Instant.now().minus(Duration.ofMinutes(10))),
                uniqueEventId(), body).status()).isEqualTo(401);
        assertThat(postCallback(EMAIL, keyIdFor(EMAIL), sigAt(body, Instant.now().plus(Duration.ofMinutes(10))),
                uniqueEventId(), body).status()).isEqualTo(401);
        assertUntouched(id);
        // 4 minutes of skew is within the 5 minute tolerance
        assertThat(postCallback(EMAIL, keyIdFor(EMAIL), sigAt(body, Instant.now().minus(Duration.ofMinutes(4))),
                uniqueEventId(), body).status()).isEqualTo(202);
        assertThat(statusOf(id)).isEqualTo("DELIVERED");
    }

    @Test
    void missingOrWrongHeadersAndKeysAreRejectedUniformly() throws Exception {
        String id = sentMessage();
        String body = delivered(id);
        String ok = sig(body);
        assertThat(postCallback(EMAIL, keyIdFor(EMAIL), null, uniqueEventId(), body).status()).isEqualTo(401);
        assertThat(postCallback(EMAIL, null, ok, uniqueEventId(), body).status()).isEqualTo(401);
        // a key belonging to another provider must not authenticate this provider
        assertThat(postCallback(EMAIL, keyIdFor("simulated-sms"), ok, uniqueEventId(), body).status()).isEqualTo(401);
        assertThat(postCallback(EMAIL, "no-such-key", ok, uniqueEventId(), body).status()).isEqualTo(401);
        // malformed signature headers
        for (String h : List.of("garbage", "t=abc,v1=00", "v1=" + "0".repeat(64), "t=" + Instant.now().getEpochSecond())) {
            assertThat(postCallback(EMAIL, keyIdFor(EMAIL), h, uniqueEventId(), body).status()).as(h).isEqualTo(401);
        }
        // an unknown provider (or a path trying something clever) gives the same answer as a bad signature
        assertThat(postCallback("no-such-provider", "no-such-provider-key", ok, uniqueEventId(), body).status())
                .isEqualTo(401);
        assertUntouched(id);
    }

    @Test
    void severalSignaturesInOneHeaderSupportKeyRotation() throws Exception {
        String id = sentMessage();
        String body = delivered(id);
        long t = Instant.now().getEpochSecond();
        String rotated = "t=" + t + ",v1=" + SignatureVerifier.sign("an-old-retired-secret", t,
                body.getBytes(StandardCharsets.UTF_8)) + ",v1=" + SignatureVerifier.sign(SECRET, t,
                body.getBytes(StandardCharsets.UTF_8));
        assertThat(postCallback(EMAIL, keyIdFor(EMAIL), rotated, uniqueEventId(), body).status()).isEqualTo(202);
        assertThat(statusOf(id)).isEqualTo("DELIVERED");
    }

    @Test
    void theCallbackEndpointNeedsNoBearerTokenButOnlyAcceptsPost() throws Exception {
        mvc.perform(get("/api/v1/callbacks/" + EMAIL)).andExpect(status().isMethodNotAllowed());
        // no Authorization header at all: still reaches the signature check (401 INVALID_SIGNATURE, not UNAUTHENTICATED)
        CallbackResponse r = postCallback(EMAIL, keyIdFor(EMAIL), "t=1,v1=00", "e-1", "{}");
        assertThat(r.result()).isEqualTo("INVALID_SIGNATURE");
    }

    // ------------------------------------------------------------------ replay and duplicates

    @Test
    void aReplayedEventIdIsAnsweredWithoutAnyStateChangeAndForgottenAfterTheReplayWindow() throws Exception {
        String id = sentMessage();
        String eventId = uniqueEventId();
        assertThat(sendCallback(id, DeliveryEventType.DELIVERED, eventId).status()).isEqualTo(202);
        CallbackResponse replay = sendCallback(id, DeliveryEventType.DELIVERED, eventId);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.result()).isEqualTo("replayed");
        assertThat(eventList(id)).hasSize(1);
        // the same X-Event-Id from another provider is a different event
        String smsId = submit(newRecipient(RETAIL), "SMS");
        awaitStatus(smsId, "SENT_TO_PROVIDER");
        assertThat(sendCallback(smsId, DeliveryEventType.DELIVERED, eventId).result()).isEqualTo("accepted");
        // after 25 hours the guard has forgotten it; the unique (provider, event) key still catches the duplicate
        clock.advance(Duration.ofHours(25));
        CallbackResponse late = sendCallback(id, DeliveryEventType.DELIVERED, eventId);
        assertThat(late.status()).isEqualTo(202);
        assertThat(late.result()).isEqualTo("duplicate");
        assertThat(eventList(id)).hasSize(1);
    }

    // ------------------------------------------------------------------ malformed

    @Test
    void authenticButUnintelligiblePayloadsAreBadRequestsAndNeverEchoed() throws Exception {
        String id = sentMessage();
        double before = counter("messaging.callbacks", "result", "malformed");
        Map<String, String> cases = Map.of(
                "not json", "this is not json at all alice@example.test",
                "array", "[1,2,3]",
                "unknown status", "{\"event\":\"exploded\",\"message_id\":\"x\",\"event_id\":\"e-1\"}",
                "no status", "{\"message_id\":\"x\",\"event_id\":\"e-2\"}",
                "no reference", "{\"event\":\"delivered\",\"event_id\":\"e-3\"}",
                "bad timestamp", "{\"event\":\"delivered\",\"message_id\":\"x\",\"event_id\":\"e-4\",\"timestamp\":\"yesterday\"}",
                "bad id characters", "{\"event\":\"delivered\",\"message_id\":\"x y<script>\",\"event_id\":\"e-5\"}");
        for (var c : cases.entrySet()) {
            CallbackResponse r = postCallback(EMAIL, keyIdFor(EMAIL), sig(c.getValue()), "hdr-" + c.getKey().hashCode(),
                    c.getValue());
            assertThat(r.status()).as(c.getKey()).isEqualTo(400);
            assertThat(r.result()).as(c.getKey()).isEqualTo("MALFORMED_REQUEST");
            assertThat(r.body()).as(c.getKey()).doesNotContain("alice").doesNotContain("exploded").doesNotContain("script");
        }
        assertThat(counter("messaging.callbacks", "result", "malformed") - before).isEqualTo((double) cases.size());
        // a missing X-Event-Id is a validation error
        String body = delivered(id);
        CallbackResponse noEventId = postCallback(EMAIL, keyIdFor(EMAIL), sig(body), null, body);
        assertThat(noEventId.status()).isEqualTo(400);
        assertUntouched(id);
    }

    @Test
    void oversizedBodiesAreRejectedBeforeAnythingIsParsed() throws Exception {
        String big = "{\"event\":\"delivered\",\"pad\":\"" + "x".repeat(300_000) + "\"}";
        MvcResult r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/callbacks/" + EMAIL).contentType(MediaType.APPLICATION_JSON)
                .header("X-Signature", sig(big)).header("X-Provider-Key-Id", keyIdFor(EMAIL)).header("X-Event-Id", "big-1")
                .content(big.getBytes(StandardCharsets.UTF_8))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(413);
    }

    @Test
    void anEventForAnUnknownMessageIsAcknowledgedButNotRememberedSoARetryCanStillSucceed() throws Exception {
        String body = "{\"event\":\"delivered\",\"message_id\":\"em-never-sent\",\"event_id\":\"evt-ghost\"}";
        CallbackResponse first = postCallback(EMAIL, keyIdFor(EMAIL), sig(body), "hdr-ghost", body);
        CallbackResponse second = postCallback(EMAIL, keyIdFor(EMAIL), sig(body), "hdr-ghost", body);
        assertThat(first.status()).isEqualTo(202);
        assertThat(first.result()).isEqualTo("unknown_message");
        assertThat(second.result()).isEqualTo("unknown_message"); // not "replayed": the guard did not record it
    }

    // ------------------------------------------------------------------ tenants and diagnostics

    @Test
    void deliveryEventsAndDiagnosticsAreTenantScoped() throws Exception {
        String id = sentMessage();
        assertThat(sendCallback(id, DeliveryEventType.DELIVERED, uniqueEventId()).status()).isEqualTo(202);

        mvc.perform(get("/api/v1/delivery-events").param("messageId", id).header("Authorization", operator(RETAIL)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("DELIVERED"))
                .andExpect(jsonPath("$.content[0].applied").value(true));
        mvc.perform(get("/api/v1/delivery-events").param("messageId", id).header("Authorization", operator(WEALTH)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
        mvc.perform(get("/api/v1/diagnostics/messages/" + id).header("Authorization", operator(WEALTH)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/diagnostics/messages/" + id).header("Authorization", operator(RETAIL)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.kind=='SEND')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.kind=='CALLBACK')]").isNotEmpty());
    }

    @Test
    void rawCallbackPayloadsAreArchivedRedactedAndFreeTextNeverReachesTheAuditTrail() throws Exception {
        String id = sentMessage();
        String body = "{\"event\":\"failed\",\"message_id\":\"" + jdbc.queryForObject("select provider_message_id from "
                + "message_attempt where message_id = ?", String.class, id) + "\",\"event_id\":\"evt-pii-" + id
                + "\",\"reason\":\"mailbox of alice@example.test is full, call +15550100123\"}";
        assertThat(postCallback(EMAIL, keyIdFor(EMAIL), sig(body), "hdr-pii-" + id, body).status()).isEqualTo(202);
        assertThat(statusOf(id)).isEqualTo("FAILED");

        String docs = mvc.perform(get("/api/v1/diagnostics/messages/" + id).header("Authorization", operator(RETAIL)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(docs).doesNotContain("alice@example.test").doesNotContain("+15550100123").contains("***");
        String audit = String.join(" ", jdbc.queryForList("select detail_json from audit_event where entity_id = ?",
                String.class, id));
        assertThat(audit).doesNotContain("alice").doesNotContain("15550100123");
        // the raw reference on the stored delivery event points at the archived document
        assertThat(jdbc.queryForObject("select raw_ref from delivery_event where message_id = ?", String.class, id))
                .isNotBlank();
    }
}
