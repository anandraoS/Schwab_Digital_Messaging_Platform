package com.example.messaging.service;

import com.example.messaging.domain.AuditAction;
import com.example.messaging.domain.MessageStatus;
import com.example.messaging.domain.Priority;
import com.example.messaging.messaging.MessageBus;
import com.example.messaging.messaging.QueueStats;
import com.example.messaging.metrics.MessagingMetrics;
import com.example.messaging.repository.ChannelCount;
import com.example.messaging.repository.MessageRequestRepository;
import com.example.messaging.repository.StatusCount;
import com.example.messaging.security.TenantContext;
import com.example.messaging.web.dto.OpsDtos.ConsumerState;
import com.example.messaging.web.dto.OpsDtos.OpsSummary;
import com.example.messaging.web.dto.OpsDtos.QueueStatsView;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Queue statistics, dashboard aggregates and consumer pause/resume (operations API). */
@Service
public class OpsService {

    public static final int WINDOW_HOURS = 24;

    private final MessageBus bus;
    private final MessageRequestRepository messages;
    private final MessagingMetrics metrics;
    private final TenantContext tenantContext;
    private final AuditService audit;
    private final Clock clock;

    public OpsService(MessageBus bus, MessageRequestRepository messages, MessagingMetrics metrics,
            TenantContext tenantContext, AuditService audit, Clock clock) {
        this.bus = bus;
        this.messages = messages;
        this.metrics = metrics;
        this.tenantContext = tenantContext;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public QueueStatsView queues() {
        QueueStats s = bus.stats();
        Map<String, Long> depth = new LinkedHashMap<>();
        for (Priority p : Priority.values()) {
            depth.put(p.name(), s.depthByPriority().getOrDefault(p, 0L));
        }
        Long ageMs = s.oldestAgeMillis();
        return new QueueStatsView(depth, s.inFlight(), s.consumers(), s.dlqDepth(),
                messages.countByStatus(MessageStatus.RETRY_PENDING), s.delayed(), ageMs,
                ageMs == null ? null : ageMs / 1000, s.paused(), s.paused());
    }

    @Transactional(readOnly = true)
    public OpsSummary summary() {
        Instant since = clock.instant().minus(Duration.ofHours(WINDOW_HOURS));
        String tenant = tenantContext.scopeTenant();
        List<StatusCount> statuses = tenant == null ? messages.countByStatusSince(since)
                : messages.countByStatusSinceForTenant(tenant, since);
        List<ChannelCount> channels = tenant == null ? messages.countByChannelSince(since)
                : messages.countByChannelSinceForTenant(tenant, since);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (MessageStatus st : MessageStatus.values()) {
            byStatus.put(st.name(), 0L);
        }
        long total = 0;
        for (StatusCount c : statuses) {
            byStatus.put(c.getStatus().name(), c.getCount());
            total += c.getCount();
        }
        Map<String, Long> byChannel = new LinkedHashMap<>();
        channels.forEach(c -> byChannel.put(c.getChannel().name(), c.getCount()));
        // success = provider accepted or delivered; failure = failed, dead-lettered or unresolved; policy rejections and
        // cancellations are neither. No finished messages yet means nothing failed: 1.0.
        long ok = byStatus.get("DELIVERED") + byStatus.get("SENT_TO_PROVIDER");
        long bad = byStatus.get("FAILED") + byStatus.get("DEAD_LETTERED") + byStatus.get("DELIVERY_UNKNOWN");
        double rate = ok + bad == 0 ? 1.0 : (double) ok / (ok + bad);
        Timer delay = metrics.registry().find(MessagingMetrics.DISPATCH_DELAY).timer();
        long p95 = delay == null ? 0 : com.example.messaging.provider.ProviderAdminService.percentileMs(delay, 0.95);
        return new OpsSummary(byStatus, byChannel, total, rate, p95, WINDOW_HOURS);
    }

    public ConsumerState pause() {
        bus.pause();
        audit.record(null, AuditAction.CONSUMERS_PAUSED, "CONSUMERS", "dispatch", Map.of("paused", true));
        return new ConsumerState(bus.isPaused());
    }

    public ConsumerState resume() {
        bus.resume();
        audit.record(null, AuditAction.CONSUMERS_RESUMED, "CONSUMERS", "dispatch", Map.of("paused", false));
        return new ConsumerState(bus.isPaused());
    }
}
