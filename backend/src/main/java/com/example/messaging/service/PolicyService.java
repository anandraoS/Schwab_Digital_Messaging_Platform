package com.example.messaging.service;

import com.example.messaging.common.Ids;
import com.example.messaging.domain.Category;
import com.example.messaging.domain.Channel;
import com.example.messaging.domain.Priority;
import com.example.messaging.domain.Recipient;
import com.example.messaging.domain.RecipientPreference;
import com.example.messaging.repository.RecipientPreferenceRepository;
import com.example.messaging.repository.SuppressionRepository;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Delivery policy (CONTRACT 3.3): consent, suppression, category rules, quiet hours, fallback evaluation.
 * {@link #decide} is pure and independently testable; the instance methods only load the data it needs.
 */
@Service
public class PolicyService {

    public static final LocalTime DEFAULT_QUIET_START = LocalTime.of(21, 0);
    public static final LocalTime DEFAULT_QUIET_END = LocalTime.of(8, 0);

    /** One fallback channel together with its independent decision. */
    public record FallbackDecision(Channel channel, PolicyDecision decision) {}

    private final RecipientPreferenceRepository preferences;
    private final SuppressionRepository suppressions;

    public PolicyService(RecipientPreferenceRepository preferences, SuppressionRepository suppressions) {
        this.preferences = preferences;
        this.suppressions = suppressions;
    }

    /** Loads preferences/suppression for the recipient and applies {@link #decide}. */
    public PolicyDecision evaluate(Recipient recipient, Channel channel, Category category, Priority priority,
            Instant now) {
        RecipientPreference prefs = preferences.findById(recipient.getId()).orElse(null);
        return decide(recipient, prefs, isSuppressed(recipient, channel), channel, category, priority, now);
    }

    /**
     * Evaluates each fallback channel through the full policy independently: a fallback never bypasses consent,
     * suppression or quiet hours.
     */
    public List<FallbackDecision> evaluateFallbacks(Recipient recipient, List<Channel> fallbacks, Category category,
            Priority priority, Instant now) {
        RecipientPreference prefs = preferences.findById(recipient.getId()).orElse(null);
        List<FallbackDecision> out = new ArrayList<>();
        for (Channel c : fallbacks) {
            out.add(new FallbackDecision(c,
                    decide(recipient, prefs, isSuppressed(recipient, c), c, category, priority, now)));
        }
        return out;
    }

    private boolean isSuppressed(Recipient recipient, Channel channel) {
        String address = recipient.addressFor(channel);
        if (address == null || address.isBlank()) {
            return false;
        }
        return suppressions.existsByTenantIdAndChannelAndAddressHash(
                recipient.getTenantId(), channel, Ids.addressHash(address));
    }

    /** Pure policy decision. Order matters: deleted, address, suppression, category/consent, quiet hours. */
    public static PolicyDecision decide(Recipient recipient, RecipientPreference prefs, boolean suppressed,
            Channel channel, Category category, Priority priority, Instant now) {
        if (recipient == null || recipient.isDeleted()) {
            return PolicyDecision.reject(PolicyReason.RECIPIENT_DELETED);
        }
        String address = recipient.addressFor(channel);
        if (address == null || address.isBlank()) {
            return PolicyDecision.reject(PolicyReason.NO_ADDRESS);
        }
        if (suppressed) {
            return PolicyDecision.reject(PolicyReason.SUPPRESSED);
        }
        switch (category) {
            case MARKETING -> {
                if (prefs == null || !prefs.isMarketingOptIn() || !prefs.channelEnabled(channel)) {
                    return PolicyDecision.reject(PolicyReason.OPTED_OUT);
                }
            }
            case SERVICE -> {
                if (prefs != null && !prefs.channelEnabled(channel)) {
                    return PolicyDecision.reject(PolicyReason.CHANNEL_DISABLED);
                }
            }
            case TRANSACTIONAL, SECURITY_ALERT -> {
                // ignore marketing opt-out and per-channel enabled flags; only suppression (above) blocks
            }
            default -> throw new IllegalStateException("Unhandled category " + category); // fail closed
        }
        if (!respectsQuietHours(category, channel, priority)) {
            return PolicyDecision.allow();
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(java.util.Objects.requireNonNullElse(recipient.getTimeZone(), ""));
        } catch (DateTimeException e) {
            // fail closed for marketing, fail open for everything else (quiet hours are a courtesy there)
            return category.isMarketing() ? PolicyDecision.reject(PolicyReason.INVALID_PREFERENCES)
                    : PolicyDecision.allow();
        }
        LocalTime start = prefs != null && prefs.getQuietStart() != null ? prefs.getQuietStart() : DEFAULT_QUIET_START;
        LocalTime end = prefs != null && prefs.getQuietEnd() != null ? prefs.getQuietEnd() : DEFAULT_QUIET_END;
        Instant until = quietHoursEnd(now, zone, start, end);
        return until == null ? PolicyDecision.allow() : PolicyDecision.defer(until);
    }

    /** SECURITY_ALERT always sends; TRANSACTIONAL defers only LOW on SMS/PUSH; SERVICE defers LOW/NORMAL. */
    static boolean respectsQuietHours(Category category, Channel channel, Priority priority) {
        return switch (category) {
            case SECURITY_ALERT -> false;
            case TRANSACTIONAL -> (channel == Channel.SMS || channel == Channel.PUSH) && priority == Priority.LOW;
            case SERVICE -> priority == Priority.LOW || priority == Priority.NORMAL;
            case MARKETING -> true;
        };
    }

    /**
     * @return the instant quiet hours end if {@code now} is inside the window in {@code zone}, else {@code null}.
     *         start == end disables quiet hours. Windows may cross midnight. DST gaps resolve forward; the returned
     *         instant is always strictly after {@code now}.
     */
    public static Instant quietHoursEnd(Instant now, ZoneId zone, LocalTime start, LocalTime end) {
        if (start.equals(end)) {
            return null;
        }
        ZonedDateTime local = now.atZone(zone);
        LocalTime t = local.toLocalTime();
        boolean crossesMidnight = start.isAfter(end);
        boolean inQuiet = crossesMidnight ? (!t.isBefore(start) || t.isBefore(end)) : (!t.isBefore(start) && t.isBefore(end));
        if (!inQuiet) {
            return null;
        }
        var endDate = (crossesMidnight && !t.isBefore(start)) ? local.toLocalDate().plusDays(1) : local.toLocalDate();
        Instant until = ZonedDateTime.of(endDate, end, zone).toInstant();
        // DST fall-back corner: an ambiguous end time resolves to its first occurrence, which may already be past;
        // in that case the window has effectively ended.
        return until.isAfter(now) ? until : null;
    }
}
