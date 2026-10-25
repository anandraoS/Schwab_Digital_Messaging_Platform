package com.example.messaging.config;

import com.example.messaging.coordination.CoordinationService;
import com.example.messaging.coordination.InMemoryCoordination;
import com.example.messaging.coordination.RedisCoordination;
import com.example.messaging.coordination.ResilientCoordination;
import com.example.messaging.metrics.MessagingMetrics;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** {@code messaging.coordination.type=memory|redis}; Redis is always wrapped by the in-memory fallback decorator. */
@Configuration
public class CoordinationConfig {

    @Bean
    @ConditionalOnProperty(name = "messaging.coordination.type", havingValue = "memory", matchIfMissing = true)
    public CoordinationService inMemoryCoordination(Clock clock) {
        return new InMemoryCoordination(clock);
    }

    @Bean
    @ConditionalOnProperty(name = "messaging.coordination.type", havingValue = "redis")
    public CoordinationService redisCoordination(StringRedisTemplate redis, MessagingProperties props,
            MessagingMetrics metrics, Clock clock) {
        return new ResilientCoordination(
                new RedisCoordination(redis, props.coordination().keyPrefix()),
                new InMemoryCoordination(clock),
                metrics,
                clock,
                props.coordination().fallbackCooldown());
    }
}
