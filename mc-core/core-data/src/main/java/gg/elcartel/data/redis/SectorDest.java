package gg.elcartel.data.redis;

import io.lettuce.core.api.sync.RedisCommands;

import java.util.UUID;

/**
 * Docelowa pozycja gracza po transferze sektorowym (krotki TTL).
 * Klucz sector:dest:&lt;uuid&gt; = "world|x|y|z|yaw|pitch". Docelowy shard czyta i czysci przy wejsciu.
 */
public final class SectorDest {

    private final RedisCommands<String, String> redis;

    public SectorDest(RedisCommands<String, String> redis) {
        this.redis = redis;
    }

    private static String key(UUID id) {
        return "sector:dest:" + id;
    }

    public void set(UUID id, String payload, long ttlMs) {
        redis.psetex(key(id), ttlMs, payload);
    }

    /** Podglad bez kasowania (do pominiecia pozycji z profilu). */
    public String peek(UUID id) {
        return redis.get(key(id));
    }

    /** Zwraca i kasuje docelowa pozycje (albo null). */
    public String take(UUID id) {
        String k = key(id);
        String v = redis.get(k);
        if (v != null) {
            redis.del(k);
        }
        return v;
    }
}
