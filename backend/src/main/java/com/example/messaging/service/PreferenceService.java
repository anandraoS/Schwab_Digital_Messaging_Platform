package com.example.messaging.service;

import com.example.messaging.common.ApiError;
import com.example.messaging.common.ApiException;
import com.example.messaging.common.ErrorCode;
import com.example.messaging.common.Ids;
import com.example.messaging.common.Redaction;
import com.example.messaging.domain.AuditAction;
import com.example.messaging.domain.Channel;
import com.example.messaging.domain.Recipient;
import com.example.messaging.domain.RecipientPreference;
import com.example.messaging.domain.Suppression;
import com.example.messaging.repository.RecipientPreferenceRepository;
import com.example.messaging.repository.RecipientRepository;
import com.example.messaging.repository.Specs;
import com.example.messaging.repository.SuppressionRepository;
import com.example.messaging.security.AuthorizationService;
import com.example.messaging.security.TenantContext;
import com.example.messaging.web.dto.RecipientDtos.PreferencesResponse;
import com.example.messaging.web.dto.RecipientDtos.RecipientSummary;
import com.example.messaging.web.dto.RecipientDtos.SuppressionRequest;
import com.example.messaging.web.dto.RecipientDtos.SuppressionResponse;
import com.example.messaging.web.dto.RecipientDtos.UpdatePreferencesRequest;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Recipients, consent preferences (optimistic locking) and the suppression list. */
@Service
public class PreferenceService {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final RecipientRepository recipients;
    private final RecipientPreferenceRepository preferences;
    private final SuppressionRepository suppressions;
    private final AuditService audit;
    private final AuthorizationService authz;
    private final TenantContext tenantContext;
    private final Clock clock;

