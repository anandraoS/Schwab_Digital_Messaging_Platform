package com.example.messaging.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Typed {@code messaging.*} configuration (CONTRACT section 9). No secrets live in configuration files. */
@ConfigurationProperties("messaging")
public record MessagingProperties(
        @DefaultValue Dispatch dispatch,
        @DefaultValue Backpressure backpressure,
        @DefaultValue Bus bus,
        @DefaultValue Coordination coordination,
        Map<String, Provider> providers,
        @DefaultValue Callback callback,
        @DefaultValue Api api,
        @DefaultValue Bulk bulk,
        @DefaultValue Retention retention,
        @DefaultValue Security security,
        @DefaultValue Templates templates,
        @DefaultValue Idempotency idempotency) {

    public MessagingProperties {
        providers = providers == null ? Map.of() : providers;
    }

    public record Dispatch(
            @DefaultValue("8") int concurrency,
            @DefaultValue("16") int prefetch,
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("1000") long backoffBaseMs,
            @DefaultValue("60000") long backoffCapMs,
            @DefaultValue("5") int poisonThreshold,
            @DefaultValue("15m") Duration receiptTimeout,
            @DefaultValue("5m") Duration reconcileAfter,
            @DefaultValue("2m") Duration lockTtl) {}

    public record Backpressure(@DefaultValue("5000") int maxQueueDepth) {}

    /** {@code memory} or {@code rabbit}. */
    public record Bus(@DefaultValue("memory") String type) {}

    /** {@code memory} or {@code redis}. */
    public record Coordination(
            @DefaultValue("memory") String type,
            @DefaultValue("msg:") String keyPrefix,
            @DefaultValue("30s") Duration fallbackCooldown) {}

    public record Provider(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("50") int ratePerSec,
            @DefaultValue("2000") int timeoutMs) {}

    public record Callback(@DefaultValue("5m") Duration tolerance, Map<String, String> secrets) {
        public Callback {
            secrets = secrets == null ? Map.of() : secrets;
        }
    }

    public record Api(
            @DefaultValue("50") int ratePerSec,
            @DefaultValue("100") int burst,
            @DefaultValue("262144") long maxBodyBytes) {}

    public record Bulk(
            @DefaultValue("4") int concurrency,
            @DefaultValue("500") int chunkSize,
            @DefaultValue("100") int skipLimit,
            @DefaultValue("100000") int maxRows,
            @DefaultValue("target/bulk-uploads") String uploadDir) {}

    public record Retention(
            @DefaultValue("90") int messageDays,
            @DefaultValue("365") int auditDays,
            @DefaultValue("30") int eventDays) {}

    public record Security(
            @DefaultValue("false") boolean devTokenEnabled,
            @DefaultValue("messaging-dev-issuer") String devIssuer,
            @DefaultValue("1h") Duration devTokenTtl,
            @DefaultValue("false") boolean docsPublic,
            @DefaultValue("false") boolean metricsPublic) {}

    public record Templates(
            @DefaultValue("50") int maxVariables,
            @DefaultValue("2000") int maxVariableLength,
            @DefaultValue("10000") int maxBodyLength,
            @DefaultValue("200000") int maxRenderedEmailLength,
            @DefaultValue("10") int maxSmsSegments,
            @DefaultValue("65") int maxPushTitleLength,
            @DefaultValue("240") int maxPushBodyLength,
            @DefaultValue("255") int maxSubjectLength) {}

    public record Idempotency(
            @DefaultValue("30s") Duration claimTtl,
            @DefaultValue("24h") Duration recordTtl) {}
}
