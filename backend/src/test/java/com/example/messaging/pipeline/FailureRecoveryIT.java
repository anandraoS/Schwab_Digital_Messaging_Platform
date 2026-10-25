package com.example.messaging.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.messaging.domain.DeliveryEventType;
import com.example.messaging.domain.MessageStateMachine;
import com.example.messaging.domain.MessageStatus;
import com.example.messaging.messaging.DispatchEnvelope;
import com.example.messaging.provider.ProviderStatus;
import com.example.messaging.domain.Channel;
import com.example.messaging.domain.Priority;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The ten reliability scenarios of docs/architecture/reliability-scenarios.md, each driven through the real pipeline
 * with simulators, an adjustable clock and fault switches. Scenario 9 (bulk job restart) belongs to the Spring Batch
 * module and is intentionally absent here.
 */
class FailureRecoveryIT extends AbstractPipelineIT {

    // ------------------------------------------------------------------ helpers

    /** Every recorded transition of the message must be a legal pair of the state table. */
    private void assertOnlyLegalTransitions(String messageId) throws Exception {
        List<String> details = jdbc.queryForList("select detail_json from audit_event where entity_id = ? and "
                + "action = 'MESSAGE_TRANSITION' order by occurred_at", String.class, messageId);
        assertThat(details).isNotEmpty();
        for (String d : details) {
            JsonNode n = om.readTree(d);
            MessageStatus from = MessageStatus.valueOf(n.get("from").asText());
            MessageStatus to = MessageStatus.valueOf(n.get("to").asText());
            assertThat(MessageStateMachine.canTransition(from, to) || MessageStateMachine.canTransitionViaReceipt(from, to))
                    .as("transition %s -> %s of %s", from, to, messageId).isTrue();
        }
    }

    private List<String> transitionTargets(String messageId) throws Exception {
        List<String> out = new ArrayList<>();
        for (String d : jdbc.queryForList("select detail_json from audit_event where entity_id = ? and "
                + "action = 'MESSAGE_TRANSITION' order by occurred_at", String.class, messageId)) {
            out.add(om.readTree(d).get("to").asText());
        }
        return out;
    }

    private void useProviderTimeout(String providerId, int ms) {
        jdbc.update("update provider_config set timeout_ms = ? where provider_id = ?", ms, providerId);
        registry.invalidate();
    }

    // ------------------------------------------------------------------ 1

    @Test
    void scenario01_providerOutage_retriesWithBackoff_thenDeadLetters_thenReplay() throws Exception {
        double retriesBefore = counter("messaging.retry.scheduled", "channel", "EMAIL");
        double dlqBefore = counter("messaging.deadletter.total", "reason", "RETRIES_EXHAUSTED");
        faults.set("simulated-email", com.example.messaging.provider.simulator.FaultInjector.Mode.TRANSIENT, 1.0);

        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "DEAD_LETTERED");

        // max-attempts=4 in this context: one attempt row per try, attempt_count matches
        List<Map<String, Object>> attempts = attemptList(id);
        assertThat(attempts).hasSize(4);
        assertThat(attempts).allSatisfy(a -> assertThat(a).containsEntry("outcome", "TRANSIENT_FAILURE")
                .containsEntry("state", "FINISHED").containsEntry("error_code", "SIM_UNAVAILABLE"));
        assertThat(attemptCount(id)).isEqualTo(4);
        assertThat(row(id)).containsEntry("dlq_reason", "RETRIES_EXHAUSTED");
        assertThat(email.callCount()).isEqualTo(4);
        // exponential backoff with jitter in [ceiling/2, ceiling] (base 40 ms): gaps are at least half the ceiling
        List<Instant> starts = jdbc.queryForList("select started_at from message_attempt where message_id = ? order by "
                + "attempt_no", Timestamp.class, id).stream().map(Timestamp::toInstant).toList();
        long[] ceilings = {40, 80, 160};
        for (int i = 0; i < 3; i++) {
            long gap = Duration.between(starts.get(i), starts.get(i + 1)).toMillis();
            assertThat(gap).as("gap before attempt %d", i + 2).isGreaterThanOrEqualTo(ceilings[i] / 2 - 10);
        }
        // fewer than the breaker's minimum number of calls: the circuit stays closed
        assertThat(invoker.state("simulated-email").name()).isEqualTo("CLOSED");
        // observability: counters, DLQ, ordered legal transitions without skips
        assertThat(counter("messaging.retry.scheduled", "channel", "EMAIL") - retriesBefore).isEqualTo(3.0);
        assertThat(counter("messaging.deadletter.total", "reason", "RETRIES_EXHAUSTED") - dlqBefore).isEqualTo(1.0);
        assertThat(bus.deadLetters()).anyMatch(d -> id.equals(d.envelope().messageId()));
        assertThat(bus.stats().dlqDepth()).isGreaterThanOrEqualTo(1);
        assertOnlyLegalTransitions(id);
        assertThat(transitionTargets(id)).containsSubsequence("QUEUED", "PROCESSING", "RETRY_PENDING", "QUEUED",
                "PROCESSING", "RETRY_PENDING", "DEAD_LETTERED");

