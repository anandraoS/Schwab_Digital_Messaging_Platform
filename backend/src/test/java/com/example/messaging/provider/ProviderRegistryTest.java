package com.example.messaging.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.messaging.domain.Channel;
import com.example.messaging.domain.ProviderConfig;
import com.example.messaging.repository.ProviderConfigRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProviderRegistryTest {

    private static SmsProvider sms(String id) {
        return new SmsProvider() {
            @Override
            public String providerId() {
                return id;
            }

            @Override
            public ProviderResult send(ProviderRequest request) {
                return ProviderResult.accepted(id + "-1", 0);
            }

            @Override
            public Optional<ProviderStatus> lookup(String idempotencyRef) {
                return Optional.empty();
            }
        };
    }

    private static ProviderConfig config(String id, Channel channel, boolean enabled, int order) {
        ProviderConfig c = new ProviderConfig();
        c.setProviderId(id);
        c.setChannel(channel);
        c.setEnabled(enabled);
        c.setPriorityOrder(order);
        c.setRateLimitPerSec(10);
        c.setTimeoutMs(1000);
        return c;
    }

    private final ProviderConfigRepository configs = mock(ProviderConfigRepository.class);
    private final ResilientProviderInvoker invoker = mock(ResilientProviderInvoker.class);

    @Test
    void picksTheEnabledProviderWithTheLowestPriorityOrderThatHasAnAdapter() {
        when(configs.findByChannelAndEnabledTrueOrderByPriorityOrderAsc(Channel.SMS)).thenReturn(List.of(
                config("no-adapter", Channel.SMS, true, 0), config("a", Channel.SMS, true, 1),
                config("b", Channel.SMS, true, 2)));
        ProviderRegistry registry = new ProviderRegistry(List.of(sms("a"), sms("b")), configs, invoker);
        assertThat(registry.select(Channel.SMS).orElseThrow().provider().providerId()).isEqualTo("a");
        assertThat(registry.select(Channel.SMS).orElseThrow().config().getProviderId()).isEqualTo("a");
    }

    @Test
    void anOpenCircuitFailsOverToTheNextProviderButIfAllAreOpenTheFirstIsStillReturned() {
        when(configs.findByChannelAndEnabledTrueOrderByPriorityOrderAsc(Channel.SMS)).thenReturn(List.of(
                config("a", Channel.SMS, true, 1), config("b", Channel.SMS, true, 2)));
        ProviderRegistry registry = new ProviderRegistry(List.of(sms("a"), sms("b")), configs, invoker);
        when(invoker.isOpen("a")).thenReturn(true);
        assertThat(registry.select(Channel.SMS).orElseThrow().provider().providerId()).isEqualTo("b");
        when(invoker.isOpen("b")).thenReturn(true);
        assertThat(registry.select(Channel.SMS).orElseThrow().provider().providerId()).isEqualTo("a");
    }

    @Test
    void nothingEnabledOrNoAdapterMeansNoProvider() {
        when(configs.findByChannelAndEnabledTrueOrderByPriorityOrderAsc(Channel.PUSH)).thenReturn(List.of());
        when(configs.findByChannelAndEnabledTrueOrderByPriorityOrderAsc(Channel.EMAIL))
                .thenReturn(List.of(config("ghost", Channel.EMAIL, true, 1)));
        ProviderRegistry registry = new ProviderRegistry(List.of(sms("a")), configs, invoker);
        assertThat(registry.select(Channel.PUSH)).isEmpty();
        assertThat(registry.select(Channel.EMAIL)).isEmpty();
    }

    @Test
    void configurationIsCachedBrieflyAndInvalidateForcesAReload() {
        when(configs.findByChannelAndEnabledTrueOrderByPriorityOrderAsc(Channel.SMS))
                .thenReturn(List.of(config("a", Channel.SMS, true, 1)));
        ProviderRegistry registry = new ProviderRegistry(List.of(sms("a"), sms("b")), configs, invoker);
        registry.select(Channel.SMS);
        registry.select(Channel.SMS);
        verify(configs, times(1)).findByChannelAndEnabledTrueOrderByPriorityOrderAsc(Channel.SMS);
        when(configs.findByChannelAndEnabledTrueOrderByPriorityOrderAsc(Channel.SMS))
                .thenReturn(List.of(config("b", Channel.SMS, true, 1)));
        assertThat(registry.select(Channel.SMS).orElseThrow().provider().providerId()).isEqualTo("a"); // still cached
        registry.invalidate();
        assertThat(registry.select(Channel.SMS).orElseThrow().provider().providerId()).isEqualTo("b");
    }

    @Test
    void byIdIgnoresTheEnabledFlagForInFlightWork() {
        when(configs.findByProviderId("a")).thenReturn(Optional.of(config("a", Channel.SMS, false, 1)));
        when(configs.findByProviderId("ghost")).thenReturn(Optional.of(config("ghost", Channel.SMS, true, 1)));
        ProviderRegistry registry = new ProviderRegistry(List.of(sms("a")), configs, invoker);
        assertThat(registry.byId("a")).isPresent();
        assertThat(registry.byId("a").orElseThrow().config().isEnabled()).isFalse();
        assertThat(registry.byId("ghost")).isEmpty(); // config row without an adapter bean
        assertThat(registry.byId("unknown")).isEmpty();
        assertThat(registry.adapter("a")).isPresent();
        assertThat(registry.adapter("ghost")).isEmpty();
        assertThat(registry.adapters()).containsOnlyKeys("a");
    }

    @Test
    void twoAdaptersWithTheSameIdAreAConfigurationError() {
        assertThatThrownBy(() -> new ProviderRegistry(List.of(sms("a"), sms("a")), configs, invoker))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate provider id a");
        verify(configs, times(0)).findByProviderId(any());
    }
}
