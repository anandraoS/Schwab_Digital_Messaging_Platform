package com.example.messaging.provider;

import com.example.messaging.common.ApiException;
import com.example.messaging.domain.AuditAction;
import com.example.messaging.domain.ProviderConfig;
import com.example.messaging.metrics.MessagingMetrics;
import com.example.messaging.provider.simulator.FaultInjector;
import com.example.messaging.provider.simulator.SimulatedProviderBase;
import com.example.messaging.repository.ProviderConfigRepository;
import com.example.messaging.service.AuditService;
import com.example.messaging.web.dto.OpsDtos.ProviderConfigRequest;
import com.example.messaging.web.dto.OpsDtos.ProviderHealth;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provider administration and health: configuration changes (audited, effective immediately because the registry
 * cache is invalidated) and the health view assembled from the circuit breaker, the {@code messaging.provider.*}
 * meters and the fault injector.
 */
@Service
public class ProviderAdminService {

    private final ProviderConfigRepository configs;
    private final ProviderRegistry registry;
    private final ResilientProviderInvoker invoker;
    private final FaultInjector faults;
    private final MessagingMetrics metrics;
    private final AuditService audit;
    private final Clock clock;

    public ProviderAdminService(ProviderConfigRepository configs, ProviderRegistry registry,
            ResilientProviderInvoker invoker, FaultInjector faults, MessagingMetrics metrics, AuditService audit,
            Clock clock) {
        this.configs = configs;
        this.registry = registry;
        this.invoker = invoker;
        this.faults = faults;
        this.metrics = metrics;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ProviderHealth> list() {
        return configs.findAll().stream()
                .sorted((a, b) -> a.getChannel() != b.getChannel() ? a.getChannel().compareTo(b.getChannel())
                        : Integer.compare(a.getPriorityOrder(), b.getPriorityOrder()))
                .map(this::health).toList();
    }

    @Transactional(readOnly = true)
    public ProviderHealth get(String providerId) {
        return health(configs.findByProviderId(providerId).orElseThrow(() -> new ApiException.NotFoundException("Provider")));
    }

    @Transactional
    public ProviderHealth update(String providerId, ProviderConfigRequest req) {
        ProviderConfig c = configs.findByProviderId(providerId).orElseThrow(() -> new ApiException.NotFoundException("Provider"));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("providerId", providerId);
        if (req.enabled() != null && req.enabled() != c.isEnabled()) {
            if (req.enabled() && registry.adapter(providerId).isEmpty()) {
                throw new ApiException.ValidationException("enabled", "no adapter is registered for provider " + providerId);
            }
            detail.put("enabled", req.enabled());
            c.setEnabled(req.enabled());
        }
        if (req.ratePerSec() != null) {
            detail.put("ratePerSec", req.ratePerSec());
            c.setRateLimitPerSec(req.ratePerSec());
        }
        if (req.timeoutMs() != null) {
            detail.put("timeoutMs", req.timeoutMs());
            c.setTimeoutMs(req.timeoutMs());
        }
        c.setUpdatedAt(clock.instant());
        configs.save(c);
        audit.record(null, AuditAction.PROVIDER_CONFIG_UPDATED, "PROVIDER", providerId, detail);
        registry.invalidate();
        return health(c);
    }

    private ProviderHealth health(ProviderConfig c) {
        String id = c.getProviderId();
        ChannelProvider adapter = registry.adapter(id).orElse(null);
        boolean simulated = adapter instanceof SimulatedProviderBase;
        FaultInjector.Setting fault = simulated ? faults.get(id) : null;
        return new ProviderHealth(id, c.getChannel(), c.isEnabled(), invoker.state(id).name(), successRate(id),
                p95Ms(id), c.getRateLimitPerSec(), c.getRateLimitPerSec(), c.getTimeoutMs(),
                invoker.lastError(id).orElse(null), fault == null ? null : fault.mode().name(),
                fault == null ? null : fault.probability(), simulated);
    }

    /** Share of ACCEPTED provider calls since process start; 1.0 when there were no calls yet. */
    private double successRate(String providerId) {
        double total = 0;
        double ok = 0;
        for (Counter counter : metrics.registry().find(MessagingMetrics.PROVIDER_CALLS).tag("provider", providerId)
                .counters()) {
            total += counter.count();
            if ("ACCEPTED".equals(counter.getId().getTag("outcome"))) {
                ok += counter.count();
            }
        }
        return total == 0 ? 1.0 : ok / total;
    }

    private long p95Ms(String providerId) {
        Timer t = metrics.registry().find(MessagingMetrics.PROVIDER_LATENCY).tag("provider", providerId).timer();
        return t == null ? 0 : percentileMs(t, 0.95);
    }

    /** 95th percentile (or other) of a timer in milliseconds, 0 when there are no samples in the window. */
    public static long percentileMs(Timer timer, double percentile) {
        for (ValueAtPercentile v : timer.takeSnapshot().percentileValues()) {
            if (Math.abs(v.percentile() - percentile) < 1e-9) {
                return Math.round(v.value(TimeUnit.MILLISECONDS));
            }
        }
        return 0;
    }
}
