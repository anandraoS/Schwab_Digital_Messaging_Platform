package com.example.messaging.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.messaging.common.Ids;
import com.example.messaging.domain.DeliveryEventType;
import com.example.messaging.provider.ProviderRequest;
import com.example.messaging.provider.simulator.FaultInjector.Mode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/** Channel specific dispatch behaviour: rendering, fallback with consent, suppression, policy at dispatch time. */
class ChannelDispatchIT extends AbstractPipelineIT {

    // ------------------------------------------------------------------ channels and rendering

    @Test
    void everyChannelIsRenderedForItsProviderAndDeliveredThroughItsOwnCallbackFormat() throws Exception {
        String rcp = newRecipient(RETAIL);
        String emailId = submit(rcp, "EMAIL");
        String smsId = submit(rcp, "SMS");
        String pushId = submitOk(sender(RETAIL), pushRequest(rcp));
        for (String id : List.of(emailId, smsId, pushId)) {
            awaitStatus(id, "SENT_TO_PROVIDER");
        }
        ProviderRequest e = email.lastRequest(ref(emailId)).orElseThrow();
        assertThat(e.html()).isTrue();
        assertThat(e.subject()).isEqualTo("Trade confirmation for account ending 1234");
        assertThat(e.body()).contains("Your order to buy 10 shares of ACME was executed at 250.00");
        assertThat(e.to()).isEqualTo(rcp + "@example.test");
        assertThat(e.idempotencyRef()).isEqualTo(ref(emailId));
        assertThat(e.toString()).doesNotContain("example.test").doesNotContain("ACME"); // masked in any log line
        ProviderRequest s = sms.lastRequest(ref(smsId)).orElseThrow();
        assertThat(s.html()).isFalse();
        assertThat(s.subject()).isNull();
        assertThat(s.body()).isEqualTo("Trade confirmed: buy 10 ACME at 250.00 (acct 1234)");
        assertThat(s.to()).startsWith("+1555");
        ProviderRequest p = push.lastRequest(ref(pushId)).orElseThrow();
        assertThat(p.subject()).isEqualTo("Welcome, Test");
        assertThat(p.body()).isEqualTo("Your account is ready to use.");
        assertThat(p.to()).isEqualTo("push-" + rcp);

        assertThat(sendCallback(emailId, DeliveryEventType.DELIVERED, uniqueEventId()).result()).isEqualTo("accepted");
        assertThat(sendCallback(smsId, DeliveryEventType.DELIVERED, uniqueEventId()).result()).isEqualTo("accepted");
        assertThat(sendCallback(pushId, DeliveryEventType.DELIVERED, uniqueEventId()).result()).isEqualTo("accepted");
        for (String id : List.of(emailId, smsId, pushId)) {
            assertThat(statusOf(id)).isEqualTo("DELIVERED");
        }
    }

    @Test
    void channelOptionsReachTheProviderAsFlatOptions() throws Exception {
        String rcp = newRecipient(RETAIL);
        Map<String, Object> body = request(rcp, "EMAIL");
        body.put("channelOptions", Map.of("email", Map.of("replyTo", "support@example.test",
                "headers", Map.of("X-Campaign", "spring"))));
        String id = submitOk(sender(RETAIL), body);
        awaitStatus(id, "SENT_TO_PROVIDER");
        Map<String, String> options = email.lastRequest(ref(id)).orElseThrow().options();
        assertThat(options).containsEntry("replyTo", "support@example.test").containsEntry("headers.X-Campaign", "spring");
    }

    @Test
    void dispatchRendersTheVersionPinnedAtAcceptTimeNotTheLatest() throws Exception {
        quiesce();
        String rcp = newRecipient(RETAIL);
        String id = submit(rcp, "EMAIL");
        awaitStatus(id, "QUEUED");
        // a new version is published and the pinned one retired while the message waits in the queue
        jdbc.update("insert into template_version (id, template_id, version_no, locale, status, subject, body, "
                + "variables_json, created_by, approved_by, published_at, created_at, updated_at, version) values "
                + "('tplv-001-en-v2','tpl-001',2,'en','PUBLISHED','NEW SUBJECT {{accountLast4}}','NEW BODY {{symbol}}', "
                + "'[\"accountLast4\",\"symbol\"]','a','b',current_timestamp,current_timestamp,current_timestamp,0)");
        jdbc.update("update template_version set status = 'RETIRED' where id = 'tplv-001-en'");
        try {
            bus.resume();
            awaitStatus(id, "SENT_TO_PROVIDER");
            ProviderRequest r = email.lastRequest(ref(id)).orElseThrow();
            assertThat(r.body()).contains("Your order to buy").doesNotContain("NEW BODY");
            assertThat(r.subject()).doesNotContain("NEW SUBJECT");
        } finally {
            jdbc.update("update template_version set status = 'PUBLISHED' where id = 'tplv-001-en'");
            jdbc.update("delete from template_version where id = 'tplv-001-en-v2'");
        }
    }