        // provider recovers; the operator replays through the API
        faults.reset();
        mvc.perform(post("/api/v1/dead-letters/" + id + "/replay").header("Authorization", operator(RETAIL)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        awaitStatus(id, "SENT_TO_PROVIDER");
        assertThat(attemptRows(id)).isEqualTo(5);
        assertThat(attemptList(id).get(4)).containsEntry("outcome", "ACCEPTED");
        assertThat(row(id)).containsEntry("dlq_reason", null);
        assertThat(((Number) row(id).get("max_attempts")).intValue()).isEqualTo(8); // fresh budget of 4 on top of 4
        assertThat(auditCount(id, "MESSAGE_REPLAYED")).isEqualTo(1);
        assertThat(auditCount(id, "REPLAY_PRECHECK")).isEqualTo(1);
        sendCallback(id, DeliveryEventType.DELIVERED, uniqueEventId());
        assertThat(statusOf(id)).isEqualTo("DELIVERED");
        assertOnlyLegalTransitions(id);
    }

    // ------------------------------------------------------------------ 2

    @Test
    void scenario02_duplicateSubmission_sameKey_returnsOriginal_noSecondMessage() throws Exception {
        String auth = sender(RETAIL);
        String rcp = newRecipient(RETAIL);
        String key = newKey();
        Map<String, Object> body = request(rcp, "EMAIL");
        MvcResult first = mvc.perform(submit(auth, key, body)).andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(202);
        String id = json(first).get("id").asText();
        MvcResult second = mvc.perform(submit(auth, key, body)).andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
        assertThat(json(second).get("id").asText()).isEqualTo(id);
        assertThat(countByKey(key)).isEqualTo(1);

        // same key, different body: never silently accepted
        Map<String, Object> other = request(rcp, "EMAIL");
        other.put("clientReference", "something-else");
        MvcResult reuse = mvc.perform(submit(auth, key, other)).andReturn();
        assertThat(reuse.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(reuse).get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

        // N parallel identical requests with a fresh key: exactly one creation, the others replay or report in-progress
        String parallelKey = newKey();
        Map<String, Object> parallelBody = request(newRecipient(RETAIL), "EMAIL");
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<Integer> c = () -> {
                go.await();
                return mvc.perform(submit(auth, parallelKey, parallelBody)).andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(c));
        }
        go.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : results) {
            codes.add(f.get());
        }
        pool.shutdown();
        assertThat(codes).allMatch(c -> c == 202 || c == 200 || c == 409);
        assertThat(codes.stream().filter(c -> c == 202).count()).isEqualTo(1);
        assertThat(countByKey(parallelKey)).isEqualTo(1);
        // and one dispatch only
        String parallelId = jdbc.queryForObject("select id from message_request where idempotency_key = ?", String.class,
                parallelKey);
        awaitStatus(parallelId, "SENT_TO_PROVIDER");
        assertThat(attemptRows(parallelId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_record where idem_key = ?", Integer.class,
                parallelKey)).isEqualTo(1);
    }

    private int countByKey(String key) {
        return jdbc.queryForObject("select count(*) from message_request where idempotency_key = ?", Integer.class, key);
    }

