package dev.fleet.processor;

import dev.fleet.common.Json;
import dev.fleet.common.Position;
import dev.fleet.common.RedisKeys;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class RedisPositionStore {
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> script = new DefaultRedisScript<>();

    public RedisPositionStore(StringRedisTemplate redis) {
        this.redis = redis;
        script.setLocation(new ClassPathResource("lua/positions.lua"));
        script.setResultType(List.class);
    }

    public List<Boolean> apply(List<Position> fullBatch) {
        if (fullBatch.isEmpty()) {
            return List.of();
        }
        var keys = new ArrayList<String>(fullBatch.size() * 2);
        var arguments = new ArrayList<String>(fullBatch.size() * 4);
        for (var position : fullBatch) {
            keys.add(RedisKeys.latest(position.tenantId(), position.assetId()));
            keys.add(RedisKeys.geo(position.tenantId()));
            arguments.add(Long.toString(position.positionTs()));
            arguments.add(Double.toString(position.lon()));
            arguments.add(Double.toString(position.lat()));
            arguments.add(Json.write(position));
        }
        List<?> flags = redis.execute(script, keys, arguments.toArray());
        if (flags == null || flags.size() != fullBatch.size()) {
            throw new IllegalStateException("Invalid position script result");
        }
        return flags.stream().map(flag -> ((Number) flag).longValue() == 1).toList();
    }
}