    public PreferenceService(RecipientRepository recipients, RecipientPreferenceRepository preferences,
            SuppressionRepository suppressions, AuditService audit, AuthorizationService authz,
            TenantContext tenantContext, Clock clock) {
        this.recipients = recipients;
        this.preferences = preferences;
        this.suppressions = suppressions;
        this.audit = audit;
        this.authz = authz;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<RecipientSummary> search(String q, Pageable pageable) {
        Specification<Recipient> spec = Specification.<Recipient>where(Specs.eq("tenantId", tenantContext.scopeTenant()))
                .and(Specs.containsAny(q, "externalRef", "displayName", "id"));
        return recipients.findAll(spec, pageable).map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public Recipient requireRecipient(String recipientId) {
        Recipient r = recipients.findById(recipientId).orElseThrow(() -> new ApiException.NotFoundException("Recipient"));
        authz.assertTenantAccess(r.getTenantId(), "Recipient");
        return r;
    }

    @Transactional(readOnly = true)
    public PreferencesResponse get(String recipientId) {
        Recipient r = requireRecipient(recipientId);
        return toResponse(r, preferences.findById(recipientId).orElse(null));
    }

    @Transactional
    public PreferencesResponse update(String recipientId, UpdatePreferencesRequest req) {
        Recipient r = requireRecipient(recipientId);
        LocalTime start = parseTime(req.quietStart(), "quietStart");
        LocalTime end = parseTime(req.quietEnd(), "quietEnd");
        if ((start == null) != (end == null)) {
            throw new ApiException.ValidationException("quietStart", "quietStart and quietEnd must be provided together");
        }
        String fallback = fallbackCsv(req.fallbackOrder());
        if (req.timeZone() != null && !req.timeZone().isBlank()) {
            try {
                ZoneId.of(req.timeZone());
            } catch (DateTimeException e) {
                throw new ApiException.ValidationException("timeZone", "Unknown time zone");
            }
        }
        Instant now = clock.instant();
        RecipientPreference p = preferences.findById(recipientId).orElse(null);
        if (p == null) {
            if (req.version() != null) {
                throw optimistic();
            }
            p = new RecipientPreference();
            p.setRecipientId(recipientId);
            p.setCreatedAt(now);
        } else if (req.version() == null || !req.version().equals(p.getVersion())) {
            // stale or missing token: the client must re-read before writing
            throw optimistic();
        }
        p.setMarketingOptIn(req.marketingOptIn());
        p.setEmailEnabled(req.emailEnabled());
        p.setSmsEnabled(req.smsEnabled());
        p.setPushEnabled(req.pushEnabled());
        p.setQuietStart(start);
        p.setQuietEnd(end);
        p.setFallbackOrder(fallback);
        p.setUpdatedAt(now);
        RecipientPreference saved = preferences.saveAndFlush(p); // flush: a concurrent writer fails here with 409
        if (req.timeZone() != null && !req.timeZone().isBlank() && !req.timeZone().equals(r.getTimeZone())) {
            r.setTimeZone(req.timeZone());
            r.setUpdatedAt(now);
            recipients.save(r);
        }
        audit.record(r.getTenantId(), AuditAction.PREFERENCE_UPDATED, "RECIPIENT", recipientId, Map.of(
                "marketingOptIn", saved.isMarketingOptIn(),
                "emailEnabled", saved.isEmailEnabled(),
                "smsEnabled", saved.isSmsEnabled(),
                "pushEnabled", saved.isPushEnabled(),
                "version", saved.getVersion() == null ? Long.valueOf(-1L) : saved.getVersion()));
        return toResponse(r, saved);
    }

    @Transactional
    public SuppressionResponse addSuppression(String recipientId, SuppressionRequest req) {
        Recipient r = requireRecipient(recipientId);
        String address = r.addressFor(req.channel());
        if (address == null || address.isBlank()) {
            throw new ApiException.ValidationException("channel", "Recipient has no address for this channel");
        }
        String hash = Ids.addressHash(address);
        Suppression existing = suppressions
                .findByTenantIdAndChannelAndAddressHash(r.getTenantId(), req.channel(), hash).orElse(null);
        if (existing != null) {
            return new SuppressionResponse(recipientId, existing.getChannel(), existing.getReason(), existing.getCreatedAt());
        }
        Suppression s = new Suppression();
        s.setId(Ids.newId());
        s.setTenantId(r.getTenantId());
        s.setChannel(req.channel());
        s.setAddressHash(hash);
        s.setReason(req.reason() == null || req.reason().isBlank() ? "OPERATOR" : req.reason().trim());
        s.setCreatedAt(clock.instant());
        suppressions.save(s);
        audit.record(r.getTenantId(), AuditAction.SUPPRESSION_ADDED, "RECIPIENT", recipientId,
                Map.of("channel", req.channel().name(), "reason", s.getReason()));
        return new SuppressionResponse(recipientId, s.getChannel(), s.getReason(), s.getCreatedAt());
    }

    @Transactional
    public void removeSuppression(String recipientId, Channel channel) {
        Recipient r = requireRecipient(recipientId);
        String address = r.addressFor(channel);
        Suppression s = address == null ? null : suppressions
                .findByTenantIdAndChannelAndAddressHash(r.getTenantId(), channel, Ids.addressHash(address)).orElse(null);
        if (s == null) {
            throw new ApiException.NotFoundException("Suppression");
        }
        suppressions.delete(s);
        audit.record(r.getTenantId(), AuditAction.SUPPRESSION_REMOVED, "RECIPIENT", recipientId,
                Map.of("channel", channel.name()));
    }

    // ------------------------------------------------------------------ mapping

    private RecipientSummary toSummary(Recipient r) {
        return new RecipientSummary(r.getId(), r.getTenantId(), r.getExternalRef(), r.getDisplayName(),
                Redaction.maskEmail(r.getEmail()), Redaction.maskPhone(r.getPhoneE164()), r.getLocale(),
                r.getTimeZone(), r.isDeleted());
    }

    private PreferencesResponse toResponse(Recipient r, RecipientPreference p) {
        List<Channel> suppressed = new ArrayList<>();
        for (Channel c : Channel.values()) {
            String a = r.addressFor(c);
            if (a != null && !a.isBlank() && suppressions.existsByTenantIdAndChannelAndAddressHash(
                    r.getTenantId(), c, Ids.addressHash(a))) {
                suppressed.add(c);
            }
        }
        if (p == null) {
            return new PreferencesResponse(r.getId(), false, true, true, true,
                    PolicyService.DEFAULT_QUIET_START.format(HHMM), PolicyService.DEFAULT_QUIET_END.format(HHMM),
                    r.getTimeZone(), List.of(), suppressed, null, null);
        }
        return new PreferencesResponse(r.getId(), p.isMarketingOptIn(), p.isEmailEnabled(), p.isSmsEnabled(),
                p.isPushEnabled(),
                (p.getQuietStart() == null ? PolicyService.DEFAULT_QUIET_START : p.getQuietStart()).format(HHMM),
                (p.getQuietEnd() == null ? PolicyService.DEFAULT_QUIET_END : p.getQuietEnd()).format(HHMM),
                r.getTimeZone(), parseFallback(p.getFallbackOrder()), suppressed, p.getVersion(), p.getUpdatedAt());
    }

    private static List<Channel> parseFallback(String csv) {
        List<Channel> out = new ArrayList<>();
        if (csv != null && !csv.isBlank()) {
            for (String s : csv.split(",")) {
                try {
                    out.add(Channel.valueOf(s.trim()));
                } catch (IllegalArgumentException ignored) {
                    // corrupt stored value: skip rather than fail the read
                }
            }
        }
        return out;
    }

    private static String fallbackCsv(List<Channel> order) {
        if (order == null || order.isEmpty()) {
            return null;
        }
        if (new LinkedHashSet<>(order).size() != order.size()) {
            throw new ApiException.ValidationException("fallbackOrder", "fallbackOrder must not contain duplicates");
        }
        return String.join(",", order.stream().map(Enum::name).toList());
    }

    private static LocalTime parseTime(String s, String field) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(s, HHMM);
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                    List.of(new ApiError.Detail(field, "must be HH:mm (24h)")));
        }
    }

    private static ApiException optimistic() {
        return new ApiException.ConflictException(ErrorCode.CONFLICT_OPTIMISTIC_LOCK,
                "Preferences were modified by someone else; reload and retry");
    }
}
