package com.example.messaging.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Provider simulator behaviour ({@code messaging.simulator.*}); only meaningful in profiles local/test. */
@ConfigurationProperties("messaging.simulator")
public record SimulatorProperties(
        /** Simulators post SENT/DELIVERED callbacks to the platform after accepting a message. */
        @DefaultValue("false") boolean autoCallbacks,
        @DefaultValue("50ms") Duration callbackDelay,
        /** Deterministic fault selection when set; random otherwise. */
        Long seed,
        /** Timeout simulation sleeps this long (0 = request timeout + 500 ms). */
        @DefaultValue("0") long timeoutSleepMs,
        @DefaultValue("local-dev-secret-not-for-production") String fallbackSecret) {}
