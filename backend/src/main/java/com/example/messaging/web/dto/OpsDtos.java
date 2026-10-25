package com.example.messaging.web.dto;

import com.example.messaging.domain.Channel;
import com.example.messaging.domain.DeliveryEventType;
import com.example.messaging.provider.simulator.FaultInjector;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** Request/response records of the provider and operations APIs (stage 2). */
public final class OpsDtos {

    private OpsDtos() {}

    /**
     * Provider health. {@code rateLimitPerSec} duplicates {@code ratePerSec} and {@code faultProbability} complements
     * {@code faultMode}: both are kept additively so the Angular console, which uses those names, works unchanged.
     */
    public record ProviderHealth(
            String providerId,
            Channel channel,
            boolean enabled,
            String circuitState,
            double successRate,
            long p95LatencyMs,
            int ratePerSec,
            int rateLimitPerSec,
            int timeoutMs,
            String lastError,
            String faultMode,
            Double faultProbability,
            boolean simulated) {}

    /** All fields optional; omitted fields keep their value. {@code rateLimitPerSec} is accepted as an alias. */
    public record ProviderConfigRequest(
            Boolean enabled,
            @JsonAlias("rateLimitPerSec") @Min(1) @Max(100_000) Integer ratePerSec,
            @Min(100) @Max(60_000) Integer timeoutMs) {}

    public record FaultModeRequest(
            @NotNull FaultInjector.Mode mode,
            @DecimalMin("0.0") @DecimalMax("1.0") Double probability) {}

    /** Queue statistics. The two alias fields exist for the Angular console (seconds / consumersPaused). */
    public record QueueStatsView(
            Map<String, Long> depthByPriority,
            long inFlight,
            int consumers,
            long dlqDepth,
            long retryPending,
            long delayed,
            Long oldestMessageAgeMs,
            Long oldestMessageAgeSeconds,
            boolean paused,
            boolean consumersPaused) {}

    public record OpsSummary(
            Map<String, Long> byStatus,
            Map<String, Long> byChannel,
            long total,
            double successRate,
            long p95DispatchDelayMs,
            int windowHours) {}

    public record ConsumerState(boolean paused) {}

    public record SimulatorEmitRequest(
            @NotBlank @Size(max = 36) String messageId,
            @NotNull DeliveryEventType type,
            @Min(0) @Max(60_000) Long delayMs,
            Boolean duplicate,
            Boolean outOfOrder) {}
}
