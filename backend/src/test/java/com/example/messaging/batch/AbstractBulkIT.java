package com.example.messaging.batch;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.messaging.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Helpers for the bulk integration tests (CSV building, multipart upload, waiting for a job, token with scope). */
public abstract class AbstractBulkIT extends AbstractIntegrationTest {

    public static final String HEADER = SyntheticDatasetGenerator.HEADER;
    public static final Set<String> TERMINAL = Set.of("COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED", "STOPPED",
            "ABANDONED");

    @Autowired
    protected SyntheticRecipientProvisioner provisioner;
    @Autowired
    protected JobExplorer explorer;
    @Autowired
    protected BulkWriterStats writerStats;
    @Autowired
    protected org.springframework.batch.core.repository.JobRepository jobRepository;
    @Autowired
    protected BulkUploadStorage storage;
    @Autowired
    protected BulkJobSweeper sweeper;

    // ------------------------------------------------------------------ auth

    protected String scoped(String subject, String tenant, List<String> roles, List<String> scopes) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", subject);
        body.put("tenant", tenant);
        body.put("roles", roles);
        body.put("scopes", scopes);
        MvcResult r = mvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body))).andReturn();
        return "Bearer " + om.readTree(r.getResponse().getContentAsString()).get("accessToken").asText();
    }

    /** SENDER with scope messages:bulk. */
    protected String bulkSender(String tenant) throws Exception {
        return scoped("bulk-sender-" + tenant, tenant, List.of("SENDER"), List.of("messages:bulk"));
    }

    // ------------------------------------------------------------------ csv

    protected static String row(String ref, String locale, String last4, String first, String side, String qty,
            String symbol, String price) {
        return String.join(",", ref, locale, last4, first, side, qty, symbol, price);
    }

    /** A valid row for synthetic recipient {@code n}. */
    protected static String ok(int n) {
        return row(SyntheticRecipientProvisioner.externalRef(n), "en", "1234", "Pat", "buy", "10", "ACME", "12.50");
    }

    protected static byte[] csv(List<String> lines) {
        List<String> all = new ArrayList<>();
        all.add(HEADER);
        all.addAll(lines);
        return (String.join("\n", all) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    protected static List<String> okRows(int from, int to) {
        List<String> l = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            l.add(ok(i));
        }
        return l;
    }

    // ------------------------------------------------------------------ upload / api

    protected MockHttpServletRequestBuilder uploadRequest(String auth, byte[] content, String fileName,
            String contentType, Consumer<Map<String, String>> params) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("name", "test-job");
        p.put("templateCode", "TRADE_CONFIRMATION");
        p.put("channel", "EMAIL");
        p.put("category", "TRANSACTIONAL");
        params.accept(p);
        MockHttpServletRequestBuilder b = multipart("/api/v1/bulk-jobs")
                .file(new MockMultipartFile("file", fileName, contentType, content))
                .header("Authorization", auth);
        p.forEach(b::param);
        return b;
    }

    protected MvcResult upload(String auth, byte[] content) throws Exception {
        return mvc.perform(uploadRequest(auth, content, "recipients.csv", "text/csv", p -> { })).andReturn();
    }

    /** Uploads (asserting 202) and returns the job id. */
    protected String uploadOk(String auth, byte[] content, Consumer<Map<String, String>> params) throws Exception {
        MvcResult r = mvc.perform(uploadRequest(auth, content, "recipients.csv", "text/csv", params)).andReturn();
        if (r.getResponse().getStatus() != 202) {
            throw new IllegalStateException("upload failed " + r.getResponse().getStatus() + ": "
                    + r.getResponse().getContentAsString());
        }
        return json(r).get("id").asText();
    }

    protected String uploadOk(String auth, byte[] content) throws Exception {
        return uploadOk(auth, content, p -> { });
    }

    protected JsonNode getJob(String auth, String id) throws Exception {
        return json(mvc.perform(get("/api/v1/bulk-jobs/" + id).header("Authorization", auth)).andReturn());
    }

    protected String statusOfJob(String id) {
        return jdbc.queryForObject("select status from bulk_job where id = ?", String.class, id);
    }

    /**
     * Waits until the job reached a terminal status AND its batch execution finished (the listener sets the status a
     * moment before the batch repository closes the execution).
     */
    protected String awaitTerminal(String id, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String s = statusOfJob(id);
            if (TERMINAL.contains(s) && executionsFinished(id)) {
                return s;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("bulk job " + id + " did not finish within " + timeout + ", status="
                + statusOfJob(id) + " counters=" + java.util.Arrays.toString(counters(id)));
    }

    protected String awaitTerminal(String id) throws Exception {
        return awaitTerminal(id, Duration.ofSeconds(90));
    }

    protected boolean executionsFinished(String id) {
        Long exec = jdbc.queryForObject("select spring_batch_execution_id from bulk_job where id = ?", Long.class, id);
        if (exec == null) {
            return false;
        }
        var e = explorer.getJobExecution(exec);
        return e != null && !e.isRunning() && e.getEndTime() != null;
    }

    /** {total, processed, succeeded, failed, duplicate} straight from the database. */
    protected long[] counters(String id) {
        return jdbc.queryForObject("select total, processed, succeeded, failed, duplicate from bulk_job where id = ?",
                (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)}, id);
    }

    protected long messageCount(String jobId) {
        return jdbc.queryForObject("select count(*) from message_request where bulk_job_id = ?", Long.class, jobId);
    }

    protected long distinctKeyCount(String jobId) {
        return jdbc.queryForObject("select count(distinct idempotency_key) from message_request where bulk_job_id = ?",
                Long.class, jobId);
    }

    protected long failureCount(String jobId, String reasonPrefix) {
        return jdbc.queryForObject("select count(*) from bulk_job_failure where job_id = ? and reason like ?",
                Long.class, jobId, reasonPrefix + "%");
    }

    /**
     * Simulates a job whose JVM died: a RUNNING bulk job whose batch execution is STARTED but that no thread of this JVM
     * executes. Returns the job id.
     */
    protected String orphanedRunningJob(String tenant, List<String> lines) throws Exception {
        String jobId = com.example.messaging.common.Ids.newId();
        var stored = storage.store(tenant, jobId, new java.io.ByteArrayInputStream(csv(lines)), "orphan.csv", "text/csv");
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        jdbc.update("insert into bulk_job (id, tenant_id, name, status, template_code, channel, category, total, "
                + "file_name, created_by, started_at, created_at, updated_at, version) values (?, ?, 'orphan', 'RUNNING', "
                + "'TRADE_CONFIRMATION', 'EMAIL', 'TRANSACTIONAL', ?, 'orphan.csv', 'bulk-sender-' || ?, ?, ?, ?, 0)",
                jobId, tenant, (long) lines.size(), tenant, now, now, now);
        var params = new org.springframework.batch.core.JobParametersBuilder()
                .addString(BulkJobConfig.PARAM_JOB_ID, jobId).addString(BulkJobConfig.PARAM_TENANT, tenant)
                .addString(BulkJobConfig.PARAM_FILE, stored.path().toString()).toJobParameters();
        var execution = jobRepository.createJobExecution(BulkJobConfig.JOB_NAME, params);
        execution.setStatus(org.springframework.batch.core.BatchStatus.STARTED);
        execution.setStartTime(java.time.LocalDateTime.now());
        jobRepository.update(execution);
        jdbc.update("update bulk_job set spring_batch_execution_id = ? where id = ?", execution.getId(), jobId);
        return jobId;
    }

    protected org.springframework.batch.core.BatchStatus executionStatus(String jobId) {
        Long exec = jdbc.queryForObject("select spring_batch_execution_id from bulk_job where id = ?", Long.class, jobId);
        return explorer.getJobExecution(exec).getStatus();
    }

    /** Failure rows as text, for assertion messages. */
    protected String failureSummary(String jobId) {
        return jdbc.queryForList("select line_no, reason from bulk_job_failure where job_id = ? order by line_no "
                + "fetch first 30 rows only", jobId).toString() + " ledger="
                + jdbc.queryForList("select external_ref, line_no from bulk_job_row where job_id = ? and external_ref in "
                + "('SYN-0000001','SYN-0000005','SYN-0000030')", jobId);
    }

    protected long openClaims(String jobId) {
        return jdbc.queryForObject("select count(*) from idempotency_record where idem_key like ? and status <> 'COMPLETED'",
                Long.class, "bulk-" + jobId + "-%");
    }
}
