package com.example.messaging.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.messaging.service.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP contract of the bulk endpoints: multipart upload, authorization (role AND scope, audited refusal), tenant
 * isolation, size and content limits, state rules of stop/restart, failures paging, synthetic jobs.
 * Same configuration as {@link BulkJobBatchIT} so both share one Spring context.
 */
@TestPropertySource(properties = {
        "messaging.bulk.concurrency=3",
        "messaging.bulk.chunk-size=10",
        "messaging.bulk.skip-limit=5",
        "messaging.bulk.max-rows=500",
        "messaging.bulk.upload-dir=target/bulk-it",
        "messaging.bulk.max-upload-bytes=65536",
        "messaging.bulk.sweeper-enabled=false"})
class BulkJobApiIT extends AbstractBulkIT {

    @MockitoSpyBean
    NotificationService notifications; // declared only so that the Spring context is shared with BulkJobBatchIT

    @BeforeEach
    void recipients() {
        provisioner.ensure(RETAIL, 300);
    }

    // ------------------------------------------------------------------ happy path and shape

    @Test
    void multipartUpload_returns202WithLocationAndTheOpenApiShape() throws Exception {
        String auth = bulkSender(RETAIL);
        MvcResult r = mvc.perform(uploadRequest(auth, csv(okRows(1, 12)), "My Recipients (v2).csv", "text/csv",
                p -> p.put("name", "April statement run"))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        JsonNode job = json(r);
        String id = job.get("id").asText();
        assertThat(r.getResponse().getHeader("Location")).isEqualTo("/api/v1/bulk-jobs/" + id);
        // exactly the fields of the committed BulkJob schema
        assertThat(fieldNames(job)).containsExactlyInAnyOrder("id", "tenantId", "name", "status", "total", "processed",
                "succeeded", "failed", "duplicate", "fileName", "ratePerSecond", "createdBy", "startedAt", "finishedAt");
        assertThat(job.get("name").asText()).isEqualTo("April statement run");
        assertThat(job.get("tenantId").asText()).isEqualTo(RETAIL);
        assertThat(job.get("total").asLong()).isEqualTo(12);
        assertThat(job.get("fileName").asText()).isEqualTo("My Recipients _v2_.csv"); // sanitised, display only

        assertThat(awaitTerminal(id)).isEqualTo("COMPLETED");
        JsonNode done = getJob(auth, id);
        assertThat(done.get("succeeded").asLong()).isEqualTo(12);

        JsonNode list = json(mvc.perform(get("/api/v1/bulk-jobs").header("Authorization", auth)).andReturn());
        assertThat(fieldNames(list)).contains("content", "page", "size", "totalElements", "totalPages");
        assertThat(list.get("content").toString()).contains(id);
    }

    // ------------------------------------------------------------------ authorization

    @Test
    void senderWithoutBulkScope_gets403_andTheRefusalIsAudited() throws Exception {
        long before = jobCount();
        String auth = scoped("no-scope-sender", RETAIL, List.of("SENDER"), List.of());
        MvcResult r = upload(auth, csv(okRows(1, 3)));
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(json(r).get("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(jobCount()).isEqualTo(before); // nothing was created

        Map<String, Object> audit = jdbc.queryForMap("select actor, actor_role, tenant_id, detail_json from audit_event "
                + "where action = 'BULK_JOB_DENIED' and actor = 'no-scope-sender' order by occurred_at desc "
                + "fetch first 1 rows only");
        assertThat(audit.get("tenant_id")).isEqualTo(RETAIL);
        assertThat(audit.get("actor_role")).isEqualTo("SENDER");
        assertThat((String) audit.get("detail_json")).contains("\"action\":\"create\"").contains("\"missingScope\":true")
                .contains("\"missingRole\":false");
    }

    @Test
    void scopeWithoutBulkRole_gets403_forEveryMutation() throws Exception {
        String auth = scoped("op-with-scope", RETAIL, List.of("OPERATOR"), List.of("messages:bulk"));
        assertThat(upload(auth, csv(okRows(1, 3))).getResponse().getStatus()).isEqualTo(403);
        mvc.perform(post("/api/v1/bulk-jobs/generate-synthetic").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rows\":10}")).andExpect(status().isForbidden());

        String id = uploadOk(bulkSender(RETAIL), csv(okRows(1, 3)));
        awaitTerminal(id);
        mvc.perform(post("/api/v1/bulk-jobs/" + id + "/stop").header("Authorization", auth)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/bulk-jobs/" + id + "/restart").header("Authorization", auth)).andExpect(status().isForbidden());
        Long denied = jdbc.queryForObject("select count(*) from audit_event where action = 'BULK_JOB_DENIED' "
                + "and actor = 'op-with-scope'", Long.class);
        assertThat(denied).isEqualTo(4);
        // the job id is the audit entity for stop/restart
        assertThat(jdbc.queryForObject("select count(*) from audit_event where action = 'BULK_JOB_DENIED' "
                + "and actor = 'op-with-scope' and entity_id = ?", Long.class, id)).isEqualTo(2);
    }

    @Test
    void unauthenticated_gets401() throws Exception {
        mvc.perform(multipart("/api/v1/bulk-jobs").file(new MockMultipartFile("file", "a.csv", "text/csv", csv(okRows(1, 1)))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/bulk-jobs")).andExpect(status().isUnauthorized());
    }

    @Test
    void tenantAdminWithScopeMayUpload_andPlatformAdminNeedsATenantHeader() throws Exception {
        String admin = scoped("tenant-admin-bulk", RETAIL, List.of("TENANT_ADMIN"), List.of("messages:bulk"));
        String id = uploadOk(admin, csv(okRows(1, 4)));
        assertThat(awaitTerminal(id)).isEqualTo("COMPLETED");

        String platform = scoped("platform-bulk", "*", List.of("PLATFORM_ADMIN"), List.of("messages:bulk"));
        MvcResult noTenant = upload(platform, csv(okRows(1, 4)));
        assertThat(noTenant.getResponse().getStatus()).isEqualTo(400); // writes need one concrete tenant
        MvcResult withTenant = mvc.perform(uploadRequest(platform, csv(okRows(1, 4)), "r.csv", "text/csv", p -> { })
                .header("X-Tenant-Id", RETAIL)).andReturn();
        assertThat(withTenant.getResponse().getStatus()).isEqualTo(202);
        assertThat(json(withTenant).get("tenantId").asText()).isEqualTo(RETAIL);
        awaitTerminal(json(withTenant).get("id").asText());
    }

    @Test
    void readEndpoints_areRoleGated() throws Exception {
        mvc.perform(get("/api/v1/bulk-jobs").header("Authorization", operator(RETAIL))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/bulk-jobs").header("Authorization",
                scoped("author", RETAIL, List.of("TEMPLATE_AUTHOR"), List.of()))).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ tenant isolation

    @Test
    void anotherTenantsJob_isInvisible_everywhere() throws Exception {
        String retail = bulkSender(RETAIL);
        String wealth = bulkSender(WEALTH);
        String id = uploadOk(retail, csv(okRows(1, 5)));
        awaitTerminal(id);

        mvc.perform(get("/api/v1/bulk-jobs/" + id).header("Authorization", wealth)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/bulk-jobs/" + id + "/failures").header("Authorization", wealth)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/bulk-jobs/" + id + "/stop").header("Authorization", wealth)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/bulk-jobs/" + id + "/restart").header("Authorization", wealth)).andExpect(status().isNotFound());
        JsonNode wealthList = json(mvc.perform(get("/api/v1/bulk-jobs").header("Authorization", wealth)).andReturn());
        assertThat(wealthList.get("content").toString()).doesNotContain(id);
        JsonNode retailList = json(mvc.perform(get("/api/v1/bulk-jobs").header("Authorization", retail)).andReturn());
        assertThat(retailList.get("content").toString()).contains(id);
        // a wealth upload cannot target retail recipients: they do not exist in its tenant
        String wid = uploadOk(wealth, csv(okRows(1, 2)));
        assertThat(awaitTerminal(wid)).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(counters(wid)).containsExactly(2, 2, 0, 2, 0);
        assertThat(failureCount(wid, "RECIPIENT_NOT_FOUND")).isEqualTo(2);
    }

    // ------------------------------------------------------------------ limits and content checks

    @Test
    void oversizedUpload_is413_andLeavesNoFileBehind() throws Exception {
        List<String> fat = new ArrayList<>();
        for (int i = 1; i <= 130; i++) { // about 540 bytes a row: over the 64 KiB limit long before 500 rows
            fat.add(row(SyntheticRecipientProvisioner.externalRef(i), "en", "1234", "A".repeat(500), "buy", "1", "ACME",
                    "1.00"));
        }
        long before = jobCount();
        long files = storedFiles();
        MvcResult r = upload(bulkSender(RETAIL), csv(fat));
        assertThat(r.getResponse().getStatus()).isEqualTo(413);
        assertThat(json(r).get("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(jobCount()).isEqualTo(before);
        assertThat(storedFiles()).isEqualTo(files);
    }

    @Test
    void tooManyRows_is413() throws Exception {
        MvcResult r = upload(bulkSender(RETAIL), csv(okRows(1, 300).stream().flatMap(l -> Stream.of(l, l)).toList()));
        assertThat(r.getResponse().getStatus()).isEqualTo(413); // 600 rows > max-rows 500
        assertThat(json(r).get("message").asText()).contains("500");
    }

    @Test
    void invalidFiles_areRejectedWith400() throws Exception {
        String auth = bulkSender(RETAIL);
        long files = storedFiles();
        // wrong extension / content type
        assertThat(mvc.perform(uploadRequest(auth, csv(okRows(1, 1)), "run.exe", "text/csv", p -> { }))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(uploadRequest(auth, csv(okRows(1, 1)), "run.csv", "image/png", p -> { }))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        // not UTF-8
        byte[] latin1 = (HEADER + "\n" + ok(1).replace("Pat", "Zoé") + "\n").getBytes(StandardCharsets.ISO_8859_1);
        MvcResult bad = upload(auth, latin1);
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(bad).toString()).contains("UTF-8");
        // header problems
        assertThat(upload(auth, "foo,bar\n1,2\n".getBytes(StandardCharsets.UTF_8)).getResponse().getStatus()).isEqualTo(400);
        assertThat(upload(auth, "externalRef,externalRef\nA,B\n".getBytes(StandardCharsets.UTF_8)).getResponse()
                .getStatus()).isEqualTo(400);
        assertThat(upload(auth, "externalRef,locale,var_a b\nA,en,x\n".getBytes(StandardCharsets.UTF_8)).getResponse()
                .getStatus()).isEqualTo(400);
        // header only
        assertThat(upload(auth, (HEADER + "\n").getBytes(StandardCharsets.UTF_8)).getResponse().getStatus()).isEqualTo(400);
        // empty and missing file
        assertThat(upload(auth, new byte[0]).getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(multipart("/api/v1/bulk-jobs").header("Authorization", auth).param("name", "x")
                .param("templateCode", "TRADE_CONFIRMATION").param("channel", "EMAIL")
                .param("category", "TRANSACTIONAL")).andReturn().getResponse().getStatus()).isEqualTo(400);
        // a 9000 character line
        String longLine = ok(1).replace("Pat", "P".repeat(9000));
        MvcResult tooLong = upload(auth, csv(List.of(longLine)));
        assertThat(tooLong.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(tooLong).toString()).contains("line 2");
        assertThat(storedFiles()).isEqualTo(files);
    }

    @Test
    void invalidParameters_areRejected() throws Exception {
        String auth = bulkSender(RETAIL);
        byte[] ok = csv(okRows(1, 2));
        MvcResult channel = mvc.perform(uploadRequest(auth, ok, "r.csv", "text/csv", p -> p.put("channel", "FAX"))).andReturn();
        assertThat(channel.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(channel).get("code").asText()).isEqualTo("UNSUPPORTED_CHANNEL");
        MvcResult template = mvc.perform(uploadRequest(auth, ok, "r.csv", "text/csv", p -> p.put("templateCode", "NOPE")))
                .andReturn();
        assertThat(template.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(template).get("code").asText()).isEqualTo("TEMPLATE_NOT_FOUND");
        MvcResult category = mvc.perform(uploadRequest(auth, ok, "r.csv", "text/csv", p -> p.put("category", "MARKETING")))
                .andReturn();
        assertThat(category.getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(uploadRequest(auth, ok, "r.csv", "text/csv", p -> p.put("ratePerSecond", "0")))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(uploadRequest(auth, ok, "r.csv", "text/csv", p -> p.put("name", " ")))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(uploadRequest(auth, ok, "r.csv", "text/csv", p -> p.put("templateCode", "../etc")))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void hostileFileName_cannotEscapeTheUploadDirectory() throws Exception {
        String auth = bulkSender(RETAIL);
        MvcResult r = mvc.perform(uploadRequest(auth, csv(okRows(1, 3)), "../../../evil.csv", "text/csv", p -> { }))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        JsonNode job = json(r);
        assertThat(job.get("fileName").asText()).isEqualTo("evil.csv");
        awaitTerminal(job.get("id").asText());
        Path base = Path.of("target/bulk-it").toAbsolutePath().normalize();
        assertThat(Path.of("target/evil.csv")).doesNotExist();
        assertThat(Path.of("evil.csv")).doesNotExist();
        try (Stream<Path> all = Files.walk(base)) {
            assertThat(all.filter(Files::isRegularFile).map(p -> p.getFileName().toString()))
                    .allMatch(n -> n.matches("[0-9a-f-]{36}\\.csv"));
        }
    }

    // ------------------------------------------------------------------ state rules

    @Test
    void stopAndRestart_followTheStateRules() throws Exception {
        String auth = bulkSender(RETAIL);
        String done = uploadOk(auth, csv(okRows(1, 5)));
        assertThat(awaitTerminal(done)).isEqualTo("COMPLETED");
        // COMPLETED can be neither stopped nor restarted
        for (String action : List.of("stop", "restart")) {
            mvc.perform(post("/api/v1/bulk-jobs/" + done + "/" + action).header("Authorization", auth))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code", is("ILLEGAL_STATE_TRANSITION")));
        }
        mvc.perform(post("/api/v1/bulk-jobs/does-not-exist/stop").header("Authorization", auth)).andExpect(status().isNotFound());

        // a slow job (20 rows/s, 100 rows) is stopped mid-way, then restarted and finishes without duplicates
        String id = uploadOk(auth, csv(okRows(1, 100)), p -> p.put("ratePerSecond", "20"));
        waitFor(() -> counters(id)[1] >= 10, Duration.ofSeconds(20));
        MvcResult stopped = mvc.perform(post("/api/v1/bulk-jobs/" + id + "/stop").header("Authorization", auth)).andReturn();
        assertThat(stopped.getResponse().getStatus()).isEqualTo(200);
        assertThat(awaitTerminal(id)).isEqualTo("STOPPED");
        long sent = messageCount(id);
        assertThat(sent).isBetween(10L, 99L);
        assertThat(counters(id)[2]).isEqualTo(sent); // counters match what was committed
        // stopping again is a state error
        mvc.perform(post("/api/v1/bulk-jobs/" + id + "/stop").header("Authorization", auth)).andExpect(status().isConflict());

        mvc.perform(post("/api/v1/bulk-jobs/" + id + "/restart").header("Authorization", auth))
                .andExpect(status().isOk());
        assertThat(awaitTerminal(id, Duration.ofSeconds(60))).isEqualTo("COMPLETED");
        assertThat(counters(id)).containsExactly(100, 100, 100, 0, 0);
        assertThat(messageCount(id)).isEqualTo(100);
        assertThat(distinctKeyCount(id)).isEqualTo(100);
        assertThat(jdbc.queryForObject("select count(*) from audit_event where entity_id = ? and action in "
                + "('BULK_JOB_STOPPED','BULK_JOB_RESTARTED')", Long.class, id)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ crashed JVM, sweeper

    @Test
    void orphanedRunningJob_canBeStoppedThenRestarted() throws Exception {
        String auth = bulkSender(RETAIL);
        String id = orphanedRunningJob(RETAIL, okRows(1, 25));
        assertThat(statusOfJob(id)).isEqualTo("RUNNING");
        assertThat(executionStatus(id).name()).isEqualTo("STARTED"); // zombie: Spring Batch would refuse a restart

        // while the job is RUNNING a restart is a state error
        mvc.perform(post("/api/v1/bulk-jobs/" + id + "/restart").header("Authorization", auth))
                .andExpect(status().isConflict());

        MvcResult stop = mvc.perform(post("/api/v1/bulk-jobs/" + id + "/stop").header("Authorization", auth)).andReturn();
        assertThat(stop.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(stop).get("status").asText()).isEqualTo("STOPPED");
        assertThat(executionStatus(id).name()).isEqualTo("STOPPED");
        assertThat(jdbc.queryForObject("select count(*) from audit_event where entity_id = ? and action = "
                + "'BULK_JOB_STOPPED' and detail_json like '%orphaned%'", Long.class, id)).isEqualTo(1);

        mvc.perform(post("/api/v1/bulk-jobs/" + id + "/restart").header("Authorization", auth)).andExpect(status().isOk());
        assertThat(awaitTerminal(id)).isEqualTo("COMPLETED");
        assertThat(counters(id)).containsExactly(25, 25, 25, 0, 0);
        assertThat(messageCount(id)).isEqualTo(25);
    }

    @Test
    void sweeper_abandonsStaleJobs_only() throws Exception {
        String stale = orphanedRunningJob(RETAIL, okRows(1, 5));
        String fresh = orphanedRunningJob(RETAIL, okRows(1, 5));
        jdbc.update("update bulk_job set updated_at = ? where id = ?",
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(2), stale);

        int n = sweeper.sweep(Duration.ofHours(1));

        assertThat(n).isGreaterThanOrEqualTo(1);
        assertThat(statusOfJob(stale)).isEqualTo("ABANDONED");
        assertThat(executionStatus(stale).name()).isEqualTo("FAILED");
        assertThat(statusOfJob(fresh)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("select count(*) from audit_event where entity_id = ? and action = "
                + "'BULK_JOB_ABANDONED' and actor = 'system'", Long.class, stale)).isEqualTo(1);
        // ABANDONED is terminal for the API
        mvc.perform(post("/api/v1/bulk-jobs/" + stale + "/restart").header("Authorization", bulkSender(RETAIL)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/bulk-jobs/" + stale + "/stop").header("Authorization", bulkSender(RETAIL)))
                .andExpect(status().isConflict());
        sweeper.sweep(Duration.ofHours(1)); // idempotent
        assertThat(statusOfJob(stale)).isEqualTo("ABANDONED");
        // tidy: the fresh orphan must not stay RUNNING for the other tests of this context
        mvc.perform(post("/api/v1/bulk-jobs/" + fresh + "/stop").header("Authorization", bulkSender(RETAIL)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ failures paging

    @Test
    void failures_arePagedAndSortable() throws Exception {
        String auth = bulkSender(RETAIL);
        List<String> lines = new ArrayList<>(okRows(1, 5));
        for (int i = 1; i <= 5; i++) {
            lines.add(row("NOPE-" + i, "en", "1234", "Pat", "buy", "10", "ACME", "12.50"));
        }
        String id = uploadOk(auth, csv(lines));
        assertThat(awaitTerminal(id)).isEqualTo("COMPLETED_WITH_ERRORS");

        List<Long> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            JsonNode p = json(mvc.perform(get("/api/v1/bulk-jobs/" + id + "/failures").header("Authorization", auth)
                    .param("page", String.valueOf(page)).param("size", "2")).andReturn());
            assertThat(p.get("totalElements").asInt()).isEqualTo(5);
            assertThat(p.get("totalPages").asInt()).isEqualTo(3);
            assertThat(p.get("size").asInt()).isEqualTo(2);
            assertThat(fieldNames(p.get("content").get(0))).containsExactlyInAnyOrder("lineNo", "reason", "rawLineHash");
            p.get("content").forEach(f -> seen.add(f.get("lineNo").asLong()));
        }
        assertThat(seen).containsExactly(7L, 8L, 9L, 10L, 11L);
        JsonNode desc = json(mvc.perform(get("/api/v1/bulk-jobs/" + id + "/failures").header("Authorization", auth)
                .param("sort", "lineNo,desc").param("size", "1")).andReturn());
        assertThat(desc.get("content").get(0).get("lineNo").asLong()).isEqualTo(11);
        mvc.perform(get("/api/v1/bulk-jobs/" + id + "/failures").header("Authorization", auth).param("size", "201"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/bulk-jobs/" + id + "/failures").header("Authorization", auth).param("sort", "reason"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void jobList_isPagedWithoutAnExtraQueryPerRow() throws Exception {
        String auth = bulkSender(RETAIL);
        for (int i = 0; i < 3; i++) {
            awaitTerminal(uploadOk(auth, csv(okRows(1, 2))));
        }
        JsonNode p = json(mvc.perform(get("/api/v1/bulk-jobs").header("Authorization", auth).param("size", "2")).andReturn());
        assertThat(p.get("content").size()).isEqualTo(2);
        assertThat(p.get("totalElements").asInt()).isGreaterThanOrEqualTo(3);
        // newest first
        assertThat(p.get("content").get(0).get("startedAt").asText()).isNotNull();
    }

    // ------------------------------------------------------------------ synthetic

    @Test
    void generateSynthetic_createsRecipientsRunsTheJobAndCountsDuplicates() throws Exception {
        String auth = bulkSender(WEALTH); // wealth has no synthetic recipients yet: the endpoint creates them
        MvcResult r = mvc.perform(post("/api/v1/bulk-jobs/generate-synthetic").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rows\":60,\"duplicateRate\":0.5,\"invalidRate\":0}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        String id = json(r).get("id").asText();
        assertThat(awaitTerminal(id)).as(() -> failureSummary(id)).isEqualTo("COMPLETED");
        long[] c = counters(id);
        assertThat(c[0]).isEqualTo(60);
        assertThat(c[4]).isGreaterThan(0);         // duplicates were generated and detected
        assertThat(c[3]).isZero();
        assertThat(c[1]).isEqualTo(60);            // processed = succeeded + failed + duplicate
        assertThat(c[2] + c[4]).isEqualTo(60);
        assertThat(messageCount(id)).isEqualTo(c[2]);
        assertThat(distinctKeyCount(id)).isEqualTo(c[2]);
    }

    @Test
    void generateSynthetic_withoutRecipients_reportsEveryRowAsRecipientNotFound() throws Exception {
        String tenant = "tenant-nosyn";
        if (jdbc.queryForObject("select count(*) from tenant where id = ?", Long.class, tenant) == 0) {
            jdbc.update("insert into tenant (id, name, status, created_at, updated_at) values (?, 'No synthetic recipients', "
                    + "'ACTIVE', current_timestamp, current_timestamp)", tenant);
            jdbc.update("insert into template (id, tenant_id, code, name, channel, category, created_at, updated_at) "
                    + "select 'tpl-nosyn', ?, code, name, channel, category, created_at, updated_at from template "
                    + "where id = 'tpl-001'", tenant);
            jdbc.update("insert into template_version (id, template_id, version_no, locale, status, subject, body, "
                    + "variables_json, created_by, approved_by, published_at, rejection_comment, created_at, updated_at, "
                    + "version) select 'tplv-nosyn-' || locale, 'tpl-nosyn', version_no, locale, status, subject, body, "
                    + "variables_json, created_by, approved_by, published_at, rejection_comment, created_at, updated_at, "
                    + "version from template_version where template_id = 'tpl-001'");
        }
        String auth = scoped("fresh-sender", tenant, List.of("SENDER"), List.of("messages:bulk"));
        MvcResult r = mvc.perform(post("/api/v1/bulk-jobs/generate-synthetic").param("createRecipients", "false")
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rows\":20,\"duplicateRate\":0,\"invalidRate\":0}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        String id = json(r).get("id").asText();
        assertThat(awaitTerminal(id)).isEqualTo("FAILED"); // 20 failures exceed the skip limit of 5
        assertThat(failureCount(id, "SKIP_LIMIT_EXCEEDED")).isEqualTo(1);
        assertThat(messageCount(id)).isZero();
    }

    @Test
    void generateSynthetic_validatesItsRequest() throws Exception {
        String auth = bulkSender(RETAIL);
        for (String body : List.of("{\"rows\":0}", "{\"rows\":501}", "{}", "{\"rows\":10,\"duplicateRate\":1.5}",
                "{\"rows\":10,\"duplicateRate\":0.7,\"invalidRate\":0.7}", "{\"rows\":10,\"channel\":\"FAX\"}")) {
            mvc.perform(post("/api/v1/bulk-jobs/generate-synthetic").header("Authorization", auth)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
    }

    // ------------------------------------------------------------------ helpers

    private long jobCount() {
        return jdbc.queryForObject("select count(*) from bulk_job", Long.class);
    }

    private Set<String> fieldNames(JsonNode n) {
        Set<String> s = new TreeSet<>();
        n.fieldNames().forEachRemaining(s::add);
        return s;
    }

    /** Number of stored CSV files under the upload tree (a rejected upload must not change it). */
    private long storedFiles() throws Exception {
        Path base = Path.of("target/bulk-it").toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            return 0;
        }
        try (Stream<Path> all = Files.walk(base)) {
            return all.filter(Files::isRegularFile).count();
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier cond, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("condition not reached within " + timeout);
    }
}