    // ------------------------------------------------------------------ 3

    @Test
    void scenario03_duplicateAndOutOfOrderCallbacks_applyMonotonically() throws Exception {
        double replayed = counter("messaging.callbacks", "result", "replayed");
        double duplicate = counter("messaging.callbacks", "result", "duplicate");
        double stale = counter("messaging.callbacks", "result", "stale");
        double outOfOrder = counter("messaging.callbacks", "result", "out_of_order");
        double conflict = counter("messaging.callbacks", "result", "conflict");

        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "SENT_TO_PROVIDER");

        // DELIVERED arrives before SENT: applied, and the later (older) SENT must not regress the state
        String deliveredId = uniqueEventId();
        CallbackResponse r1 = sendCallback(id, DeliveryEventType.DELIVERED, deliveredId, Instant.now());
        assertThat(r1.status()).isEqualTo(202);
        assertThat(r1.result()).isEqualTo("accepted");
        assertThat(statusOf(id)).isEqualTo("DELIVERED");

        CallbackResponse late = sendCallback(id, DeliveryEventType.SENT, uniqueEventId(), Instant.now().minusSeconds(30));
        assertThat(late.status()).isEqualTo(202);
        assertThat(late.result()).isEqualTo("out_of_order");
        assertThat(statusOf(id)).isEqualTo("DELIVERED");