    // ------------------------------------------------------------------ fallback

    @Test
    void permanentFailureFallsBackToTheNextChannelWhenConsentAllows() throws Exception {
        faults.set("simulated-email", Mode.PERMANENT, 1.0);
        String id = submitWithFallback(newRecipient(RETAIL), "EMAIL", "SMS");
        awaitStatus(id, "SENT_TO_PROVIDER");

        List<Map<String, Object>> attempts = attemptList(id);
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0)).containsEntry("channel", "EMAIL").containsEntry("outcome", "PERMANENT_FAILURE");
        assertThat(attempts.get(1)).containsEntry("channel", "SMS").containsEntry("outcome", "ACCEPTED")
                .containsEntry("provider_id", "simulated-sms");
        assertThat(row(id)).containsEntry("channel_used", "SMS");
        assertThat(((Number) row(id).get("fallback_index")).intValue()).isEqualTo(1);
        assertThat(auditCount(id, "MESSAGE_FALLBACK")).isEqualTo(1);
        // a new attempt group: the provider sees a different idempotency reference per channel
        assertThat(sms.lastRequest(id + ":2")).isPresent();
        assertThat(sms.lastRequest(id + ":2").orElseThrow().body()).startsWith("Trade confirmed:");
        assertThat(sendCallback(id, DeliveryEventType.DELIVERED, uniqueEventId()).status()).isEqualTo(202);
        assertThat(statusOf(id)).isEqualTo("DELIVERED");
    }

    @Test
    void fallbackNeverBypassesConsentOrSuppression() throws Exception {
        String rcp = newRecipient(RETAIL);
        String phone = jdbc.queryForObject("select phone_e164 from recipient where id = ?", String.class, rcp);
        jdbc.update("insert into suppression (id, tenant_id, channel, address_hash, reason, created_at) values "
                + "(?,?,?,?,?,?)", Ids.newId(), RETAIL, "SMS", Ids.addressHash(phone), "COMPLAINT",
                utc(Instant.now()));
        faults.set("simulated-email", Mode.PERMANENT, 1.0);
        String id = submitWithFallback(rcp, "EMAIL", "SMS");
        awaitStatus(id, "FAILED");
        assertThat(attemptRows(id)).isEqualTo(1);
        assertThat(sms.callCount()).isZero(); // the suppressed fallback channel is never used
        assertThat(auditCount(id, "MESSAGE_FALLBACK")).isZero();
    }

    @Test
    void exhaustedRetriesFallBackWithAFreshBudgetAndDeadLetterOnlyWhenTheFallbackFailsToo() throws Exception {
        faults.set("simulated-email", Mode.TRANSIENT, 1.0);
        String ok = submitWithFallback(newRecipient(RETAIL), "EMAIL", "SMS");
        awaitStatus(ok, "SENT_TO_PROVIDER");
        assertThat(attemptList(ok).stream().filter(a -> "EMAIL".equals(a.get("channel"))).count()).isEqualTo(4);
        assertThat(attemptList(ok).get(4)).containsEntry("channel", "SMS").containsEntry("outcome", "ACCEPTED");

        faults.set("simulated-sms", Mode.TRANSIENT, 1.0);
        String dead = submitWithFallback(newRecipient(RETAIL), "EMAIL", "SMS");
        awaitStatus(dead, "DEAD_LETTERED");
        assertThat(attemptRows(dead)).isEqualTo(8); // 4 on each channel
        assertThat(attemptCount(dead)).isEqualTo(8);
        assertThat(row(dead)).containsEntry("dlq_reason", "RETRIES_EXHAUSTED");
    }

    // ------------------------------------------------------------------ suppression

    @Test
    void hardBounceAtSendTimeSuppressesTheAddressAndLaterSubmissionsAreRejected() throws Exception {
        String rcp = newRecipient(RETAIL, "bouncer-" + UUID.randomUUID().toString().substring(0, 8) + "+hardbounce@example.test");
        String id = submit(rcp, "EMAIL");
        awaitStatus(id, "FAILED");
        assertThat(attemptList(id).get(0)).containsEntry("error_code", "HARD_BOUNCE");
        assertThat(jdbc.queryForObject("select count(*) from suppression where tenant_id = ? and channel = 'EMAIL' and "
                + "reason = 'HARD_BOUNCE' and address_hash = ?", Integer.class, RETAIL, Ids.addressHash(
                jdbc.queryForObject("select email from recipient where id = ?", String.class, rcp)))).isEqualTo(1);
        // the audit trail names the channel and message, never the address
        String audit = jdbc.queryForObject("select detail_json from audit_event where action = 'SUPPRESSION_ADDED' "
                + "order by occurred_at desc limit 1", String.class);
        assertThat(audit).contains(id).doesNotContain("example.test");
        // the next submission to that address is refused by policy at accept time
        String next = submit(rcp, "EMAIL");
        assertThat(statusOf(next)).isEqualTo("REJECTED");
        assertThat(row(next)).containsEntry("status_reason", "SUPPRESSED");
    }

    @Test
    void bouncedCallbackFailsTheMessageAndSuppressesTheAddress() throws Exception {
        String rcp = newRecipient(RETAIL);
        String id = submit(rcp, "EMAIL");
        awaitStatus(id, "SENT_TO_PROVIDER");
        assertThat(sendCallback(id, DeliveryEventType.BOUNCED, uniqueEventId()).result()).isEqualTo("accepted");
        assertThat(statusOf(id)).isEqualTo("FAILED");
        String again = submit(rcp, "EMAIL");
        assertThat(statusOf(again)).isEqualTo("REJECTED");
        // token_invalid on push flags the push token as suppressed
        String pushId = submitOk(sender(RETAIL), pushRequest(rcp));
        awaitStatus(pushId, "SENT_TO_PROVIDER");
        String body = "{\"type\":\"token_invalid\",\"notification_id\":\""
                + jdbc.queryForObject("select provider_message_id from message_attempt where message_id = ?",
                        String.class, pushId) + "\",\"id\":\"tok-" + pushId + "\"}";
        String sig = com.example.messaging.callback.SignatureVerifier.header(SECRET, Instant.now().getEpochSecond(),
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(postCallback("simulated-push", keyIdFor("simulated-push"), sig, "hdr-" + pushId, body).status())
                .isEqualTo(202);
        assertThat(statusOf(pushId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select count(*) from suppression where tenant_id = ? and channel = 'PUSH' and "
                + "reason = 'TOKEN_INVALID'", Integer.class, RETAIL)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void unsubscribeCallbackOptsTheRecipientOutOfMarketing() throws Exception {
        String rcp = newRecipient(RETAIL);
        jdbc.update("update recipient_preference set marketing_opt_in = true where recipient_id = ?", rcp);
        String id = submit(rcp, "EMAIL");
        awaitStatus(id, "SENT_TO_PROVIDER");
        assertThat(sendCallback(id, DeliveryEventType.UNSUBSCRIBED, uniqueEventId()).result()).isEqualTo("accepted");
        assertThat(jdbc.queryForObject("select marketing_opt_in from recipient_preference where recipient_id = ?",
                Boolean.class, rcp)).isFalse();
        assertThat(statusOf(id)).isEqualTo("SENT_TO_PROVIDER"); // engagement events never move the status
    }

    // ------------------------------------------------------------------ policy re-evaluation at dispatch time

    @Test
    void optOutAfterAcceptanceRejectsAtDispatchWithoutCallingTheProvider() throws Exception {
        String rcp = newRecipient(RETAIL);
        jdbc.update("update recipient_preference set marketing_opt_in = true where recipient_id = ?", rcp);
        quiesce();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipientId", rcp);
        body.put("channel", "EMAIL");
        body.put("category", "MARKETING");
        body.put("priority", "LOW");
        body.put("template", Map.of("code", "MARKETING_PROMO", "locale", "en-US"));
        body.put("variables", Map.of("firstName", "Test", "offerTitle", "Spring sale", "deadline", "Friday"));
        String id = submitOk(sender(RETAIL), body);
        awaitStatus(id, "QUEUED");
        jdbc.update("update recipient_preference set marketing_opt_in = false where recipient_id = ?", rcp);
        bus.resume();
        awaitStatus(id, "REJECTED");
        assertThat(row(id)).containsEntry("status_reason", "OPTED_OUT");
        assertThat(email.callCount()).isZero();
        assertThat(attemptRows(id)).isZero();
    }

    @Test
    void quietHoursEncounteredAtDispatchDeferThroughTheOutboxAndThenSend() throws Exception {
        String rcp = newRecipient(RETAIL);
        quiesce();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipientId", rcp);
        body.put("channel", "EMAIL");
        body.put("category", "SERVICE");
        body.put("priority", "NORMAL");
        body.put("template", Map.of("code", "MONTHLY_STATEMENT", "locale", "en-US"));
        body.put("variables", Map.of("firstName", "Test", "month", "October"));
        String id = submitOk(sender(RETAIL), body);
        awaitStatus(id, "QUEUED");
        // quiet hours begin while the message waits: [now-1h, now+1h) in the recipient's zone (UTC). Written through
        // JPA like the application does (raw JDBC would apply a different TIME zone convention).
        LocalTime now = LocalTime.now(ZoneOffset.UTC);
        var prefs = preferenceRepository.findById(rcp).orElseThrow();
        prefs.setQuietStart(now.minusHours(1));
        prefs.setQuietEnd(now.plusHours(1));
        preferenceRepository.save(prefs);
        bus.resume();
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("select count(*) from outbox_event "
                + "where aggregate_id = ? and status = 'NEW'", Integer.class, id) == 1);
        awaitIdle();
        assertThat(statusOf(id)).isEqualTo("QUEUED"); // deferred, not failed, not sent
        assertThat(email.callCount()).isZero();
        assertThat(attemptRows(id)).isZero();
        // the window ends: the outbox row becomes due and the message goes out
        clock.advance(Duration.ofHours(3));
        awaitStatus(id, "SENT_TO_PROVIDER");
        assertThat(email.callCount()).isEqualTo(1);
    }

    @Test
    void expiredMessagesFailWithoutCallingTheProvider() throws Exception {
        quiesce();
        Map<String, Object> body = request(newRecipient(RETAIL), "EMAIL");
        body.put("schedule", Map.of("expiresAt", Instant.now().plusSeconds(60).toString()));
        String id = submitOk(sender(RETAIL), body);
        awaitStatus(id, "QUEUED");
        clock.advance(Duration.ofMinutes(5));
        bus.resume();
        awaitStatus(id, "FAILED");
        assertThat(row(id)).containsEntry("status_reason", "EXPIRED");
        assertThat(email.callCount()).isZero();
    }

    @Test
    void cancelledMessagesAreSkippedByTheConsumer() throws Exception {
        quiesce();
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "QUEUED");
        mvc.perform(post("/api/v1/messages/" + id + "/cancel").header("Authorization", sender(RETAIL)))
                .andExpect(MockMvcResultMatchers.status().isOk());
        bus.resume();
        awaitIdle();
        assertThat(statusOf(id)).isEqualTo("CANCELLED");
        assertThat(email.callCount()).isZero();
        assertThat(attemptRows(id)).isZero();
    }

    @Test
    void outboxSkipsMessagesCancelledBeforeTheyBecameDue() throws Exception {
        Map<String, Object> body = request(newRecipient(RETAIL), "EMAIL");
        body.put("schedule", Map.of("sendAt", Instant.now().plusSeconds(3600).toString()));
        String id = submitOk(sender(RETAIL), body);
        mvc.perform(post("/api/v1/messages/" + id + "/cancel").header("Authorization", sender(RETAIL)))
                .andExpect(MockMvcResultMatchers.status().isOk());
        clock.advance(Duration.ofHours(2));
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("select status from outbox_event where "
                + "aggregate_id = ?", String.class, id).equals("SENT"));
        awaitIdle();
        assertThat(statusOf(id)).isEqualTo("CANCELLED");
        assertThat(email.callCount()).isZero();
    }

    // ------------------------------------------------------------------ pollers

    @Test
    void pollerRecoversARetryWhoseDelayedEnvelopeWasLost() throws Exception {
        quiesce();
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "QUEUED");
        // as if attempt 1 had failed transiently and its delayed redelivery had been lost with a restarted bus
        jdbc.update("update message_request set status = 'RETRY_PENDING', attempt_count = 1, next_attempt_at = ? "
                + "where id = ?", utc(Instant.now().minusSeconds(10)), id);
        // the scheduled poller (1 s tick) may win the race for this row; either way exactly one re-enqueue happens
        assertThat(poller.pollRetries()).isBetween(0, 1);
        bus.resume();
        awaitStatus(id, "SENT_TO_PROVIDER");
        assertThat(attemptList(id)).hasSize(1);
        assertThat(attemptList(id).get(0)).containsEntry("attempt_no", 2); // the poller continues at attemptCount + 1
        assertThat(email.callCount()).isEqualTo(1); // the stale envelope of attempt 1 was skipped
    }

    @Test
    void pollerRepairsAValidatedMessageThatLostItsOutboxRow() throws Exception {
        Map<String, Object> body = request(newRecipient(RETAIL), "EMAIL");
        body.put("schedule", Map.of("sendAt", Instant.now().plusSeconds(3600).toString()));
        String id = submitOk(sender(RETAIL), body);
        jdbc.update("update outbox_event set status = 'SENT' where aggregate_id = ?", id);
        jdbc.update("update message_request set scheduled_at = null, status_changed_at = ? where id = ?",
                utc(Instant.now().minus(Duration.ofMinutes(10))), id);
        assertThat(poller.pollOrphans()).isBetween(0, 1); // the scheduled poller may have repaired it first
        awaitStatus(id, "SENT_TO_PROVIDER");
        assertThat(email.callCount()).isEqualTo(1);
        // nothing left to repair
        assertThat(poller.pollOrphans()).isZero();
    }

    @Test
    void pollerRepublishesAStaleQueuedMessageAndTheIdempotentConsumerSendsOnce() throws Exception {
        quiesce();
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "QUEUED");
        // young message: nothing to do
        assertThat(poller.pollStaleQueued()).isZero();
        // as if its envelope had been lost with a restarted in-memory bus: old QUEUED row, no processed_message
        jdbc.update("update message_request set status_changed_at = ? where id = ?",
                utc(Instant.now().minus(Duration.ofMinutes(30))), id);
        // (the scheduled poller may win this race by a few ms: it makes the same single CAS-guarded publish)
        assertThat(poller.pollStaleQueued()).isBetween(0, 1);
        // the CAS moved the staleness clock: an immediate second poll must not publish again
        assertThat(poller.pollStaleQueued()).isZero();
        bus.resume();
        awaitStatus(id, "SENT_TO_PROVIDER");
        awaitIdle();
        assertThat(email.callCount()).isEqualTo(1); // original (still alive) envelope + re-published one: one send
        assertThat(attemptRows(id)).isEqualTo(1);
    }

    @Test
    void pollerLeavesQueuedMessagesParkedInTheOutboxAndAlreadyProcessedOnesAlone() throws Exception {
        quiesce();
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "QUEUED");
        jdbc.update("update message_request set status_changed_at = ? where id = ?",
                utc(Instant.now().minus(Duration.ofMinutes(30))), id);
        // parked (e.g. quiet-hours deferral): a NEW outbox row exists
        jdbc.update("update outbox_event set status = 'NEW', available_at = ? where aggregate_id = ?",
                utc(Instant.now().plus(Duration.ofHours(5))), id);
        assertThat(poller.pollStaleQueued()).isZero();
        jdbc.update("update outbox_event set status = 'SENT' where aggregate_id = ?", id);
        // a consumer already claimed dispatch attempt 1
        jdbc.update("insert into processed_message (id, dispatch_id, message_id, processed_at) values (?, ?, ?, ?)",
                java.util.UUID.randomUUID().toString(), id + ":1", id, utc(Instant.now()));
        assertThat(poller.pollStaleQueued()).isZero();
        bus.resume();
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.example.messaging.scheduler.ScheduledDispatchPoller poller;
    @org.springframework.beans.factory.annotation.Autowired
    com.example.messaging.repository.RecipientPreferenceRepository preferenceRepository;
}
