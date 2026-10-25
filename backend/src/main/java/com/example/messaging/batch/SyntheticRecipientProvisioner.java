package com.example.messaging.batch;

import com.example.messaging.common.Ids;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates fictitious recipients {@code SYN-0000001 .. SYN-nnnnnnn} (plus an all-channels-enabled preference row) for a
 * tenant, so that a synthetic bulk file has real targets. Idempotent and contiguous: it only appends the missing
 * tail. Addresses are reserved {@code example.test} names and {@code +1555} numbers; nothing here is real data.
 */
@Component
public class SyntheticRecipientProvisioner {

    public static final String PREFIX = "SYN-";
    private static final int BATCH = 1000;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate tx;

    public SyntheticRecipientProvisioner(JdbcTemplate jdbc, Clock clock, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.tx = new TransactionTemplate(tm);
    }

    public static String externalRef(long index) {
        return String.format("SYN-%07d", index);
    }

    /** Ensures recipients 1..count exist for the tenant; returns how many were created. */
    public long ensure(String tenantId, long count) {
        String max = jdbc.queryForObject(
                "select max(external_ref) from recipient where tenant_id = ? and external_ref like 'SYN-0%'",
                String.class, tenantId);
        long have = max == null ? 0 : Long.parseLong(max.substring(PREFIX.length()));
        long created = 0;
        String tenantTag = Ids.sha256Hex(tenantId).substring(0, 8);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (long from = have + 1; from <= count; from += BATCH) {
            long to = Math.min(count, from + BATCH - 1);
            List<Object[]> recipients = new ArrayList<>();
            List<Object[]> prefs = new ArrayList<>();
            for (long n = from; n <= to; n++) {
                String id = "syn-" + String.format("%07d", n) + "-" + tenantTag;
                recipients.add(new Object[] {id, tenantId, externalRef(n), "Synthetic " + n,
                        String.format("syn%07d@example.test", n), String.format("+1555%07d", n),
                        "push-token-synthetic-" + n, "en", "UTC", false, now, now, 0L});
                prefs.add(new Object[] {id, true, true, true, true, now, now, 0L});
            }
            tx.executeWithoutResult(s -> {
                jdbc.batchUpdate("insert into recipient (id, tenant_id, external_ref, display_name, email, phone_e164, "
                        + "push_token, locale, time_zone, deleted, created_at, updated_at, version) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", recipients);
                jdbc.batchUpdate("insert into recipient_preference (recipient_id, marketing_opt_in, email_enabled, "
                        + "sms_enabled, push_enabled, created_at, updated_at, version) values (?, ?, ?, ?, ?, ?, ?, ?)",
                        prefs);
            });
            created += to - from + 1;
        }
        return created;
    }
}