        // the provider retries the very same webhook (same X-Event-Id): no state change, no new rows
        int rowsBefore = eventList(id).size();
        CallbackResponse again = sendCallback(id, DeliveryEventType.DELIVERED, deliveredId, Instant.now());
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.result()).isEqualTo("replayed");
        assertThat(eventList(id)).hasSize(rowsBefore);

        // a second DELIVERED under a new event id is stale; a BOUNCED after DELIVERED is a conflict, first terminal wins
        assertThat(sendCallback(id, DeliveryEventType.DELIVERED, uniqueEventId()).result()).isEqualTo("stale");
        CallbackResponse bounced = sendCallback(id, DeliveryEventType.BOUNCED, uniqueEventId());
        assertThat(bounced.status()).isEqualTo(202);
        assertThat(bounced.result()).isEqualTo("conflict");
        assertThat(statusOf(id)).isEqualTo("DELIVERED");
        assertThat(auditCount(id, "DELIVERY_EVENT_CONFLICT")).isEqualTo(1);

        // same payload event id behind two different transport ids: the unique key catches it
        String payloadEventId = uniqueEventId();
        String body = payload(id, DeliveryEventType.OPENED, payloadEventId, Instant.now());
        String sig = com.example.messaging.callback.SignatureVerifier.header(SECRET, clock.instant().getEpochSecond(),
                body.getBytes(StandardCharsets.UTF_8));
        assertThat(postCallback("simulated-email", keyIdFor("simulated-email"), sig, uniqueEventId(), body).result())
                .isEqualTo("accepted");
        assertThat(postCallback("simulated-email", keyIdFor("simulated-email"), sig, uniqueEventId(), body).result())
                .isEqualTo("duplicate");

        List<Map<String, Object>> events = eventList(id);
        assertThat(events).filteredOn(e -> "OUT_OF_ORDER".equals(e.get("ignored_reason"))).hasSize(1);
        assertThat(events).filteredOn(e -> "STALE".equals(e.get("ignored_reason"))).hasSize(1);
        assertThat(events).filteredOn(e -> "CONFLICT".equals(e.get("ignored_reason"))).hasSize(1);
        assertThat(events).filteredOn(e -> Boolean.TRUE.equals(e.get("applied")) && "DELIVERED".equals(e.get("type")))
                .hasSize(1);
        assertThat(counter("messaging.callbacks", "result", "replayed") - replayed).isEqualTo(1.0);
        assertThat(counter("messaging.callbacks", "result", "duplicate") - duplicate).isEqualTo(1.0);
        assertThat(counter("messaging.callbacks", "result", "stale") - stale).isEqualTo(1.0);
        assertThat(counter("messaging.callbacks", "result", "out_of_order") - outOfOrder).isEqualTo(1.0);
        assertThat(counter("messaging.callbacks", "result", "conflict") - conflict).isEqualTo(1.0);
    }

    @Test
    void scenario03b_receiptThatOvertakesTheConsumerIsApplied() throws Exception {
        quiesce();
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "QUEUED");
        forceStatus(id, "PROCESSING"); // the consumer has claimed it but has not persisted the provider answer yet
        String body = "{\"event\":\"delivered\",\"event_id\":\"x-" + id + "\",\"meta\":{\"idempotencyRef\":\"" + ref(id)
                + "\"}}";
        String sig = com.example.messaging.callback.SignatureVerifier.header(SECRET, clock.instant().getEpochSecond(),
                body.getBytes(StandardCharsets.UTF_8));
        CallbackResponse r = postCallback("simulated-email", keyIdFor("simulated-email"), sig, "hdr-" + id, body);
        assertThat(r.status()).isEqualTo(202);
        assertThat(statusOf(id)).isEqualTo("DELIVERED");
        bus.resume(); // the redundant envelope is skipped: the message is already DELIVERED
        awaitIdle();
        assertThat(statusOf(id)).isEqualTo("DELIVERED");
        assertThat(email.callCount()).isZero();
    }

    // ------------------------------------------------------------------ 4

    @Test
    void scenario04_providerTimeout_movesToDeliveryUnknown_reconcilesWithoutDuplicateSend() throws Exception {
        useProviderTimeout("simulated-email", 300);
        faults.set("simulated-email", com.example.messaging.provider.simulator.FaultInjector.Mode.TIMEOUT, 1.0);
        double unknownBefore = counter("messaging.transitions", "to", "DELIVERY_UNKNOWN");

        // (a) the provider never saw the request: reconciliation says NOT_RECEIVED, one safe second call
        String a = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(a, "DELIVERY_UNKNOWN");
        assertThat(attemptList(a).get(0)).containsEntry("outcome", "TIMEOUT");
        Thread.sleep(500); // no blind retry while unknown
        assertThat(email.callCount()).isEqualTo(1);
        assertThat(statusOf(a)).isEqualTo("DELIVERY_UNKNOWN");
        assertThat(counter("messaging.transitions", "to", "DELIVERY_UNKNOWN") - unknownBefore).isGreaterThanOrEqualTo(1.0);
        faults.reset();
        assertThat(reconciliation.reconcileOne(a)).isEqualTo("NOT_RECEIVED");
        awaitStatus(a, "SENT_TO_PROVIDER");
        assertThat(email.callCount()).isEqualTo(2);
        assertThat(attemptList(a)).hasSize(2);
        assertThat(attemptList(a).get(1)).containsEntry("outcome", "ACCEPTED");
        assertThat(auditCount(a, "RECONCILIATION_CHECK")).isEqualTo(1);
        assertOnlyLegalTransitions(a);

        // (b) AMBIGUOUS: the vendor accepted but the answer was lost; lookup says ACCEPTED, no resend, the late callback wins
        faults.set("simulated-email", com.example.messaging.provider.simulator.FaultInjector.Mode.AMBIGUOUS, 1.0);
        String b = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(b, "DELIVERY_UNKNOWN");
        assertThat(attemptList(b).get(0)).containsEntry("outcome", "AMBIGUOUS");
        long callsAfterB = email.callCount();
        faults.reset();
        assertThat(reconciliation.reconcileOne(b)).isEqualTo("ACCEPTED");
        assertThat(statusOf(b)).isEqualTo("DELIVERY_UNKNOWN");
        assertThat(email.callCount()).isEqualTo(callsAfterB);
        assertThat(sendCallback(b, DeliveryEventType.DELIVERED, uniqueEventId()).status()).isEqualTo(202);
        assertThat(statusOf(b)).isEqualTo("DELIVERED");

        // (c) AMBIGUOUS and the provider's lookup reports it delivered: reconciliation applies it, with evidence
        faults.set("simulated-email", com.example.messaging.provider.simulator.FaultInjector.Mode.AMBIGUOUS, 1.0);
        String c = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(c, "DELIVERY_UNKNOWN");
        faults.reset();
        email.setState(ref(c), ProviderStatus.State.DELIVERED);
        assertThat(reconciliation.reconcileOne(c)).isEqualTo("DELIVERED");
        assertThat(statusOf(c)).isEqualTo("DELIVERED");
        assertThat(eventList(c)).anyMatch(e -> ((String) e.get("provider_event_id")).startsWith("recon:"));

        // (d) lookup reports failure
        faults.set("simulated-email", com.example.messaging.provider.simulator.FaultInjector.Mode.AMBIGUOUS, 1.0);
        String d = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(d, "DELIVERY_UNKNOWN");
        faults.reset();
        email.setState(ref(d), ProviderStatus.State.FAILED);
        assertThat(reconciliation.reconcileOne(d)).isEqualTo("FAILED");
        assertThat(statusOf(d)).isEqualTo("FAILED");

        // (e) PUSH may be retried: the collapse key / idempotency reference makes the resend harmless
        faults.set("simulated-push", com.example.messaging.provider.simulator.FaultInjector.Mode.AMBIGUOUS, 1.0);
        String p = submitOk(sender(RETAIL), pushRequest(newRecipient(RETAIL)));
        await().atMost(Duration.ofSeconds(15)).until(() -> !attemptList(p).isEmpty() && attemptList(p).get(0).get("outcome") != null); // answered (the attempt row may not exist yet)
        faults.reset();
        awaitStatus(p, "SENT_TO_PROVIDER");
        assertThat(push.callCount()).isEqualTo(2);
        assertThat(push.acceptedCount()).isEqualTo(1); // provider-side dedupe: one message, two calls
    }

    // ------------------------------------------------------------------ 5

    @Test
    void scenario05_consumerCrashAfterProviderAccept_sweeperMarksDeliveryUnknown() throws Exception {
        quiesce();
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "QUEUED");
        hook.crashAfterProviderCall.add(id);
        bus.resume();

        // the worker "dies" right after the provider accepted: PROCESSING stays, the attempt row stays STARTED
        await().atMost(Duration.ofSeconds(15)).until(() -> email.callCount() >= 1 && hook.deliveries.get(id) != null
                && hook.deliveries.get(id).get() >= 2);
        awaitIdle();
        assertThat(statusOf(id)).isEqualTo("PROCESSING");
        assertThat(attemptList(id).get(0)).containsEntry("state", "STARTED").containsEntry("outcome", null);
        assertThat(email.callCount()).isEqualTo(1);
        assertThat(email.find(ref(id))).isPresent(); // the provider really has it
        assertThat(row(id).get("locked_until")).isNotNull();

        // before the lock expires nothing happens
        assertThat(sweeper.sweep()).isZero();
        assertThat(statusOf(id)).isEqualTo("PROCESSING");

        // the lock TTL (2 min) passes: the sweeper marks it unknown, never retries blindly
        clock.advance(Duration.ofMinutes(3));
        awaitStatus(id, "DELIVERY_UNKNOWN");
        assertThat(auditCount(id, "STUCK_SWEEP")).isEqualTo(1);
        assertThat(email.callCount()).isEqualTo(1);
        assertThat(attemptList(id).get(0)).containsEntry("state", "STARTED");
        assertThat(attemptRows(id)).isEqualTo(1);
        assertOnlyLegalTransitions(id);

        // reconciliation then finds the provider holds it and the late receipt resolves it
        assertThat(reconciliation.reconcileOne(id)).isEqualTo("ACCEPTED");
        assertThat(sendCallback(id, DeliveryEventType.DELIVERED, uniqueEventId()).status()).isEqualTo(202);
        assertThat(statusOf(id)).isEqualTo("DELIVERED");
        assertThat(email.callCount()).isEqualTo(1);
    }

    @Test
    void scenario05b_sentToProviderWithoutReceiptBecomesUnknownAfterTheReceiptTimeout() throws Exception {
        String id = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(id, "SENT_TO_PROVIDER");
        clock.advance(Duration.ofMinutes(20)); // receipt-timeout is 15 minutes
        awaitStatus(id, "DELIVERY_UNKNOWN");
        assertThat(auditCount(id, "STUCK_SWEEP")).isEqualTo(1);
        assertThat(email.callCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 6

    @Test
    void scenario06_databaseOutage_submissionFailsClosed_thenRecovers() throws Exception {
        String auth = sender(RETAIL);
        String rcp = newRecipient(RETAIL);
        String key = newKey();
        Map<String, Object> body = request(rcp, "EMAIL");

        PipelineTestConfig.DbOutage.down = true;
        MvcResult down;
        try {
            down = mvc.perform(submit(auth, key, body)).andReturn();
            Thread.sleep(300); // schedulers keep ticking against the dead database without crashing
        } finally {
            PipelineTestConfig.DbOutage.down = false;
        }
        assertThat(down.getResponse().getStatus()).isEqualTo(503);
        assertThat(down.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(json(down).get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        // fail closed: nothing was committed
        assertThat(countByKey(key)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from idempotency_record where idem_key = ?", Integer.class, key))
                .isZero();

        // recovery: the client retries with the same key and gets exactly one message
        MvcResult retry = mvc.perform(submit(auth, key, body)).andReturn();
        assertThat(retry.getResponse().getStatus()).isEqualTo(202);
        String id = json(retry).get("id").asText();
        assertThat(countByKey(key)).isEqualTo(1);
        awaitStatus(id, "SENT_TO_PROVIDER");
        assertThat(attemptRows(id)).isEqualTo(1);
        assertThat(mvc.perform(submit(auth, key, body)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ 7

    @Test
    void scenario07_redisOutage_fallsBackToDb_noDuplicates() throws Exception {
        String auth = sender(RETAIL);
        Map<String, Object> body = request(newRecipient(RETAIL), "EMAIL");
        String key = newKey();
        double before = counter("messaging.redis.fallback");

        PipelineTestConfig.RedisOutage.down = true;
        MvcResult first = mvc.perform(submit(auth, key, body)).andReturn();
        MvcResult second = mvc.perform(submit(auth, key, body)).andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(202);
        assertThat(second.getResponse().getStatus()).isEqualTo(200); // the database unique key still dedupes
        assertThat(json(second).get("id").asText()).isEqualTo(json(first).get("id").asText());
        assertThat(countByKey(key)).isEqualTo(1);
        String id = json(first).get("id").asText();
        awaitStatus(id, "SENT_TO_PROVIDER"); // the provider rate limiter also degrades to the local bucket
        assertThat(counter("messaging.redis.fallback")).isGreaterThan(before);
        assertThat(email.callCount()).isEqualTo(1);

        // Redis is back: after the cool-down the primary is used again, so the fallback counter stops moving
        PipelineTestConfig.RedisOutage.down = false;
        Thread.sleep(500);
        mvc.perform(submit(auth, newKey(), request(newRecipient(RETAIL), "EMAIL"))).andReturn();
        double afterRecovery = counter("messaging.redis.fallback");
        awaitIdle();
        Thread.sleep(300);
        assertThat(counter("messaging.redis.fallback")).isEqualTo(afterRecovery);
    }

    // ------------------------------------------------------------------ 8

    @Test
    void scenario08_poisonMessage_goesToDlq_noInfiniteLoop() throws Exception {
        double poisonBefore = counter("messaging.deadletter.total", "reason", "POISON_MESSAGE");

        // (a) envelope for a message that does not exist, and an unreadable one
        DispatchEnvelope ghost = DispatchEnvelope.of("no-such-message-" + System.nanoTime(), RETAIL, Channel.EMAIL,
                Priority.NORMAL, 1, "corr-ghost", Instant.now());
        bus.publish(ghost);
        bus.publish(new DispatchEnvelope(null, null, RETAIL, Channel.EMAIL, Priority.NORMAL, 0, "corr-bad", Instant.now()));
        await().atMost(Duration.ofSeconds(10)).until(() -> bus.deadLetters().stream()
                .filter(d -> d.reason().name().equals("POISON_MESSAGE")).count() >= 2);
        assertThat(hook.deliveries.get(ghost.messageId()).get()).isEqualTo(1); // dead-lettered on the first delivery

        // (b) a real message whose processing keeps failing unexpectedly: bounded redeliveries, then DLQ, DB mirrored
        quiesce();
        String poison = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(poison, "QUEUED");
        hook.failBeforeProcess.put(poison, new java.util.concurrent.atomic.AtomicInteger(1000));
        bus.resume();
        awaitStatus(poison, "DEAD_LETTERED");
        assertThat(row(poison)).containsEntry("dlq_reason", "POISON_MESSAGE");
        assertThat(hook.deliveries.get(poison).get()).isEqualTo(3); // poison-threshold = 3, no further redelivery
        assertThat(email.callCount()).isZero();
        assertThat(bus.deadLetters()).anyMatch(d -> poison.equals(d.envelope().messageId()));
        assertOnlyLegalTransitions(poison);
        assertThat(counter("messaging.deadletter.total", "reason", "POISON_MESSAGE") - poisonBefore).isEqualTo(3.0);

        // and the consumer keeps serving everybody else
        String healthy = submit(newRecipient(RETAIL), "EMAIL");
        awaitStatus(healthy, "SENT_TO_PROVIDER");
        Thread.sleep(300);
        assertThat(hook.deliveries.get(poison).get()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ 10

    @Test
    void scenario10_providerRateLimitAndBackpressure_noAttemptConsumed_apiReturns503() throws Exception {
        // (a) the provider token bucket paces dispatch; requeues consume no attempt
        jdbc.update("update provider_config set rate_limit_per_sec = 2 where provider_id = 'simulated-email'");
        registry.invalidate();
        double rejectedBefore = counter("messaging.ratelimit.rejected", "scope", "provider");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(submit(newRecipient(RETAIL), "EMAIL"));
        }
        for (String id : ids) {
            awaitStatus(id, "SENT_TO_PROVIDER");
        }
        assertThat(counter("messaging.ratelimit.rejected", "scope", "provider") - rejectedBefore).isGreaterThan(0);
        for (String id : ids) {
            assertThat(attemptCount(id)).as("attempt_count of %s", id).isEqualTo(1);
            assertThat(attemptRows(id)).isEqualTo(1);
        }
        assertThat(email.callCount()).isEqualTo(6); // exactly one provider call per message, no loss, no retry
        restoreWorld();

        // (b) backpressure: with consumers paused and a tiny limit the API answers 503 + Retry-After
        quiesce();
        limits.setMaxQueueDepth(3);
        String auth = sender(RETAIL);
        List<String> accepted = new ArrayList<>();
        String rejectedKey = null;
        Map<String, Object> rejectedBody = null;
        for (int i = 0; i < 10 && rejectedKey == null; i++) {
            String key = newKey();
            Map<String, Object> b = request(newRecipient(RETAIL), "EMAIL");
            MvcResult r = mvc.perform(submit(auth, key, b)).andReturn();
            if (r.getResponse().getStatus() == 202) {
                accepted.add(json(r).get("id").asText());
            } else {
                assertThat(r.getResponse().getStatus()).isEqualTo(503);
                assertThat(Long.parseLong(r.getResponse().getHeader("Retry-After"))).isGreaterThanOrEqualTo(1);
                assertThat(json(r).get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
                rejectedKey = key;
                rejectedBody = b;
            }
        }
        assertThat(rejectedKey).as("the API must refuse once the backlog reaches the limit").isNotNull();
        assertThat(accepted).hasSizeLessThanOrEqualTo(3).isNotEmpty();
        assertThat(jdbc.queryForObject("select count(*) from message_request where idempotency_key = ?", Integer.class,
                rejectedKey)).isZero(); // refused before anything was persisted

        // recovery: consumers resume, accepted messages are not lost, and the refused client retries with its key
        limits.setMaxQueueDepth(5000);
        bus.resume();
        for (String id : accepted) {
            awaitStatus(id, "SENT_TO_PROVIDER");
        }
        MvcResult retried = mvc.perform(submit(auth, rejectedKey, rejectedBody)).andReturn();
        assertThat(retried.getResponse().getStatus()).isEqualTo(202);
        awaitStatus(json(retried).get("id").asText(), "SENT_TO_PROVIDER");
    }
}
