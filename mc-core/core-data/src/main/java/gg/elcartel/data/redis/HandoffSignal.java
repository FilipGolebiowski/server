package gg.elcartel.data.redis;

import io.lettuce.core.api.sync.RedisCommands;

import java.util.HashMap;
import java.util.Map;

/** Sygnal gotowosci danych przy handoffie. Subject = "<uuid>:<mode>". */
public final class HandoffSignal {

    private final RedisCommands<String, String> redis;

    public HandoffSignal(RedisCommands<String, String> redis) {
        this.redis = redis;
    }

    private static String key(String subject) {
        return "handoff:" + subject;
    }

    public void set(String subject, long version, String fromShard, String toShard, long ttlMs) {
        String k = key(subject);
        Map<String, String> fields = new HashMap<>();
        fields.put("version", String.valueOf(version));
        fields.put("from", fromShard);
        fields.put("to", toShard);
        fields.put("ts", String.valueOf(System.currentTimeMillis()));
        redis.hset(k, fields);
        redis.pexpire(k, ttlMs);
    }

    public Map<String, String> get(String subject) {
        return redis.hgetall(key(subject));
    }

    public void clear(String subject) {
        redis.del(key(subject));
    }
}
