package gg.elcartel.data.redis;

import io.lettuce.core.api.sync.RedisCommands;

import java.util.UUID;

/**
 * Docelowa pozycja gracza po transferze typu cross-server teleport.
 * Klucz tp:dest:<uuid> = "target_uuid". Docelowy shard czyta i czysci przy wejsciu.
 */
public final class TpDest {

    private final RedisCommands<String, String> redis;

    public TpDest(RedisCommands<String, String> redis) {
        this.redis = redis;
    }

    private static String key(UUID id) {
        return "tp:dest:" + id;
    }

    public void set(UUID id, String targetUuid, long ttlMs) {
        redis.psetex(key(id), ttlMs, targetUuid);
    }

    public String peek(UUID id) {
        return redis.get(key(id));
    }

    public String take(UUID id) {
        String k = key(id);
        String v = redis.get(k);
        if (v != null) {
            redis.del(k);
        }
        return v;
    }
}
