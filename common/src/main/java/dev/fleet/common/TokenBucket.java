package dev.fleet.common;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

public class TokenBucket {
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> script = new DefaultRedisScript<>();

    public record Decision(boolean allowed, long retryAfterMillis) {}

    public TokenBucket(StringRedisTemplate redis) {
        this.redis = redis;
        script.setLocation(new ClassPathResource("lua/token-bucket.lua"));
        script.setResultType(List.class);
    }

    public Decision acquire(long tenantId, int ratePerSecond, int count) {
        if (tenantId <= 0 || ratePerSecond <= 0 || count <= 0) {
            throw new IllegalArgumentException("Invalid rate request");
        }
        return acquireBucket(RedisKeys.ingestBucket(tenantId), ratePerSecond, count, 1000);
    }

    public Decision acquireLlm(long tenantId, int ratePerMinute) {
        if (tenantId <= 0 || ratePerMinute <= 0) {
            throw new IllegalArgumentException("Invalid LLM rate request");
        }
        return acquireBucket("tenant:" + tenantId + ":rate:llm", ratePerMinute, 1, 60000);
    }

    private Decision acquireBucket(String key, int rate, int count, int windowMs) {
        List<?> result =
                redis.execute(
                        script,
                        List.of(key),
                        Integer.toString(rate),
                        Integer.toString(count),
                        Integer.toString(windowMs));
        if (result == null || result.size() != 2) {
            throw new IllegalStateException("Invalid rate limiter result");
        }
        return new Decision(
                ((Number) result.get(0)).longValue() == 1, ((Number) result.get(1)).longValue());
    }
}
