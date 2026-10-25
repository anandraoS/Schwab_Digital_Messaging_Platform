package com.example.messaging.coordination;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis adapter (Lettuce via Spring Data Redis). The token bucket runs atomically in a Lua script and uses the Redis
 * server clock, so all application instances share one consistent bucket. Not executed in this repository's local
 * build (no Redis available); covered by the {@code *ContainerIT} profile.
 */
public class RedisCoordination implements CoordinationService {

    private static final String LUA = """
            local key = KEYS[1]
            local rate = tonumber(ARGV[1])
            local burst = tonumber(ARGV[2])
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local data = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil then
              tokens = burst
              ts = now
            end
            local delta = math.max(0, now - ts)
            tokens = math.min(burst, tokens + delta * rate / 1000)
            local allowed = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            end
            redis.call('HSET', key, 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', key, math.ceil(burst / rate * 1000) + 1000)
            local retry = 0
            if allowed == 0 then
              retry = math.ceil((1 - tokens) * 1000 / rate)
            end
            local reset = math.ceil((burst - tokens) * 1000 / rate)
            return {allowed, math.floor(tokens), retry, reset}
            """;

    @SuppressWarnings({"rawtypes", "unchecked"})
    private final DefaultRedisScript<List> script = new DefaultRedisScript<>(LUA, List.class);

    private final StringRedisTemplate redis;
    private final String prefix;

    public RedisCoordination(StringRedisTemplate redis, String prefix) {
        this.redis = redis;
        this.prefix = prefix;
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public Optional<String> getCached(String key) {
        return Optional.ofNullable(redis.opsForValue().get(prefix + "cache:" + key));
    }

    @Override
    public void putCached(String key, String value, Duration ttl) {
        redis.opsForValue().set(prefix + "cache:" + key, value, ttl);
    }

    @Override
    @SuppressWarnings("unchecked")
    public RateLimitDecision tryAcquire(String bucketKey, int ratePerSec, int burst) {
        int rate = Math.max(1, ratePerSec);
        int cap = Math.max(1, burst);
        List<Long> r = (List<Long>) redis.execute(script, List.of(prefix + "bucket:" + bucketKey),
                Integer.toString(rate), Integer.toString(cap));
        if (r == null || r.size() < 4) {
            throw new IllegalStateException("Unexpected Redis token-bucket response");
        }
        return new RateLimitDecision(r.get(0) == 1L, cap, r.get(1), r.get(2), r.get(3));
    }
}
