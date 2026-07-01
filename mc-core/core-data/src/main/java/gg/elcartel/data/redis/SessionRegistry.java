package gg.elcartel.data.redis;

import io.lettuce.core.api.sync.RedisCommands;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Gdzie aktualnie jest gracz: proxy + shard (BLUEPRINT-kanaly, sekcje 3 i 8). */
public final class SessionRegistry {

    private final RedisCommands<String, String> redis;

    public SessionRegistry(RedisCommands<String, String> redis) {
        this.redis = redis;
    }

    private static String key(UUID id) {
        return "session:" + id;
    }

    public void set(UUID id, String proxy, String shard) {
        Map<String, String> fields = new HashMap<>();
        fields.put("proxy", proxy);
        fields.put("shard", shard);
        fields.put("ts", String.valueOf(System.currentTimeMillis()));
        redis.hset(key(id), fields);
    }

    public Map<String, String> get(UUID id) {
        return redis.hgetall(key(id));
    }

    public void clear(UUID id) {
        redis.del(key(id));
    }
}
