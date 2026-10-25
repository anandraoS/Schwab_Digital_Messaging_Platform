package com.example.messaging.batch;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A throw-away H2 database (PostgreSQL mode, as in the {@code test} profile) with the real Flyway migrations applied,
 * for tests that exercise SQL directly without starting the Spring application.
 */
public final class H2Support {

    public final DataSource dataSource;
    public final JdbcTemplate jdbc;

    public H2Support() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:unit-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        this.dataSource = ds;
        this.jdbc = new JdbcTemplate(ds);
    }

    public static LocalDateTime utc(java.time.Instant i) {
        return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    public void tenant(String id) {
        LocalDateTime now = utc(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        jdbc.update("insert into tenant (id, name, status, created_at, updated_at) values (?, ?, 'ACTIVE', ?, ?)",
                id, id, now, now);
        jdbc.update("insert into recipient (id, tenant_id, external_ref, locale, time_zone, deleted, created_at, "
                + "updated_at, version) values (?, ?, 'R1', 'en', 'UTC', false, ?, ?, 0)", "rcp-" + id, id, now, now);
    }

    /** Inserts a minimal valid message row. */
    public void message(String id, String tenant, String status, LocalDateTime created, boolean discarded,
            String bulkJobId) {
        jdbc.update("insert into message_request (id, tenant_id, recipient_id, channel, category, priority, status, "
                + "template_code, template_version_no, template_locale, payload_json, correlation_id, idempotency_key, "
                + "discarded, bulk_job_id, status_changed_at, created_at, updated_at, version) "
                + "values (?, ?, ?, 'EMAIL', 'TRANSACTIONAL', 'NORMAL', ?, 'T', 1, 'en', '{}', 'c', ?, ?, ?, ?, ?, ?, 0)",
                id, tenant, "rcp-" + tenant, status, "k-" + id, discarded, bulkJobId, created, created, created);
    }

    public void attempt(String messageId, LocalDateTime at) {
        jdbc.update("insert into message_attempt (id, message_id, attempt_no, channel, provider_id, state, started_at) "
                + "values (?, ?, 1, 'EMAIL', 'p', 'FINISHED', ?)", "att-" + messageId, messageId, at);
    }

    public void event(String id, String messageId, LocalDateTime received) {
        jdbc.update("insert into delivery_event (id, message_id, provider_id, provider_event_id, type, occurred_at, "
                + "received_at, applied) values (?, ?, 'p', ?, 'DELIVERED', ?, ?, true)", id, messageId, "pe-" + id,
                received, received);
    }

    public void outbox(String id, String aggregate, String status, LocalDateTime created, LocalDateTime sentAt) {
        jdbc.update("insert into outbox_event (id, aggregate_id, topic, payload, status, available_at, created_at, "
                + "sent_at) values (?, ?, 'dispatch', '{}', ?, ?, ?, ?)", id, aggregate, status, created, created, sentAt);
    }

    public void audit(String id, String tenant, String entityType, String entityId, LocalDateTime at) {
        jdbc.update("insert into audit_event (id, tenant_id, actor, action, entity_type, entity_id, occurred_at) "
                + "values (?, ?, 'a', 'X', ?, ?, ?)", id, tenant, entityType, entityId, at);
    }

    public long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }
}
