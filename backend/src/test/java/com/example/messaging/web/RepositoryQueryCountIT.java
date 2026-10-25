package com.example.messaging.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.messaging.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Guards against N+1 queries: the number of SQL statements for a list/detail request must be small, constant and
 * independent of the number of rows returned (measured with Hibernate statistics around real HTTP requests).
 */
class RepositoryQueryCountIT extends AbstractIntegrationTest {

    @Autowired
    EntityManagerFactory emf;

    private Statistics stats;

    @BeforeEach
    void enableStatistics() {
        stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
    }

    /** @return {statementCount, entityLoads} */
    private long[] measure(Callable<MvcResult> request) throws Exception {
        stats.clear();
        MvcResult r = request.call();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return new long[] {stats.getPrepareStatementCount(), stats.getEntityLoadCount()};
    }

    private int size(MvcResult r) throws Exception {
        return json(r).get("content").size();
    }

    @Test
    void templateListWithVersionsUsesAConstantNumberOfStatements() throws Exception {
        String auth = bearer("author-qc", RETAIL, "TEMPLATE_AUTHOR");
        // both page sizes are smaller than the 8 seeded templates, so both need the count query
        long[] one = measure(() -> mvc.perform(get("/api/v1/templates").header("Authorization", auth).param("size", "2"))
                .andExpect(status().isOk()).andReturn());
        long[] many = measure(() -> mvc.perform(get("/api/v1/templates").header("Authorization", auth).param("size", "7"))
                .andExpect(status().isOk()).andReturn());
        MvcResult page = mvc.perform(get("/api/v1/templates").header("Authorization", auth).param("size", "10")).andReturn();
        assertThat(size(page)).as("seeded retail templates").isEqualTo(8);
        // select page + count + one versions query, regardless of how many templates are on the page
        assertThat(many[0]).isEqualTo(one[0]).isLessThanOrEqualTo(3);
        assertThat(many[1]).as("entities loaded grows with rows, statements do not").isGreaterThan(one[1]);
    }

    @Test
    void templateDetailLoadsVersionsInOneQuery() throws Exception {
        String auth = bearer("author-qc", RETAIL, "TEMPLATE_AUTHOR");
        String id = json(mvc.perform(get("/api/v1/templates").header("Authorization", auth).param("code", "TRADE_CONFIRMATION")
                .param("channel", "EMAIL")).andReturn()).get("content").get(0).get("id").asText();
        long[] d = measure(() -> mvc.perform(get("/api/v1/templates/" + id).header("Authorization", auth))
                .andExpect(status().isOk()).andReturn());
        assertThat(d[0]).as("template + versions").isLessThanOrEqualTo(2);
    }

    @Test
    void messageListIsASinglePageQueryPlusCount() throws Exception {
        String auth = sender(RETAIL);
        String prefix = "qc-" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < 12; i++) {
            Map<String, Object> b = tradeConfirmation("rcp-00" + (1 + i % 9));
            b.put("clientReference", prefix + "-" + i);
            submitOk(auth, b);
        }
        long[] one = measure(() -> mvc.perform(get("/api/v1/messages").header("Authorization", auth)
                .param("q", prefix).param("size", "1")).andReturn());
        long[] twelve = measure(() -> mvc.perform(get("/api/v1/messages").header("Authorization", auth)
                .param("q", prefix).param("size", "12")).andReturn());
        assertThat(twelve[0]).isEqualTo(one[0]).isLessThanOrEqualTo(2);
        assertThat(twelve[1]).isEqualTo(12);
    }

    @Test
    void messageDetailAndHistoryAreBoundedQueries() throws Exception {
        String auth = sender(RETAIL);
        String id = submitOk(auth, tradeConfirmation("rcp-001"));
        long[] detail = measure(() -> mvc.perform(get("/api/v1/messages/" + id).header("Authorization", auth)).andReturn());
        long[] history = measure(() -> mvc.perform(get("/api/v1/messages/" + id + "/history").header("Authorization", auth)).andReturn());
        long[] statusOnly = measure(() -> mvc.perform(get("/api/v1/messages/" + id + "/status").header("Authorization", auth)).andReturn());
        assertThat(detail[0]).as("message, recipient, attempts, events").isLessThanOrEqualTo(4);
        assertThat(history[0]).as("message, audit, attempts, events").isLessThanOrEqualTo(4);
        assertThat(statusOnly[0]).as("projection: one narrow select").isEqualTo(1);
        assertThat(statusOnly[1]).as("the status view loads no entity").isZero();
    }

    @Test
    void recipientsPreferencesAndAuditQueriesAreBounded() throws Exception {
        String op = operator(RETAIL);
        long[] one = measure(() -> mvc.perform(get("/api/v1/recipients").header("Authorization", op).param("size", "1")).andReturn());
        long[] forty = measure(() -> mvc.perform(get("/api/v1/recipients").header("Authorization", op).param("size", "40")).andReturn());
        assertThat(forty[0]).isEqualTo(one[0]).isLessThanOrEqualTo(2);
        long[] prefs = measure(() -> mvc.perform(get("/api/v1/recipients/rcp-003/preferences").header("Authorization", op)).andReturn());
        assertThat(prefs[0]).as("recipient, preference row, one existence check per channel").isLessThanOrEqualTo(5);
        String admin = admin(RETAIL);
        long[] audit1 = measure(() -> mvc.perform(get("/api/v1/audit-events").header("Authorization", admin).param("size", "1")).andReturn());
        long[] audit50 = measure(() -> mvc.perform(get("/api/v1/audit-events").header("Authorization", admin).param("size", "50")).andReturn());
        assertThat(audit50[0]).isLessThanOrEqualTo(audit1[0]).isLessThanOrEqualTo(2);
        long[] dlq = measure(() -> mvc.perform(get("/api/v1/dead-letters").header("Authorization", op).param("size", "50")).andReturn());
        assertThat(dlq[0]).isLessThanOrEqualTo(2);
        long[] events = measure(() -> mvc.perform(get("/api/v1/delivery-events").header("Authorization", op).param("size", "50")).andReturn());
        assertThat(events[0]).isLessThanOrEqualTo(2);
    }

    @Test
    void submissionUsesABoundedNumberOfStatements() throws Exception {
        String auth = sender(RETAIL);
        stats.clear();
        MvcResult r = mvc.perform(submit(auth, newKey(), tradeConfirmation("rcp-001"))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        // idempotency claim + lookup, recipient, template (+versions), preferences, suppression check, message,
        // outbox, audit, idempotency completion: bounded and independent of any collection size
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(24);
        List<Long> second = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            stats.clear();
            mvc.perform(submit(auth, newKey(), tradeConfirmation("rcp-001"))).andReturn();
            second.add(stats.getPrepareStatementCount());
        }
        assertThat(second).as("statement count is stable across submissions").allMatch(c -> c.equals(second.get(0)));
        JsonNode ignored = json(r);
        assertThat(ignored.get("id").asText()).isNotBlank();
    }
}
