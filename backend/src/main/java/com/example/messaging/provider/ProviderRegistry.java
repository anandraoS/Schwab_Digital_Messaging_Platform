package com.example.messaging.provider;

import com.example.messaging.domain.Channel;
import com.example.messaging.domain.ProviderConfig;
import com.example.messaging.repository.ProviderConfigRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Strategy that picks the provider for a channel (ADR-0002). Selection is data driven: enabled rows of
 * {@code provider_config} ordered by {@code priority_order}, restricted to providers that have an adapter bean, and
 * preferring providers whose circuit is not open (failover). Registering a new vendor therefore needs a bean and a
 * config row, never a change to the orchestration code.
 */
@Component
public class ProviderRegistry {

    /** A provider adapter together with its configuration row. */
    public record Selected(ChannelProvider provider, ProviderConfig config) {}

    private static final long CACHE_NANOS = 1_000_000_000L;

    private final Map<String, ChannelProvider> adapters;
    private final ProviderConfigRepository configs;
    private final ResilientProviderInvoker invoker;
    private final Map<Channel, CachedConfigs> cache = new ConcurrentHashMap<>();

    private record CachedConfigs(List<ProviderConfig> rows, long loadedAtNanos) {}

    public ProviderRegistry(List<ChannelProvider> providers, ProviderConfigRepository configs,
            ResilientProviderInvoker invoker) {
        Map<String, ChannelProvider> m = new HashMap<>();
        for (ChannelProvider p : providers) {
            if (m.putIfAbsent(p.providerId(), p) != null) {
                throw new IllegalStateException("Duplicate provider id " + p.providerId());
            }
        }
        this.adapters = Map.copyOf(m);
        this.configs = configs;
        this.invoker = invoker;
    }

    /** Picks the provider for the channel, or empty when none is enabled and available. */
    public Optional<Selected> select(Channel channel) {
        List<Selected> candidates = rows(channel).stream()
                .filter(c -> adapters.containsKey(c.getProviderId()))
                .map(c -> new Selected(adapters.get(c.getProviderId()), c))
                .collect(Collectors.toList());
        return candidates.stream().filter(s -> !invoker.isOpen(s.provider().providerId())).findFirst()
                .or(() -> candidates.stream().findFirst());
    }

    /** Provider by id regardless of enabled flag (reconciliation of in-flight messages, callbacks). */
    public Optional<Selected> byId(String providerId) {
        ChannelProvider p = adapters.get(providerId);
        if (p == null) {
            return Optional.empty();
        }
        return configs.findByProviderId(providerId).map(c -> new Selected(p, c));
    }

    public Optional<ChannelProvider> adapter(String providerId) {
        return Optional.ofNullable(adapters.get(providerId));
    }

    public Map<String, ChannelProvider> adapters() {
        return adapters;
    }

    /** Drops cached configuration (called after an admin changes {@code provider_config}). */
    public void invalidate() {
        cache.clear();
    }

    private List<ProviderConfig> rows(Channel channel) {
        long now = System.nanoTime();
        CachedConfigs c = cache.get(channel);
        if (c == null || now - c.loadedAtNanos() > CACHE_NANOS) {
            c = new CachedConfigs(configs.findByChannelAndEnabledTrueOrderByPriorityOrderAsc(channel), now);
            cache.put(channel, c);
        }
        return c.rows();
    }
}
