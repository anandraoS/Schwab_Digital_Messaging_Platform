package com.example.messaging.config;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the stage-2 property records and the shared backpressure limit. */
@Configuration
@EnableConfigurationProperties({PipelineProperties.class, SimulatorProperties.class})
public class PipelineConfig {

    /** One adjustable limit shared by the bus (publish rejection) and the API guard (503 on submit). */
    public static final class BackpressureLimits {
        private final AtomicInteger maxQueueDepth;

        public BackpressureLimits(int initial) {
            this.maxQueueDepth = new AtomicInteger(Math.max(1, initial));
        }

        public int maxQueueDepth() {
            return maxQueueDepth.get();
        }

        /** Runtime override (ops tooling and tests); values below 1 are clamped to 1. */
        public void setMaxQueueDepth(int value) {
            maxQueueDepth.set(Math.max(1, value));
        }
    }

    @Bean
    public BackpressureLimits backpressureLimits(MessagingProperties props) {
        return new BackpressureLimits(props.backpressure().maxQueueDepth());
    }
}
