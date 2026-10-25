package com.example.messaging.service;

import com.example.messaging.common.Ids;
import com.example.messaging.domain.AuditAction;
import com.example.messaging.domain.Channel;
import com.example.messaging.domain.Suppression;
import com.example.messaging.repository.SuppressionRepository;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suppression entries created by the platform itself (hard bounces, invalid push tokens). Unlike
 * {@code PreferenceService} this carries no caller authorisation: it is only used by the dispatch consumer, the
 * callback processor and reconciliation. Joins the caller's transaction.
 */
@Service
public class SystemSuppressionService {

    private final SuppressionRepository suppressions;
    private final AuditService audit;
    private final Clock clock;

    public SystemSuppressionService(SuppressionRepository suppressions, AuditService audit, Clock clock) {
        this.suppressions = suppressions;
        this.audit = audit;
        this.clock = clock;
    }

    /** Idempotent: returns false when the address is already suppressed for the channel. */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean suppress(String tenantId, Channel channel, String address, String reason, String actor,
            String messageId) {
        if (address == null || address.isBlank()) {
            return false;
        }
        String hash = Ids.addressHash(address);
        if (suppressions.existsByTenantIdAndChannelAndAddressHash(tenantId, channel, hash)) {
            return false;
        }
        Suppression s = new Suppression();
        s.setId(Ids.newId());
        s.setTenantId(tenantId);
        s.setChannel(channel);
        s.setAddressHash(hash);
        s.setReason(reason);
        s.setCreatedAt(clock.instant());
        suppressions.save(s);
        // the address itself is never audited, only its channel and the triggering message
        audit.recordAs(actor, "SYSTEM", tenantId, AuditAction.SUPPRESSION_ADDED, "SUPPRESSION", s.getId(),
                Map.of("channel", channel.name(), "reason", reason, "messageId", messageId == null ? "" : messageId));
        return true;
    }
}
