package com.example.messaging.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.messaging.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class TemplateLifecycleIT extends AbstractIntegrationTest {

    private static String code() {
        return "T_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private Map<String, Object> createBody(String code, String channel) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("code", code);
        b.put("name", "Test template " + code);
        b.put("channel", channel);
        b.put("category", "TRANSACTIONAL");
        b.put("locale", "en");
        if (!"SMS".equals(channel)) {
            b.put("subject", "Hi {{name}}");
        }
        b.put("body", "SMS".equals(channel) ? "Hello {{name}} {{item|thing}}" : "<p>Hello {{name}} your {{item|thing}}</p>");
        b.put("variables", List.of("name", "item"));
        return b;
    }

    private MockHttpServletRequestBuilder postJson(String url, String auth, Object body) throws Exception {
        return post(url).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body));
    }

    private JsonNode create(String auth, Map<String, Object> body) throws Exception {
        MvcResult r = mvc.perform(postJson("/api/v1/templates", auth, body)).andExpect(status().isCreated()).andReturn();
        return json(r);
    }

    private String author() throws Exception {
        return bearer("author-1", RETAIL, "TEMPLATE_AUTHOR");
    }

    private String approver() throws Exception {
        return bearer("approver-1", RETAIL, "TEMPLATE_APPROVER");
    }

    private void submitVersion(String id, int v, String auth) throws Exception {
        mvc.perform(post("/api/v1/templates/" + id + "/versions/" + v + "/submit").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("PENDING_APPROVAL")));
    }

    private void approveVersion(String id, int v, String auth) throws Exception {
        mvc.perform(post("/api/v1/templates/" + id + "/versions/" + v + "/approve").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("PUBLISHED")));
    }

    private Map<String, Object> message(String code) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recipientId", "rcp-001");
        m.put("channel", "EMAIL");
        m.put("category", "TRANSACTIONAL");
        m.put("template", Map.of("code", code, "locale", "en"));
        m.put("variables", Map.of("name", "Ann"));
        return m;
    }

    @Test
    void fullLifecycleWithFourEyesApprovalVersioningAndPinning() throws Exception {
        String author = author();
        String approver = approver();
        String code = code();
        JsonNode created = create(author, createBody(code, "EMAIL"));
        String id = created.get("id").asText();
        assertThat(created.get("versions").get(0).get("status").asText()).isEqualTo("DRAFT");
        assertThat(created.get("versions").get(0).get("createdBy").asText()).isEqualTo("author-1");
        assertThat(created.get("versions").get(0).get("variables")).hasSize(2);

        // a DRAFT cannot be approved, and cannot be used for sending
        mvc.perform(post("/api/v1/templates/" + id + "/versions/1/approve").header("Authorization", approver))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("ILLEGAL_STATE_TRANSITION")));
        mvc.perform(submit(sender(RETAIL), newKey(), message(code))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("TEMPLATE_NOT_FOUND")));

        submitVersion(id, 1, author);
        // submitting twice is illegal
        mvc.perform(post("/api/v1/templates/" + id + "/versions/1/submit").header("Authorization", author))
                .andExpect(status().isConflict());
        approveVersion(id, 1, approver);

        JsonNode detail = json(mvc.perform(get("/api/v1/templates/" + id).header("Authorization", author))
                .andExpect(status().isOk()).andReturn());
        JsonNode v1 = detail.get("versions").get(0);
        assertThat(v1.get("approvedBy").asText()).isEqualTo("approver-1");
        assertThat(v1.get("publishedAt").isNull()).isFalse();

        // published content is immutable
        mvc.perform(put("/api/v1/templates/" + id + "/versions/1").header("Authorization", author)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("body", "changed", "variables", List.of(), "version", v1.get("version").asLong()))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("ILLEGAL_STATE_TRANSITION")));

        // the published template can now be used and the message pins version 1
        String first = submitOk(sender(RETAIL), message(code));
        mvc.perform(get("/api/v1/messages/" + first).header("Authorization", sender(RETAIL)))
                .andExpect(jsonPath("$.template.versionNo", is(1)));

        // version 2: new DRAFT, approved, retires version 1
        MvcResult v2r = mvc.perform(postJson("/api/v1/templates/" + id + "/versions", author,
                Map.of("locale", "en", "subject", "Hello again {{name}}", "body", "<p>v2 {{name}}</p>",
                        "variables", List.of("name")))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo", is(2))).andExpect(jsonPath("$.status", is("DRAFT"))).andReturn();
        assertThat(json(v2r).get("version").asLong()).isZero();
        submitVersion(id, 2, author);
        approveVersion(id, 2, approver);
        JsonNode after = json(mvc.perform(get("/api/v1/templates/" + id).header("Authorization", author)).andReturn());
        List<String> statuses = new ArrayList<>();
        after.get("versions").forEach(v -> statuses.add(v.get("versionNo").asInt() + ":" + v.get("status").asText()));
        assertThat(statuses).containsExactly("1:RETIRED", "2:PUBLISHED");

        String second = submitOk(sender(RETAIL), message(code));
        mvc.perform(get("/api/v1/messages/" + second).header("Authorization", sender(RETAIL)))
                .andExpect(jsonPath("$.template.versionNo", is(2)));
        mvc.perform(get("/api/v1/messages/" + first).header("Authorization", sender(RETAIL)))
                .andExpect(jsonPath("$.template.versionNo", is(1)));

        // pinning an explicit retired version is refused, pinning the published one works
        Map<String, Object> pinned = message(code);
        pinned.put("template", Map.of("code", code, "locale", "en", "version", 1));
        mvc.perform(submit(sender(RETAIL), newKey(), pinned)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("TEMPLATE_NOT_FOUND")));
        pinned.put("template", Map.of("code", code, "locale", "en", "version", 2));
        mvc.perform(submit(sender(RETAIL), newKey(), pinned)).andExpect(status().isAccepted());

        // retire by a tenant admin: nothing is left to send with
        mvc.perform(post("/api/v1/templates/" + id + "/versions/2/retire").header("Authorization", admin(RETAIL)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("RETIRED")));
        mvc.perform(submit(sender(RETAIL), newKey(), message(code))).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/templates/" + id + "/versions/2/retire").header("Authorization", admin(RETAIL)))
                .andExpect(status().isConflict());

        // history is complete and ordered
        MvcResult h = mvc.perform(get("/api/v1/templates/" + id + "/history").header("Authorization", author))
                .andExpect(status().isOk()).andReturn();
        List<String> actions = new ArrayList<>();
        json(h).forEach(n -> actions.add(n.get("action").asText()));
        assertThat(actions).containsSubsequence("TEMPLATE_CREATED", "TEMPLATE_SUBMITTED", "TEMPLATE_APPROVED",
                "TEMPLATE_VERSION_CREATED", "TEMPLATE_SUBMITTED", "TEMPLATE_APPROVED", "TEMPLATE_RETIRED");
        assertThat(json(h).get(0).get("actor").asText()).isEqualTo("author-1");
    }

    @Test
    void authorCannotApproveTheirOwnVersion() throws Exception {
        String dual = bearer("dual-role-user", RETAIL, "TEMPLATE_AUTHOR", "TEMPLATE_APPROVER");
        JsonNode created = create(dual, createBody(code(), "EMAIL"));
        String id = created.get("id").asText();
        submitVersion(id, 1, dual);
        mvc.perform(post("/api/v1/templates/" + id + "/versions/1/approve").header("Authorization", dual))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("FORBIDDEN")))
                .andExpect(jsonPath("$.message", containsString("Four-eyes")));
        // a different approver can
        approveVersion(id, 1, approver());
    }

    @Test
    void editingMakesTheEditorTheAuthorSoFourEyesCannotBeBypassedByEditing() throws Exception {
        String author = author();
        String dual = bearer("dual-editor", RETAIL, "TEMPLATE_AUTHOR", "TEMPLATE_APPROVER");
        JsonNode created = create(author, createBody(code(), "EMAIL"));
        String id = created.get("id").asText();
        long version = created.get("versions").get(0).get("version").asLong();
        // another person edits the draft and thereby becomes its author of record
        mvc.perform(put("/api/v1/templates/" + id + "/versions/1").header("Authorization", dual)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("subject", "Edited {{name}}", "body", "<p>edited {{name}}</p>",
                                "variables", List.of("name"), "version", version))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.createdBy", is("dual-editor")))
                .andExpect(jsonPath("$.version", is((int) version + 1)));
        submitVersion(id, 1, dual);
        mvc.perform(post("/api/v1/templates/" + id + "/versions/1/approve").header("Authorization", dual))
                .andExpect(status().isForbidden());
    }

    @Test
    void staleEditVersionIsAConflictAndIfMatchMustAgree() throws Exception {
        String author = author();
        JsonNode created = create(author, createBody(code(), "EMAIL"));
        String id = created.get("id").asText();
        long version = created.get("versions").get(0).get("version").asLong();
        Map<String, Object> edit = Map.of("subject", "S {{name}}", "body", "<p>one {{name}}</p>",
                "variables", List.of("name"), "version", version);
        mvc.perform(put("/api/v1/templates/" + id + "/versions/1").header("Authorization", author)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(edit)))
                .andExpect(status().isOk());
        // the same (now stale) token again
        mvc.perform(put("/api/v1/templates/" + id + "/versions/1").header("Authorization", author)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(edit)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("CONFLICT_OPTIMISTIC_LOCK")));
        // If-Match that disagrees with the body is rejected, an agreeing one works
        Map<String, Object> next = Map.of("subject", "S {{name}}", "body", "<p>two {{name}}</p>",
                "variables", List.of("name"), "version", version + 1);
        mvc.perform(put("/api/v1/templates/" + id + "/versions/1").header("Authorization", author)
                        .header("If-Match", "\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(next)))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/templates/" + id + "/versions/1").header("Authorization", author)
                        .header("If-Match", "W/\"" + (version + 1) + "\"")
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(next)))
                .andExpect(status().isOk());
    }

    @Test
    void rejectionReturnsTheVersionToDraftWithTheCommentAndRequiresOne() throws Exception {
        String author = author();
        String approver = approver();
        JsonNode created = create(author, createBody(code(), "EMAIL"));
        String id = created.get("id").asText();
        submitVersion(id, 1, author);
        mvc.perform(postJson("/api/v1/templates/" + id + "/versions/1/reject", approver, Map.of("comment", " ")))
                .andExpect(status().isBadRequest());
        mvc.perform(postJson("/api/v1/templates/" + id + "/versions/1/reject", approver, Map.of("comment", "Legal wording missing")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("DRAFT")))
                .andExpect(jsonPath("$.rejectionComment", is("Legal wording missing")));
        // reject is only valid while pending
        mvc.perform(postJson("/api/v1/templates/" + id + "/versions/1/reject", approver, Map.of("comment", "again")))
                .andExpect(status().isConflict());
        // the author can resubmit, which clears the comment
        mvc.perform(post("/api/v1/templates/" + id + "/versions/1/submit").header("Authorization", author))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rejectionComment").doesNotExist());
    }

    @Test
    void localeVariantsAreIndependentVersionsOfTheSameNumber() throws Exception {
        String author = author();
        String code = code();
        JsonNode created = create(author, createBody(code, "EMAIL"));
        String id = created.get("id").asText();
        submitVersion(id, 1, author);
        approveVersion(id, 1, approver());
        mvc.perform(postJson("/api/v1/templates/" + id + "/versions", author,
                        Map.of("versionNo", 1, "locale", "es", "subject", "Hola {{name}}", "body", "<p>Hola {{name}}</p>",
                                "variables", List.of("name"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.locale", is("es")));
        // same version number + locale again is a duplicate
        mvc.perform(postJson("/api/v1/templates/" + id + "/versions", author,
                        Map.of("versionNo", 1, "locale", "es", "body", "<p>x</p>", "subject", "s", "variables", List.of())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("DUPLICATE_RESOURCE")));
        // versionNo may not skip ahead
        mvc.perform(postJson("/api/v1/templates/" + id + "/versions", author,
                        Map.of("versionNo", 7, "locale", "fr", "body", "<p>x</p>", "subject", "s", "variables", List.of())))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/templates/" + id + "/versions/1/submit").param("locale", "es").header("Authorization", author))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/templates/" + id + "/versions/1/approve").param("locale", "es")
                        .header("Authorization", approver())).andExpect(status().isOk());
        // approving the es variant must not retire the en one
        JsonNode detail = json(mvc.perform(get("/api/v1/templates/" + id).header("Authorization", author)).andReturn());
        List<String> statuses = new ArrayList<>();
        detail.get("versions").forEach(v -> statuses.add(v.get("locale").asText() + ":" + v.get("status").asText()));
        assertThat(statuses).containsExactlyInAnyOrder("en:PUBLISHED", "es:PUBLISHED");

        // a recipient whose locale is es gets the es text pinned (rcp-004 is es)
        Map<String, Object> m = message(code);
        m.put("recipientId", "rcp-004");
        m.put("template", Map.of("code", code));
        String msg = submitOk(sender(RETAIL), m);
        mvc.perform(get("/api/v1/messages/" + msg).header("Authorization", sender(RETAIL)))
                .andExpect(jsonPath("$.template.locale", is("es")));
        // unknown locales fall back to en
        m.put("template", Map.of("code", code, "locale", "fr-CA"));
        String fr = submitOk(sender(RETAIL), m);
        mvc.perform(get("/api/v1/messages/" + fr).header("Authorization", sender(RETAIL)))
                .andExpect(jsonPath("$.template.locale", is("en")));
    }

    @Test
    void creationValidationRejectsUnsafeOrInconsistentTemplates() throws Exception {
        String author = author();
        Map<String, Object> expr = createBody(code(), "EMAIL");
        expr.put("body", "<p>{{ 7*7 }}</p>");
        mvc.perform(postJson("/api/v1/templates", author, expr)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("TEMPLATE_RENDER_ERROR")));

        Map<String, Object> undeclared = createBody(code(), "EMAIL");
        undeclared.put("body", "<p>{{name}} {{secret}}</p>");
        mvc.perform(postJson("/api/v1/templates", author, undeclared)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].message", containsString("secret")));

        Map<String, Object> badVar = createBody(code(), "EMAIL");
        badVar.put("variables", List.of("name", "not valid!"));
        mvc.perform(postJson("/api/v1/templates", author, badVar)).andExpect(status().isUnprocessableEntity());

        Map<String, Object> smsSubject = createBody(code(), "SMS");
        smsSubject.put("subject", "no subject allowed");
        mvc.perform(postJson("/api/v1/templates", author, smsSubject)).andExpect(status().isUnprocessableEntity());

        Map<String, Object> noSubject = createBody(code(), "EMAIL");
        noSubject.remove("subject");
        mvc.perform(postJson("/api/v1/templates", author, noSubject)).andExpect(status().isUnprocessableEntity());

        Map<String, Object> badCode = createBody("bad code!", "EMAIL");
        mvc.perform(postJson("/api/v1/templates", author, badCode)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));

        Map<String, Object> badLocale = createBody(code(), "EMAIL");
        badLocale.put("locale", "english please");
        mvc.perform(postJson("/api/v1/templates", author, badLocale)).andExpect(status().isBadRequest());

        Map<String, Object> badChannel = createBody(code(), "EMAIL");
        badChannel.put("channel", "TELEGRAM");
        mvc.perform(postJson("/api/v1/templates", author, badChannel)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("UNSUPPORTED_CHANNEL")));

        String c = code();
        create(author, createBody(c, "EMAIL"));
        mvc.perform(postJson("/api/v1/templates", author, createBody(c, "EMAIL"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("DUPLICATE_RESOURCE")));
        // the same code on another channel is a different template
        create(author, createBody(c, "SMS"));
    }

    @Test
    void roleChecksOnTheAuthoringWorkflow() throws Exception {
        Map<String, Object> body = createBody(code(), "EMAIL");
        mvc.perform(postJson("/api/v1/templates", sender(RETAIL), body)).andExpect(status().isForbidden());
        mvc.perform(postJson("/api/v1/templates", approver(), body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/templates").contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
        String id = create(admin(RETAIL), body).get("id").asText(); // TENANT_ADMIN may create
        // but cannot submit (author only) or approve (approver only)
        mvc.perform(post("/api/v1/templates/" + id + "/versions/1/submit").header("Authorization", admin(RETAIL)))
                .andExpect(status().isForbidden());
        submitVersion(id, 1, author());
        mvc.perform(post("/api/v1/templates/" + id + "/versions/1/approve").header("Authorization", author()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/templates/" + id + "/versions/1/approve").header("Authorization", admin(RETAIL)))
                .andExpect(status().isForbidden());
        // anyone authenticated can read
        mvc.perform(get("/api/v1/templates/" + id).header("Authorization", sender(RETAIL))).andExpect(status().isOk());
        // unknown ids and versions
        mvc.perform(get("/api/v1/templates/nope").header("Authorization", author())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/templates/" + id + "/versions/9/submit").header("Authorization", author()))
                .andExpect(status().isNotFound());
    }

    @Test
    void listFiltersAndPagination() throws Exception {
        String author = author();
        String c = code();
        create(author, createBody(c, "EMAIL"));
        mvc.perform(get("/api/v1/templates").header("Authorization", author).param("code", c))
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].latestVersionNo", is(1)))
                .andExpect(jsonPath("$.content[0].versionCounts.DRAFT", is(1)));
        mvc.perform(get("/api/v1/templates").header("Authorization", author).param("code", c).param("channel", "SMS"))
                .andExpect(jsonPath("$.totalElements", is(0)));
        mvc.perform(get("/api/v1/templates").header("Authorization", author).param("code", c).param("status", "PUBLISHED"))
                .andExpect(jsonPath("$.totalElements", is(0)));
        mvc.perform(get("/api/v1/templates").header("Authorization", author).param("code", c).param("status", "DRAFT"))
                .andExpect(jsonPath("$.totalElements", is(1)));
        // seeded published templates: status filter finds them, page size is honoured
        mvc.perform(get("/api/v1/templates").header("Authorization", author).param("status", "PUBLISHED").param("size", "3"))
                .andExpect(jsonPath("$.content.length()", is(3)))
                .andExpect(jsonPath("$.content[0].publishedLocales", hasItem("en")));
        mvc.perform(get("/api/v1/templates").header("Authorization", author).param("code", "trade_confirmation"))
                .andExpect(jsonPath("$.totalElements", is(2)));
    }

    @Test
    void previewRendersSafelyAndReportsProblemsWithoutSendingAnything() throws Exception {
        String auth = author();
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("channel", "EMAIL");
        ok.put("subject", "Hi {{name}}");
        ok.put("body", "<p>{{name}} owes {{amount|0.00}}</p>");
        ok.put("sampleData", Map.of("name", "<script>alert(1)</script>"));
        mvc.perform(postJson("/api/v1/templates/preview", auth, ok)).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid", is(true)))
                .andExpect(jsonPath("$.body", is("<p>&lt;script&gt;alert(1)&lt;/script&gt; owes 0.00</p>")))
                .andExpect(jsonPath("$.html", is(true)));

        Map<String, Object> sms = new LinkedHashMap<>();
        sms.put("channel", "SMS");
        sms.put("body", "Code {{code}}");
        sms.put("sampleData", Map.of("code", "€€€"));
        mvc.perform(postJson("/api/v1/templates/preview", operator(RETAIL), sms)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sms.encoding", is("GSM-7")))
                .andExpect(jsonPath("$.sms.segments", is(1)));

        Map<String, Object> bad = new LinkedHashMap<>(ok);
        bad.put("body", "<p>{{ 7*7 }}</p>");
        mvc.perform(postJson("/api/v1/templates/preview", auth, bad)).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid", is(false)))
                .andExpect(jsonPath("$.errors[0].message", containsString("no expressions")));

        Map<String, Object> missing = new LinkedHashMap<>(ok);
        missing.put("body", "<p>{{name}} {{other}}</p>");
        mvc.perform(postJson("/api/v1/templates/preview", auth, missing)).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid", is(false)))
                .andExpect(jsonPath("$.errors[*].field", hasItem("variables.other")));

        Map<String, Object> undeclared = new LinkedHashMap<>(ok);
        undeclared.put("variables", List.of("name"));
        undeclared.put("body", "<p>{{name}} {{other}}</p>");
        mvc.perform(postJson("/api/v1/templates/preview", auth, undeclared)).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid", is(false)));

        // preview is not available to senders
        mvc.perform(postJson("/api/v1/templates/preview", sender(RETAIL), ok)).andExpect(status().isForbidden());
        // nothing was persisted
        Integer count = jdbc.queryForObject("select count(*) from template where code like 'PREVIEW%'", Integer.class);
        assertThat(count).isZero();
    }
}
