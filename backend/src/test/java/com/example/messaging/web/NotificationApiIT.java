package com.example.messaging.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.messaging.AbstractIntegrationTest;
import com.example.messaging.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class NotificationApiIT extends AbstractIntegrationTest {

    @Autowired
    OutboxEventRepository outbox;

    // ------------------------------------------------------------ happy path

    @Test
    void submitReturns202WithLocationAndPersistsValidatedMessageWithOutboxAndAudit() throws Exception {
        String auth = sender(RETAIL);
        long outboxBefore = outbox.count();
        MvcResult r = mvc.perform(submit(auth, newKey(), tradeConfirmation("rcp-001")))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", containsString("/api/v1/messages/")))
                .andExpect(header().string("API-Version", "1"))
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.status", is("VALIDATED")))
                .andExpect(jsonPath("$.channel", is("EMAIL")))
                .andExpect(jsonPath("$.links.self", notNullValue()))
                .andReturn();
        String id = json(r).get("id").asText();
        assertThat(json(r).get("correlationId").asText()).isEqualTo(r.getResponse().getHeader("X-Correlation-Id"));
        assertThat(outbox.count()).as("outbox row written in the same transaction").isEqualTo(outboxBefore + 1);

        mvc.perform(get("/api/v1/messages/" + id).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("VALIDATED")))
                .andExpect(jsonPath("$.terminal", is(false)))
                .andExpect(jsonPath("$.template.code", is("TRADE_CONFIRMATION")))
                .andExpect(jsonPath("$.template.versionNo", is(1)))
                .andExpect(jsonPath("$.template.locale", is("en")))
                .andExpect(jsonPath("$.recipient.id", is("rcp-001")))
                .andExpect(jsonPath("$.attempts.length()", is(0)));

        // the lightweight status endpoint
        mvc.perform(get("/api/v1/messages/" + id + "/status").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("VALIDATED")))
                .andExpect(jsonPath("$.attemptCount", is(0)))
                .andExpect(jsonPath("$.terminal", is(false)));

        // history: accepted audit entry exists
        mvc.perform(get("/api/v1/messages/" + id + "/history").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].type", hasItem("MESSAGE_ACCEPTED")));
    }

    @Test
    void messageDetailNeverExposesContentOrFullAddresses() throws Exception {
        String auth = sender(RETAIL);
        String id = submitOk(auth, tradeConfirmation("rcp-001"));
        String body = mvc.perform(get("/api/v1/messages/" + id).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipient.maskedAddress", is("r***@example.test")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("recipient001@example.test").doesNotContain("250.00").doesNotContain("ACME")
                .doesNotContain("1234").doesNotContain("Trade confirmation for account");
    }

    @Test
    void smsAndPushAddressesAreMaskedToo() throws Exception {
        String auth = sender(RETAIL);
        Map<String, Object> sms = tradeConfirmation("rcp-001");
        sms.put("channel", "SMS");
        String smsId = submitOk(auth, sms);
        mvc.perform(get("/api/v1/messages/" + smsId).header("Authorization", auth))
                .andExpect(jsonPath("$.recipient.maskedAddress", is("+1555***0001")));

        Map<String, Object> push = new LinkedHashMap<>();
        push.put("recipientId", "rcp-001");
        push.put("channel", "PUSH");
        push.put("category", "TRANSACTIONAL");
        push.put("template", Map.of("code", "WELCOME_PUSH"));
        push.put("variables", Map.of("firstName", "Ada"));
        String pushId = submitOk(auth, push);
        String body = mvc.perform(get("/api/v1/messages/" + pushId).header("Authorization", auth))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("push-token-synthetic-001");
    }

    @Test
    void recipientCanBeAddressedByExternalRefAndTemplateLocaleFallsBackToRecipientLocale() throws Exception {
        String auth = sender(RETAIL);
        Map<String, Object> body = tradeConfirmation(null);
        body.remove("recipientId");
        body.put("recipient", Map.of("externalRef", "CUST-004")); // rcp-004 has locale "es"
        body.put("template", Map.of("code", "TRADE_CONFIRMATION"));
        String id = submitOk(auth, body);
        mvc.perform(get("/api/v1/messages/" + id).header("Authorization", auth))
                .andExpect(jsonPath("$.recipient.id", is("rcp-004")))
                .andExpect(jsonPath("$.template.locale", is("es")));
    }

    // ------------------------------------------------------------ idempotency

    @Test
    void duplicateRequestReplaysTheOriginalResponseWithoutCreatingASecondMessage() throws Exception {
        String auth = sender(RETAIL);
        String key = newKey();
        Map<String, Object> body = tradeConfirmation("rcp-001");
        MvcResult first = mvc.perform(submit(auth, key, body)).andExpect(status().isAccepted()).andReturn();
        long outboxAfterFirst = outbox.count();
        MvcResult second = mvc.perform(submit(auth, key, body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn();
        assertThat(json(second).get("id").asText()).isEqualTo(json(first).get("id").asText());
        assertThat(json(second).get("correlationId").asText()).isEqualTo(json(first).get("correlationId").asText());
        assertThat(outbox.count()).isEqualTo(outboxAfterFirst);
        Integer messagesWithKey = jdbc.queryForObject("select count(*) from message_request where idempotency_key = ?",
                Integer.class, key);
        assertThat(messagesWithKey).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejectedWith422() throws Exception {
        String auth = sender(RETAIL);
        String key = newKey();
        mvc.perform(submit(auth, key, tradeConfirmation("rcp-001"))).andExpect(status().isAccepted());
        mvc.perform(submit(auth, key, tradeConfirmation("rcp-001")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REUSED")));
    }

    @Test
    void sameKeyDifferentTenantsAreIndependent() throws Exception {
        String key = newKey();
        Map<String, Object> retail = tradeConfirmation("rcp-001");
        Map<String, Object> wealth = tradeConfirmation("rcp-041");
        mvc.perform(submit(sender(RETAIL), key, retail)).andExpect(status().isAccepted());
        mvc.perform(submit(sender(WEALTH), key, wealth)).andExpect(status().isAccepted());
    }

    @Test
    void missingIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(post("/api/v1/messages").header("Authorization", sender(RETAIL))
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(tradeConfirmation("rcp-001"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.details[0].field", is("Idempotency-Key")));
    }

    @Test
    void invalidIdempotencyKeyCharactersAreRejected() throws Exception {
        mvc.perform(submit(sender(RETAIL), "bad key with spaces", tradeConfirmation("rcp-001")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
    }

    @Test
    void aFailedSubmissionDoesNotBurnTheIdempotencyKey() throws Exception {
        String auth = sender(RETAIL);
        String key = newKey();
        Map<String, Object> bad = tradeConfirmation("rcp-001");
        bad.put("template", Map.of("code", "NO_SUCH_TEMPLATE"));
        mvc.perform(submit(auth, key, bad)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("TEMPLATE_NOT_FOUND")));
        // the same key can now be used for a corrected request (different body, no 422)
        mvc.perform(submit(auth, key, tradeConfirmation("rcp-001"))).andExpect(status().isAccepted());
    }

    // ------------------------------------------------------------ validation / malformed input

    @Test
    void malformedJsonIsAnApiErrorAndDoesNotEchoTheInput() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/messages").header("Authorization", sender(RETAIL))
                        .header("Idempotency-Key", newKey()).header("X-Correlation-Id", "corr-abc-123")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\": \"EMAIL\", SECRET-TOKEN-xyz"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("MALFORMED_REQUEST")))
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.path", is("/api/v1/messages")))
                .andExpect(jsonPath("$.correlationId", is("corr-abc-123")))
                .andExpect(header().string("X-Correlation-Id", "corr-abc-123"))
                .andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContain("SECRET-TOKEN-xyz");
    }

    @Test
    void emptyBodyIsMalformed() throws Exception {
        mvc.perform(post("/api/v1/messages").header("Authorization", sender(RETAIL)).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("MALFORMED_REQUEST")));
    }

    @Test
    void unsupportedChannelValueYieldsUnsupportedChannel() throws Exception {
        Map<String, Object> body = tradeConfirmation("rcp-001");
        body.put("channel", "FAX");
        mvc.perform(submit(sender(RETAIL), newKey(), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("UNSUPPORTED_CHANNEL")))
                .andExpect(jsonPath("$.details[0].field", is("channel")));
    }

    @Test
    void unsupportedFallbackChannelValueYieldsUnsupportedChannelWithIndexedField() throws Exception {
        Map<String, Object> body = tradeConfirmation("rcp-001");
        body.put("fallbackChannels", List.of("CARRIER_PIGEON"));
        mvc.perform(submit(sender(RETAIL), newKey(), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("UNSUPPORTED_CHANNEL")))
                .andExpect(jsonPath("$.details[0].field", is("fallbackChannels[0]")));
    }

    @Test
    void invalidCategoryValueIsAValidationErrorListingAllowedValues() throws Exception {
        Map<String, Object> body = tradeConfirmation("rcp-001");
        body.put("category", "SPAM");
        mvc.perform(submit(sender(RETAIL), newKey(), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.details[0].field", is("category")))
                .andExpect(jsonPath("$.details[0].message", containsString("MARKETING")));
    }

    @Test
    void beanValidationErrorsListEveryOffendingField() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipientId", "rcp-001");
        body.put("clientReference", "x".repeat(201));
        MvcResult r = mvc.perform(submit(sender(RETAIL), newKey(), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
                .andReturn();
        List<String> fields = new ArrayList<>();
        json(r).get("details").forEach(d -> fields.add(d.get("field").asText()));
        assertThat(fields).contains("channel", "category", "template", "clientReference");
    }

    @Test
    void recipientSelectorIsMutuallyExclusive() throws Exception {
        Map<String, Object> both = tradeConfirmation("rcp-001");
        both.put("recipient", Map.of("externalRef", "CUST-001"));
        mvc.perform(submit(sender(RETAIL), newKey(), both)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
        Map<String, Object> none = tradeConfirmation("rcp-001");
        none.remove("recipientId");
        mvc.perform(submit(sender(RETAIL), newKey(), none)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
    }

    @Test
    void unknownRecipientAndTemplateAndMissingVariablesUseDedicatedCodes() throws Exception {
        String auth = sender(RETAIL);
        Map<String, Object> unknownRecipient = tradeConfirmation("rcp-does-not-exist");
        mvc.perform(submit(auth, newKey(), unknownRecipient)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("RECIPIENT_NOT_FOUND")));

        Map<String, Object> missingVar = tradeConfirmation("rcp-001");
        missingVar.put("variables", Map.of("accountLast4", "1234"));
        mvc.perform(submit(auth, newKey(), missingVar)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("TEMPLATE_VARIABLE_MISSING")))
                .andExpect(jsonPath("$.details[*].field", hasItem("variables.quantity")));

        Map<String, Object> unknownVar = tradeConfirmation("rcp-001");
        Map<String, Object> vars = new LinkedHashMap<>((Map<String, Object>) unknownVar.get("variables"));
        vars.put("injected", "{{7*7}}");
        unknownVar.put("variables", vars);
        mvc.perform(submit(auth, newKey(), unknownVar)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("TEMPLATE_RENDER_ERROR")));
    }

    @Test
    void categoryMustMatchTheTemplateSoMarketingConsentCannotBeBypassed() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipientId", "rcp-002"); // marketing opt-out
        body.put("channel", "EMAIL");
        body.put("category", "TRANSACTIONAL"); // template MARKETING_PROMO is MARKETING
        body.put("template", Map.of("code", "MARKETING_PROMO"));
        body.put("variables", Map.of("offerTitle", "Big sale", "deadline", "Friday"));
        mvc.perform(submit(sender(RETAIL), newKey(), body)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.details[0].field", is("category")));
    }

    @Test
    void headerInjectionInCustomEmailHeadersIsRejected() throws Exception {
        Map<String, Object> body = tradeConfirmation("rcp-001");
        body.put("channelOptions", Map.of("email", Map.of("headers", Map.of("Bcc", "attacker@example.test"))));
        mvc.perform(submit(sender(RETAIL), newKey(), body)).andExpect(status().isBadRequest());
        body.put("channelOptions", Map.of("email", Map.of("headers", Map.of("X-Campaign", "a\r\nBcc: x@example.test"))));
        mvc.perform(submit(sender(RETAIL), newKey(), body)).andExpect(status().isBadRequest());
        body.put("channelOptions", Map.of("email", Map.of("headers", Map.of("X-Campaign", "spring"))));
        mvc.perform(submit(sender(RETAIL), newKey(), body)).andExpect(status().isAccepted());
    }

    @Test
    void fallbackRulesAreValidated() throws Exception {
        String auth = sender(RETAIL);
        Map<String, Object> same = tradeConfirmation("rcp-001");
        same.put("fallbackChannels", List.of("EMAIL"));
        mvc.perform(submit(auth, newKey(), same)).andExpect(status().isBadRequest());
        Map<String, Object> dup = tradeConfirmation("rcp-001");
        dup.put("fallbackChannels", List.of("SMS", "SMS"));
        mvc.perform(submit(auth, newKey(), dup)).andExpect(status().isBadRequest());
        Map<String, Object> noTemplate = tradeConfirmation("rcp-001");
        noTemplate.put("fallbackChannels", List.of("PUSH")); // no TRADE_CONFIRMATION push template
        mvc.perform(submit(auth, newKey(), noTemplate)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("TEMPLATE_NOT_FOUND")));
        Map<String, Object> ok = tradeConfirmation("rcp-001");
        ok.put("fallbackChannels", List.of("SMS"));
        String id = submitOk(auth, ok);
        assertThat(jdbc.queryForObject("select fallback_channels from message_request where id = ?", String.class, id))
                .isEqualTo("SMS");
    }

    @Test
    void scheduleIsValidated() throws Exception {
        String auth = sender(RETAIL);
        Map<String, Object> past = tradeConfirmation("rcp-001");
        past.put("schedule", Map.of("sendAt", Instant.now().minus(1, ChronoUnit.DAYS).toString()));
        MvcResult r = mvc.perform(submit(auth, newKey(), past)).andExpect(status().isAccepted()).andReturn();
        assertThat(json(r).get("scheduledAt").isNull()).as("a past sendAt means send now").isTrue();

        Map<String, Object> tooFar = tradeConfirmation("rcp-001");
        tooFar.put("schedule", Map.of("sendAt", Instant.now().plus(400, ChronoUnit.DAYS).toString()));
        mvc.perform(submit(auth, newKey(), tooFar)).andExpect(status().isBadRequest());

        Map<String, Object> badExpiry = tradeConfirmation("rcp-001");
        Instant sendAt = Instant.now().plus(2, ChronoUnit.DAYS);
        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("sendAt", sendAt.toString());
        schedule.put("expiresAt", sendAt.minus(1, ChronoUnit.HOURS).toString());
        badExpiry.put("schedule", schedule);
        mvc.perform(submit(auth, newKey(), badExpiry)).andExpect(status().isBadRequest());
    }

    @Test
    void futureScheduledMessageStaysValidatedAndTheOutboxEventIsNotDueYet() throws Exception {
        String auth = sender(RETAIL);
        Instant sendAt = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> body = tradeConfirmation("rcp-001");
        body.put("schedule", Map.of("sendAt", sendAt.toString()));
        MvcResult r = mvc.perform(submit(auth, newKey(), body)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status", is("VALIDATED"))).andReturn();
        assertThat(Instant.parse(json(r).get("scheduledAt").asText())).isEqualTo(sendAt);
        Integer notDue = jdbc.queryForObject(
                "select count(*) from outbox_event where aggregate_id = ? and status = 'NEW' and available_at > current_timestamp",
                Integer.class, json(r).get("id").asText());
        assertThat(notDue).isEqualTo(1);
    }

    // ------------------------------------------------------------ policy at submission

    @Test
    void policyRejectionsAreRecordedAsRejectedMessagesWithAReason() throws Exception {
        String auth = sender(RETAIL);
        Map<String, Object> marketing = marketing("rcp-002"); // marketing opt-out
        mvc.perform(submit(auth, newKey(), marketing)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status", is("REJECTED"))).andExpect(jsonPath("$.statusReason", is("OPTED_OUT")));

        // rcp-013's email address is on the suppression list: even transactional mail is blocked
        mvc.perform(submit(auth, newKey(), tradeConfirmation("rcp-013"))).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status", is("REJECTED"))).andExpect(jsonPath("$.statusReason", is("SUPPRESSED")));

        // rcp-040 is flagged deleted
        mvc.perform(submit(auth, newKey(), tradeConfirmation("rcp-040"))).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status", is("REJECTED"))).andExpect(jsonPath("$.statusReason", is("RECIPIENT_DELETED")));
    }

    @Test
    void rejectedMessagesHaveNoOutboxEventAndAreTerminal() throws Exception {
        String auth = sender(RETAIL);
        MvcResult r = mvc.perform(submit(auth, newKey(), marketing("rcp-002"))).andReturn();
        String id = json(r).get("id").asText();
        Integer outboxRows = jdbc.queryForObject("select count(*) from outbox_event where aggregate_id = ?", Integer.class, id);
        assertThat(outboxRows).isZero();
        mvc.perform(get("/api/v1/messages/" + id + "/status").header("Authorization", auth))
                .andExpect(jsonPath("$.terminal", is(true))).andExpect(jsonPath("$.status", is("REJECTED")));
        mvc.perform(post("/api/v1/messages/" + id + "/cancel").header("Authorization", auth))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("ILLEGAL_STATE_TRANSITION")));
    }

    @Test
    void marketingInsideTheRecipientsQuietHoursIsDeferredToTheirLocalMorning() throws Exception {
        // rcp-001 is in America/New_York with default quiet hours 21:00-08:00; pick a send time 30 days out at
        // 03:00Z, which is 22:00 or 23:00 local regardless of DST
        String auth = sender(RETAIL);
        Instant sendAt = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).plus(3, ChronoUnit.HOURS);
        Map<String, Object> body = marketing("rcp-001");
        body.put("schedule", Map.of("sendAt", sendAt.toString()));
        MvcResult r = mvc.perform(submit(auth, newKey(), body)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status", is("VALIDATED")))
                .andExpect(jsonPath("$.statusReason", is("DEFERRED_QUIET_HOURS"))).andReturn();
        Instant scheduled = Instant.parse(json(r).get("scheduledAt").asText());
        assertThat(scheduled).isAfter(sendAt);
        assertThat(scheduled.atZone(java.time.ZoneId.of("America/New_York")).toLocalTime())
                .isEqualTo(java.time.LocalTime.of(8, 0));
    }

    @Test
    void marketingOutsideQuietHoursIsNotDeferred() throws Exception {
        String auth = sender(RETAIL);
        Instant sendAt = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).plus(16, ChronoUnit.HOURS);
        Map<String, Object> body = marketing("rcp-001");
        body.put("schedule", Map.of("sendAt", sendAt.toString()));
        MvcResult r = mvc.perform(submit(auth, newKey(), body)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status", is("VALIDATED"))).andReturn();
        assertThat(Instant.parse(json(r).get("scheduledAt").asText())).isEqualTo(sendAt);
        assertThat(json(r).get("statusReason").isNull()).isTrue();
    }

    private Map<String, Object> marketing(String recipientId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientReference", "promo-" + UUID.randomUUID());
        body.put("recipientId", recipientId);
        body.put("channel", "EMAIL");
        body.put("category", "MARKETING");
        body.put("template", Map.of("code", "MARKETING_PROMO"));
        body.put("variables", Map.of("offerTitle", "Spring offer", "deadline", "Friday"));
        return body;
    }

    // ------------------------------------------------------------ authn / authz

    @Test
    void unauthenticatedRequestsGet401WithApiError() throws Exception {
        mvc.perform(post("/api/v1/messages").header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(tradeConfirmation("rcp-001"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")))
                .andExpect(header().exists("X-Correlation-Id"));
        mvc.perform(get("/api/v1/messages")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/messages").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code", is("UNAUTHENTICATED")));
        mvc.perform(get("/api/v1/messages").header("Authorization", "Basic YWxpY2U6cGFzcw=="))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void roleWithoutPermissionGets403() throws Exception {
        mvc.perform(submit(bearer("auditor-1", RETAIL, "AUDITOR"), newKey(), tradeConfirmation("rcp-001")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("FORBIDDEN")));
        String id = submitOk(sender(RETAIL), tradeConfirmation("rcp-001"));
        mvc.perform(post("/api/v1/messages/" + id + "/requeue").header("Authorization", sender(RETAIL)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/messages/" + id + "/cancel").header("Authorization", bearer("aud", RETAIL, "AUDITOR")))
                .andExpect(status().isForbidden());
        // a token with no roles can read but not write
        String noRole = bearer("nobody", RETAIL);
        mvc.perform(get("/api/v1/messages/" + id).header("Authorization", noRole)).andExpect(status().isOk());
        mvc.perform(submit(noRole, newKey(), tradeConfirmation("rcp-001"))).andExpect(status().isForbidden());
    }

    @Test
    void platformAdminMustScopeTheTenantForWrites() throws Exception {
        String admin = platformAdmin();
        mvc.perform(submit(admin, newKey(), tradeConfirmation("rcp-001"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field", is("X-Tenant-Id")));
        mvc.perform(submit(admin, newKey(), tradeConfirmation("rcp-001")).header("X-Tenant-Id", RETAIL))
                .andExpect(status().isAccepted());
        mvc.perform(submit(admin, newKey(), tradeConfirmation("rcp-001")).header("X-Tenant-Id", "tenant-unknown"))
                .andExpect(status().isBadRequest());
        mvc.perform(submit(admin, newKey(), tradeConfirmation("rcp-001")).header("X-Tenant-Id", "bad tenant!"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------ tenant isolation

    @Test
    void anotherTenantsMessageIsInvisibleAs404EverywhereAndNeverListed() throws Exception {
        String retail = sender(RETAIL);
        String wealth = bearer("wealth-admin", WEALTH, "TENANT_ADMIN", "SENDER", "OPERATOR");
        String id = submitOk(retail, tradeConfirmation("rcp-001"));
        mvc.perform(get("/api/v1/messages/" + id).header("Authorization", wealth))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code", is("NOT_FOUND")));
        mvc.perform(get("/api/v1/messages/" + id + "/status").header("Authorization", wealth)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/messages/" + id + "/history").header("Authorization", wealth)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/messages/" + id + "/cancel").header("Authorization", wealth)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/messages/" + id + "/requeue").header("Authorization", wealth)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/messages").param("q", "order-").header("Authorization", wealth))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(id))));
        // the retail message is untouched
        assertThat(statusOf(id)).isEqualTo("VALIDATED");
        // and a wealth sender cannot message a retail recipient
        mvc.perform(submit(wealth, newKey(), tradeConfirmation("rcp-001"))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("RECIPIENT_NOT_FOUND")));
    }

    @Test
    void messagesFromOtherTenantsAreNotCountedInPagination() throws Exception {
        String prefix = "iso-" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < 3; i++) {
            Map<String, Object> b = tradeConfirmation("rcp-001");
            b.put("clientReference", prefix + "-" + i);
            submitOk(sender(RETAIL), b);
        }
        Map<String, Object> w = tradeConfirmation("rcp-041");
        w.put("clientReference", prefix + "-w");
        submitOk(sender(WEALTH), w);
        mvc.perform(get("/api/v1/messages").param("q", prefix).header("Authorization", sender(RETAIL)))
                .andExpect(jsonPath("$.totalElements", is(3)));
        mvc.perform(get("/api/v1/messages").param("q", prefix).header("Authorization", sender(WEALTH)))
                .andExpect(jsonPath("$.totalElements", is(1)));
        mvc.perform(get("/api/v1/messages").param("q", prefix).header("Authorization", platformAdmin()))
                .andExpect(jsonPath("$.totalElements", is(4)));
        mvc.perform(get("/api/v1/messages").param("q", prefix).header("Authorization", platformAdmin())
                        .header("X-Tenant-Id", WEALTH))
                .andExpect(jsonPath("$.totalElements", is(1)));
        // a non-admin cannot widen scope with the header
        mvc.perform(get("/api/v1/messages").param("q", prefix).header("Authorization", sender(RETAIL))
                        .header("X-Tenant-Id", WEALTH))
                .andExpect(jsonPath("$.totalElements", is(3)));
    }

    // ------------------------------------------------------------ size and rate limits

    @Test
    void oversizedBodyIsRejectedWith413BeforeProcessing() throws Exception {
        Map<String, Object> body = tradeConfirmation("rcp-001");
        body.put("clientReference", "x".repeat(300_000));
        mvc.perform(submit(sender(RETAIL), newKey(), body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code", is("PAYLOAD_TOO_LARGE")));
    }

    @Test
    void bodyJustUnderTheLimitIsProcessedNormally() throws Exception {
        Map<String, Object> body = tradeConfirmation("rcp-001");
        body.put("clientReference", "x".repeat(150));
        mvc.perform(submit(sender(RETAIL), newKey(), body)).andExpect(status().isAccepted());
    }

    // ------------------------------------------------------------ cancel / requeue

    @Test
    void cancelWorksFromValidatedAndIsRecordedInHistory() throws Exception {
        String auth = sender(RETAIL);
        String id = submitOk(auth, tradeConfirmation("rcp-001"));
        mvc.perform(post("/api/v1/messages/" + id + "/cancel").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED")))
                .andExpect(jsonPath("$.terminal", is(true)));
        mvc.perform(get("/api/v1/messages/" + id + "/history").header("Authorization", auth))
                .andExpect(jsonPath("$[*].type", hasItem("MESSAGE_ACCEPTED")))
                .andExpect(jsonPath("$[*].type", hasItem("MESSAGE_CANCELLED")));
        // the pending outbox event must not resurrect it: cancelling again is illegal
        mvc.perform(post("/api/v1/messages/" + id + "/cancel").header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("ILLEGAL_STATE_TRANSITION")));
    }

    @Test
    void cancelAllowedFromQueuedAndRetryPendingButIllegalElsewhere() throws Exception {
        String auth = sender(RETAIL);
        for (String allowed : List.of("QUEUED", "RETRY_PENDING")) {
            String id = submitOk(auth, tradeConfirmation("rcp-001"));
            forceStatus(id, allowed);
            mvc.perform(post("/api/v1/messages/" + id + "/cancel").header("Authorization", auth))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("CANCELLED")));
        }
        for (String illegal : List.of("PROCESSING", "SENT_TO_PROVIDER", "DELIVERED", "FAILED", "DEAD_LETTERED",
                "DELIVERY_UNKNOWN", "CANCELLED", "REJECTED")) {
            String id = submitOk(auth, tradeConfirmation("rcp-001"));
            forceStatus(id, illegal);
            mvc.perform(post("/api/v1/messages/" + id + "/cancel").header("Authorization", auth))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code", is("ILLEGAL_STATE_TRANSITION")));
            assertThat(statusOf(id)).as("status unchanged after rejected cancel from " + illegal).isEqualTo(illegal);
        }
    }

    @Test
    void operatorRequeueMovesFailedAndDeadLetteredBackToQueuedViaTheOutbox() throws Exception {
        String sender = sender(RETAIL);
        String op = operator(RETAIL);
        for (String from : List.of("FAILED", "DEAD_LETTERED")) {
            String id = submitOk(sender, tradeConfirmation("rcp-001"));
            forceStatus(id, from);
            long before = outbox.count();
            mvc.perform(post("/api/v1/messages/" + id + "/requeue").header("Authorization", op))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("QUEUED")));
            assertThat(outbox.count()).isEqualTo(before + 1);
            Integer attemptNoInPayload = jdbc.queryForObject(
                    "select count(*) from outbox_event where aggregate_id = ? and payload like '%\"attemptNo\":1%'",
                    Integer.class, id);
            assertThat(attemptNoInPayload).as("requeue publishes the next attempt number").isGreaterThanOrEqualTo(1);
        }
        String delivered = submitOk(sender, tradeConfirmation("rcp-001"));
        forceStatus(delivered, "DELIVERED");
        mvc.perform(post("/api/v1/messages/" + delivered + "/requeue").header("Authorization", op))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("ILLEGAL_STATE_TRANSITION")));
        String validated = submitOk(sender, tradeConfirmation("rcp-001"));
        mvc.perform(post("/api/v1/messages/" + validated + "/requeue").header("Authorization", op))
                .andExpect(status().isConflict());
    }

    @Test
    void deadLetterListReplayAndDiscard() throws Exception {
        String sender = sender(RETAIL);
        String op = operator(RETAIL);
        String id = submitOk(sender, tradeConfirmation("rcp-001"));
        forceStatus(id, "DEAD_LETTERED");
        jdbc.update("update message_request set dlq_reason = 'RETRIES_EXHAUSTED' where id = ?", id);
        mvc.perform(get("/api/v1/dead-letters").header("Authorization", op).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.messageId=='" + id + "')].reason", hasItem("RETRIES_EXHAUSTED")));
        mvc.perform(get("/api/v1/dead-letters").header("Authorization", sender)).andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/dead-letters/" + id + "/discard").header("Authorization", op)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"duplicate of order 17\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("DEAD_LETTERED")));
        assertThat(jdbc.queryForObject("select discarded from message_request where id = ?", Boolean.class, id)).isTrue();
        mvc.perform(get("/api/v1/audit-events").header("Authorization", admin(RETAIL))
                        .param("entityId", id).param("action", "MESSAGE_DISCARDED"))
                .andExpect(jsonPath("$.totalElements", is(1)));

        mvc.perform(post("/api/v1/dead-letters/" + id + "/replay").header("Authorization", op))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("QUEUED")));
        assertThat(jdbc.queryForObject("select discarded from message_request where id = ?", Boolean.class, id))
                .as("replay clears the discarded flag").isFalse();
        // a message that is not dead-lettered can be neither replayed nor discarded
        mvc.perform(post("/api/v1/dead-letters/" + id + "/replay").header("Authorization", op)).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/dead-letters/" + id + "/discard").header("Authorization", op)).andExpect(status().isConflict());
    }

    @Test
    void failedMessagesListCoversFailedUnknownAndRetryPending() throws Exception {
        String sender = sender(RETAIL);
        String op = operator(RETAIL);
        Map<String, String> ids = new LinkedHashMap<>();
        for (String st : List.of("FAILED", "DELIVERY_UNKNOWN", "RETRY_PENDING", "DELIVERED")) {
            String id = submitOk(sender, tradeConfirmation("rcp-001"));
            forceStatus(id, st);
            ids.put(st, id);
        }
        MvcResult r = mvc.perform(get("/api/v1/failed-messages").header("Authorization", op).param("size", "200"))
                .andExpect(status().isOk()).andReturn();
        List<String> listed = new ArrayList<>();
        json(r).get("content").forEach(n -> listed.add(n.get("id").asText()));
        assertThat(listed).contains(ids.get("FAILED"), ids.get("DELIVERY_UNKNOWN"), ids.get("RETRY_PENDING"))
                .doesNotContain(ids.get("DELIVERED"));
        mvc.perform(get("/api/v1/failed-messages").header("Authorization", sender)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------ pagination / filters

    @Test
    void paginationFiltersAndSorting() throws Exception {
        String auth = sender(RETAIL);
        String prefix = "page-" + UUID.randomUUID().toString().substring(0, 8);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Map<String, Object> b = tradeConfirmation("rcp-001");
            b.put("clientReference", prefix + "-" + i);
            if (i == 4) {
                b.put("channel", "SMS");
                b.put("priority", "HIGH");
            }
            ids.add(submitOk(auth, b));
        }
        forceStatus(ids.get(0), "CANCELLED");

        MvcResult page1 = mvc.perform(get("/api/v1/messages").header("Authorization", auth)
                        .param("q", prefix).param("size", "2").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(5)))
                .andExpect(jsonPath("$.totalPages", is(3)))
                .andExpect(jsonPath("$.size", is(2)))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.content.length()", is(2)))
                .andReturn();
        MvcResult page3 = mvc.perform(get("/api/v1/messages").header("Authorization", auth)
                        .param("q", prefix).param("size", "2").param("page", "2"))
                .andExpect(jsonPath("$.content.length()", is(1))).andReturn();
        // pages do not overlap and default order is newest first
        List<String> seen = new ArrayList<>();
        for (int p = 0; p < 3; p++) {
            MvcResult r = mvc.perform(get("/api/v1/messages").header("Authorization", auth)
                    .param("q", prefix).param("size", "2").param("page", String.valueOf(p))).andReturn();
            json(r).get("content").forEach(n -> seen.add(n.get("id").asText()));
        }
        assertThat(seen).hasSize(5).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(ids);
        assertThat(json(page1).get("content").get(0).get("clientReference").asText()).isEqualTo(prefix + "-4");
        assertThat(page3.getResponse().getStatus()).isEqualTo(200);

        mvc.perform(get("/api/v1/messages").header("Authorization", auth).param("q", prefix).param("channel", "SMS"))
                .andExpect(jsonPath("$.totalElements", is(1)));
        mvc.perform(get("/api/v1/messages").header("Authorization", auth).param("q", prefix).param("status", "CANCELLED"))
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].id", is(ids.get(0))));
        mvc.perform(get("/api/v1/messages").header("Authorization", auth).param("q", prefix).param("priority", "HIGH"))
                .andExpect(jsonPath("$.totalElements", is(1)));
        mvc.perform(get("/api/v1/messages").header("Authorization", auth).param("q", prefix).param("category", "MARKETING"))
                .andExpect(jsonPath("$.totalElements", is(0)));
        mvc.perform(get("/api/v1/messages").header("Authorization", auth).param("q", prefix).param("recipientId", "rcp-002"))
                .andExpect(jsonPath("$.totalElements", is(0)));
        mvc.perform(get("/api/v1/messages").header("Authorization", auth).param("q", prefix).param("sort", "createdAt,asc"))
                .andExpect(jsonPath("$.content[0].clientReference", is(prefix + "-0")))
                .andExpect(jsonPath("$.sort", containsString("createdAt")));
        String from = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        mvc.perform(get("/api/v1/messages").header("Authorization", auth).param("q", prefix).param("from", from))
                .andExpect(jsonPath("$.totalElements", is(0)));
        String correlation = json(page1).get("content").get(0).get("correlationId").asText();
        mvc.perform(get("/api/v1/messages").header("Authorization", auth).param("correlationId", correlation))
                .andExpect(jsonPath("$.totalElements", is(1)));
        // LIKE wildcards in the free-text filter are literals
        mvc.perform(get("/api/v1/messages").header("Authorization", auth).param("q", "%"))
                .andExpect(jsonPath("$.totalElements", is(0)));
    }

    @Test
    void invalidPaginationAndFilterValuesAre400() throws Exception {
        String auth = sender(RETAIL);
        mvc.perform(get("/api/v1/messages").header("Authorization", auth).param("size", "201"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
        mvc.perform(get("/api/v1/messages").header("Authorization", auth).param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/messages").header("Authorization", auth).param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/messages").header("Authorization", auth).param("sort", "payloadJson,desc"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/messages").header("Authorization", auth).param("sort", "createdAt,sideways"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/messages").header("Authorization", auth).param("status", "EXPLODED"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
        mvc.perform(get("/api/v1/messages").header("Authorization", auth).param("channel", "FAX"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("UNSUPPORTED_CHANNEL")));
        mvc.perform(get("/api/v1/messages").header("Authorization", auth).param("from", "yesterday"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/messages").header("Authorization", auth).param("size", "200")).andExpect(status().isOk());
    }

    @Test
    void unknownRoutesAndMethodsProduceApiErrors() throws Exception {
        String auth = sender(RETAIL);
        mvc.perform(get("/api/v1/nothing-here").header("Authorization", auth))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code", is("NOT_FOUND")));
        mvc.perform(get("/api/v1/messages/does-not-exist").header("Authorization", auth))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code", is("NOT_FOUND")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/messages/x")
                        .header("Authorization", auth))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/v1/messages").header("Authorization", auth).header("Idempotency-Key", newKey())
                        .contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType());
    }

    // ------------------------------------------------------------ misc

    @Test
    void correlationIdIsGeneratedWhenAbsentAndUnsafeInboundValuesAreReplaced() throws Exception {
        MvcResult generated = mvc.perform(get("/api/v1/me").header("Authorization", sender(RETAIL))).andReturn();
        assertThat(generated.getResponse().getHeader("X-Correlation-Id")).isNotBlank();
        MvcResult unsafe = mvc.perform(get("/api/v1/me").header("Authorization", sender(RETAIL))
                .header("X-Correlation-Id", "bad id <script>alert(1)</script> with spaces")).andReturn();
        assertThat(unsafe.getResponse().getHeader("X-Correlation-Id")).isNotBlank().doesNotContain(" ")
                .doesNotContain("<script>");
        // a header with a line break never reaches the application: the Spring Security firewall rejects it
        mvc.perform(get("/api/v1/me").header("Authorization", sender(RETAIL))
                .header("X-Correlation-Id", "evil\r\nSet-Cookie: x=1")).andExpect(status().isBadRequest());
        JsonNode node = json(mvc.perform(get("/api/v1/nope").header("Authorization", sender(RETAIL))).andReturn());
        assertThat(node.get("correlationId").asText()).isNotBlank();
    }

    @Test
    void submissionIsCountedInMetrics() throws Exception {
        submitOk(sender(RETAIL), tradeConfirmation("rcp-001"));
        String prom = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(prom.lines().filter(l -> l.startsWith("messaging_messages_accepted_total"))
                .anyMatch(l -> l.contains("channel=\"EMAIL\"") && l.contains("priority=\"NORMAL\"")
                        && l.contains("tenant=\"tenant-retail\""))).isTrue();
        assertThat(prom.lines().filter(l -> l.startsWith("messaging_transitions_total"))
                .anyMatch(l -> l.contains("from=\"ACCEPTED\"") && l.contains("to=\"VALIDATED\""))).isTrue();
    }

    @Test
    void idempotentReplayIsCountedInMetrics() throws Exception {
        String auth = sender(RETAIL);
        String key = newKey();
        Map<String, Object> body = tradeConfirmation("rcp-001");
        mvc.perform(submit(auth, key, body)).andExpect(status().isAccepted());
        mvc.perform(submit(auth, key, body)).andExpect(status().isOk());
        String prom = mvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString();
        assertThat(prom).contains("messaging_idempotency_hits_total");
    }
}
