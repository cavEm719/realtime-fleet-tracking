package dev.fleet.api;

import dev.fleet.common.TokenBucket;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RateLimiter extends TokenBucket {
    public RateLimiter(StringRedisTemplate redis) {
        super(redis);
    }
}
