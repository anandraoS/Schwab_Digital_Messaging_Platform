package com.example.messaging.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.messaging.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Executes {@code EXPLAIN ANALYZE} for the hot queries of the platform on the H2 database (PostgreSQL mode) loaded with
 * a synthetic data set of 100,000 messages, and checks that every query is served by an index (no full table scan) and
 * scans only a small part of the table. The plans are written to {@code target/query-plans-executed.md}
 * (copied to docs/database/query-plans-executed.md by hand).
 *
 * <p>These are <b>H2 plans</b>: they prove that the portable DDL provides a usable access path for each query, they say
 * nothing about PostgreSQL's planner choices or timings (run {@code EXPLAIN (ANALYZE, BUFFERS)} on a real PostgreSQL
 * 16 for that). The SQL below is the equivalent of what the Spring Data repositories / Specifications generate
 * (aliases and column lists simplified; literal values instead of bind parameters).
 */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class QueryPlanIT extends AbstractIntegrationTest {

    private static final int MESSAGES = 100_000;

    /** mayScan: the query may read many index rows on H2 for a documented reason (see note); full table scans are never allowed. */
    private record Q(String name, String where, String sql, boolean mayScan, String note) {}

    @Test
    void hotQueriesUseIndexesOnALargeDataSet() throws Exception {
        long t0 = System.nanoTime();
        load();
        jdbc.execute("ANALYZE");
        double loadSeconds = (System.nanoTime() - t0) / 1e9;

        String retail = "tenant-retail";
        String someMessage = jdbc.queryForObject("select id from message_request where status = 'DELIVERED' and tenant_id = 'tenant-retail' "
                + "order by id fetch first 1 rows only", String.class);
        String someEvent = jdbc.queryForObject("select provider_event_id from delivery_event order by id fetch first 1 rows only", String.class);
        String someProviderMsg = jdbc.queryForObject("select provider_message_id from message_attempt order by id fetch first 1 rows only", String.class);

        List<Q> queries = List.of(
                new Q("Message list: tenant + status filter, newest first (page 1)", "NotificationService.list / Specs.eq(tenantId,status)",
                        "select * from message_request where tenant_id = '" + retail + "' and status = 'SENT_TO_PROVIDER' "
                                + "order by created_at desc, id asc fetch first 20 rows only", true, "idx_msg_tenant_status_created; H2 limitation: ORDER BY created_at DESC, id cannot use an index whose leading columns are equality-filtered, so H2 reads the whole tenant/status partition and sorts"),
                new Q("Message list: tenant + status + channel + created_at range", "NotificationService.list",
                        "select * from message_request where tenant_id = '" + retail + "' and status = 'DELIVERED' and channel = 'SMS' "
                                + "and created_at >= timestamp '2026-10-01 00:00:00' order by created_at desc, id asc fetch first 20 rows only", true,
                        "range on created_at inside the (tenant, status, created_at) index; channel is filtered after the index"),
                new Q("Message list: tenant only (no filter), newest first - the default console view", "NotificationService.list",
                        "select * from message_request where tenant_id = '" + retail + "' order by created_at desc, id asc fetch first 20 rows only", true,
                        "(tenant, status, created_at) index, then a top-N sort; see the H2 limitation note"),
                new Q("Message list: platform admin (all tenants), newest first", "NotificationService.list (tenant = null)",
                        "select * from message_request order by created_at desc, id asc fetch first 20 rows only", false, "idx_msg_created"),
                new Q("Message list: by correlation id", "MessageRequestRepository (correlationId filter)",
                        "select * from message_request where tenant_id = '" + retail + "' and correlation_id = 'corr-5' fetch first 20 rows only", false, "idx_msg_correlation"),
                new Q("Outbox poll: due NEW rows, oldest first (OutboxPublisher, every 200 ms)", "OutboxEventRepository.findByStatusAndAvailableAtLessThanEqualOrderByCreatedAtAsc",
                        "select * from outbox_event where status = 'NEW' and available_at <= timestamp '2026-10-18 12:00:00' "
                                + "order by created_at asc fetch first 100 rows only", false, "idx_outbox_status_available"),
                new Q("Due-retry poll (ScheduledDispatchPoller.pollRetries)", "MessageRequestRepository.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc",
                        "select * from message_request where status = 'RETRY_PENDING' and next_attempt_at <= timestamp '2026-10-18 12:00:00' "
                                + "order by next_attempt_at asc fetch first 100 rows only", false, "idx_msg_status_next_attempt"),
                new Q("Stale QUEUED poll (ScheduledDispatchPoller.pollStaleQueued)", "MessageRequestRepository.findByStatusAndStatusChangedAtBefore",
                        "select * from message_request where status = 'QUEUED' and status_changed_at < timestamp '2026-10-18 12:00:00' "
                                + "fetch first 100 rows only", false, "idx_msg_status_changed"),
                new Q("Delivery-event dedupe lookup (callback processing)", "DeliveryEventRepository.findByProviderIdAndProviderEventId",
                        "select * from delivery_event where provider_id = 'simulated-email' and provider_event_id = '" + someEvent + "'", false, "uq_delivery_event"),
                new Q("Delivery events of one message (timeline)", "DeliveryEventRepository.findByMessageIdOrderByOccurredAtAsc",
                        "select * from delivery_event where message_id = '" + someMessage + "' order by occurred_at asc", false, "idx_event_message"),
                new Q("Audit events of one entity (history, ordered)", "AuditEventRepository.findByEntityTypeAndEntityIdOrderByOccurredAtAsc",
                        "select * from audit_event where entity_type = 'MESSAGE' and entity_id = '" + someMessage + "' order by occurred_at asc", false, "idx_audit_entity"),
                new Q("Audit search: tenant + time window", "AuditService.search",
                        "select * from audit_event where tenant_id = '" + retail + "' and occurred_at >= timestamp '2026-10-17 00:00:00' "
                                + "order by occurred_at desc, id asc fetch first 50 rows only", true, "idx_audit_tenant_time; same H2 sort limitation as the message list (tie-breaker column id)"),
                new Q("Callback lookup: provider message id -> attempt", "MessageAttemptRepository.findFirstByProviderIdAndProviderMessageId",
                        "select * from message_attempt where provider_id = 'simulated-email' and provider_message_id = '" + someProviderMsg + "'", false, "idx_attempt_provider_msg"),
                new Q("Idempotency lookup (tenant, key)", "IdempotencyRecordRepository.findByTenantIdAndIdemKey",
                        "select * from idempotency_record where tenant_id = '" + retail + "' and idem_key = 'idem-777'", false, "uq_msg_idempotency"),
                new Q("Consumer dedupe (processed_message by dispatch id)", "ProcessedMessageRepository.existsByDispatchId",
                        "select 1 from processed_message where dispatch_id = '" + someMessage + ":1'", false, "uq_processed_dispatch"),
                new Q("Dashboard: counts by status over the last 24 h (all tenants)", "MessageRequestRepository.countByStatusSince",
                        "select status, count(*) from message_request where created_at >= timestamp '2026-10-17 12:00:00' group by status", true,
                        "aggregate over a time range: reads every row of the window by design (idx_msg_created); cost grows with the window, not the table"),
                new Q("Retention scan: oldest terminal messages (RetentionJob)", "RetentionJob.purgeMessages",
                        "select id from message_request m where m.created_at < timestamp '2026-09-20 00:00:00' and m.updated_at < timestamp '2026-09-20 00:00:00' "
                                + "and m.status in ('DELIVERED','REJECTED','CANCELLED','FAILED') order by m.created_at fetch first 500 rows only", true, "H2 picks the status index and sorts (nightly batch job); PostgreSQL planner expected to differ - not verified"));

        StringBuilder md = new StringBuilder();
        md.append("# Query plans executed on H2 (PostgreSQL mode)\n\n");
        md.append("**These are H2 `EXPLAIN ANALYZE` plans, not PostgreSQL plans.** ").append("They were produced by `QueryPlanIT` (`bash scripts/it.sh QueryPlanIT` or `bash scripts/mvn.sh verify`) ")
                .append("against the Flyway-built schema with ").append(MESSAGES).append(" synthetic messages, ")
                .append(count("delivery_event")).append(" delivery events, ").append(count("audit_event")).append(" audit events, ")
                .append(count("outbox_event")).append(" outbox rows and ").append(count("message_attempt")).append(" attempts (generated in ")
                .append(String.format(Locale.ROOT, "%.1f", loadSeconds)).append(" s, then `ANALYZE`). ")
                .append("`scanCount` is the number of index/table rows H2 actually read. The SQL is the equivalent of the repository/Specification output ")
                .append("(literal values instead of bind parameters).\n\n");
        List<String> problems = new ArrayList<>();
        for (Q q : queries) {
            String plan = plan(q.sql());
            md.append("## ").append(q.name()).append("\n\n");
            md.append("- Code path: `").append(q.where()).append("`\n- Expected access path: ").append(q.note()).append("\n\n```sql\n").append(q.sql()).append("\n```\n\n```\n")
                    .append(plan).append("\n```\n\n");
            boolean scans = plan.contains("tableScan");
            long scanCount = scanCount(plan);
            md.append("Result: ").append(scans ? "**FULL TABLE SCAN**" : "index access").append(", scanCount=").append(scanCount).append("\n\n");
            if (scans) {
                problems.add(q.name() + ": full table scan");
            }
            if (!q.mayScan() && scanCount > 5_000) {
                problems.add(q.name() + ": scanCount " + scanCount);
            }
        }
        Path out = Path.of("target", "query-plans-executed.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString(), StandardCharsets.UTF_8);
        assertThat(problems).as("queries without an efficient access path (see %s)", out.toAbsolutePath()).isEmpty();
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("select count(*) from " + table, Long.class);
        return n == null ? 0 : n;
    }

    private String plan(String sql) {
        List<String> rows = jdbc.queryForList("EXPLAIN ANALYZE " + sql, String.class);
        return String.join("\n", rows);
    }

    private static long scanCount(String plan) {
        long total = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("scanCount: (\\d+)").matcher(plan);
        while (m.find()) {
            total = Math.max(total, Long.parseLong(m.group(1)));
        }
        return total;
    }

    /** Set-based data generation with SYSTEM_RANGE: fast and deterministic. */
    private void load() {
        // statuses by x % 100: 0-69 SENT_TO_PROVIDER, 70-95 DELIVERED, 96 QUEUED, 97 RETRY_PENDING, 98 FAILED, 99 REJECTED
        jdbc.update("insert into message_request (id, tenant_id, client_reference, recipient_id, channel, category, priority, status, template_code, "
                + "template_version_no, template_locale, payload_json, correlation_id, idempotency_key, attempt_count, max_attempts, next_attempt_at, "
                + "status_changed_at, created_at, updated_at, version, fallback_index, discarded) "
                + "select 'qp-' || x, case when mod(x, 5) = 0 then 'tenant-wealth' else 'tenant-retail' end, 'ref-' || x, "
                + "case when mod(x, 5) = 0 then 'rcp-041' else 'rcp-001' end, "
                + "case mod(x, 3) when 0 then 'EMAIL' when 1 then 'SMS' else 'PUSH' end, 'TRANSACTIONAL', 'NORMAL', "
                + "case when mod(x, 100) < 70 then 'SENT_TO_PROVIDER' when mod(x, 100) < 96 then 'DELIVERED' when mod(x, 100) = 96 then 'QUEUED' "
                + "when mod(x, 100) = 97 then 'RETRY_PENDING' when mod(x, 100) = 98 then 'FAILED' else 'REJECTED' end, "
                + "'TRADE_CONFIRMATION', 1, 'en', '{}', 'corr-' || x, 'idem-' || x, 1, 5, "
                + "case when mod(x, 100) = 97 then dateadd('SECOND', -mod(x, 600), timestamp '2026-10-18 11:00:00') else null end, "
                + "dateadd('SECOND', -x, timestamp '2026-10-18 11:00:00'), dateadd('SECOND', -x * 25, timestamp '2026-10-18 11:00:00'), "
                + "dateadd('SECOND', -x * 25, timestamp '2026-10-18 11:00:00'), 0, 0, false from system_range(1, " + MESSAGES + ") r(x)");
        jdbc.update("insert into message_attempt (id, message_id, attempt_no, channel, provider_id, state, outcome, provider_message_id, started_at) "
                + "select 'qa-' || x, 'qp-' || x, 1, 'EMAIL', 'simulated-email', 'FINISHED', 'ACCEPTED', 'em-' || x, dateadd('SECOND', -x, timestamp '2026-10-18 11:00:00') "
                + "from system_range(1, " + MESSAGES + ") r(x)");
        jdbc.update("insert into delivery_event (id, message_id, provider_id, provider_event_id, type, occurred_at, received_at, applied) "
                + "select 'qe-' || x, 'qp-' || x, 'simulated-email', 'pe-' || x, 'DELIVERED', dateadd('SECOND', -x, timestamp '2026-10-18 11:00:00'), "
                + "dateadd('SECOND', -x, timestamp '2026-10-18 11:00:00'), true from system_range(1, " + MESSAGES + ") r(x)");
        jdbc.update("insert into audit_event (id, tenant_id, actor, action, entity_type, entity_id, occurred_at) "
                + "select 'qu-' || x, case when mod(x, 5) = 0 then 'tenant-wealth' else 'tenant-retail' end, 'dispatch-consumer', 'MESSAGE_TRANSITION', 'MESSAGE', "
                + "'qp-' || (1 + mod(x, " + MESSAGES + ")), dateadd('SECOND', -x, timestamp '2026-10-18 11:00:00') from system_range(1, " + (2 * MESSAGES) + ") r(x)");
        jdbc.update("insert into outbox_event (id, aggregate_id, topic, payload, status, available_at, created_at, sent_at) "
                + "select 'qo-' || x, 'qp-' || x, 'dispatch', '{}', case when mod(x, 100) = 0 then 'NEW' else 'SENT' end, "
                + "dateadd('SECOND', -x, timestamp '2026-10-18 11:00:00'), dateadd('SECOND', -x, timestamp '2026-10-18 11:00:00'), "
                + "case when mod(x, 100) = 0 then null else dateadd('SECOND', -x, timestamp '2026-10-18 11:00:00') end from system_range(1, " + MESSAGES + ") r(x)");
        jdbc.update("insert into processed_message (id, dispatch_id, message_id, processed_at) select 'qd-' || x, 'qp-' || x || ':1', 'qp-' || x, "
                + "dateadd('SECOND', -x, timestamp '2026-10-18 11:00:00') from system_range(1, " + MESSAGES + ") r(x)");
        jdbc.update("insert into idempotency_record (id, tenant_id, idem_key, request_hash, status, created_at, expires_at) "
                + "select 'qi-' || x, case when mod(x, 5) = 0 then 'tenant-wealth' else 'tenant-retail' end, 'idem-' || x, 'h', 'COMPLETED', "
                + "dateadd('SECOND', -x, timestamp '2026-10-18 11:00:00'), timestamp '2026-10-19 11:00:00' from system_range(1, " + MESSAGES + ") r(x)");
    }
}
