package gg.elcartel.data.redis;

import io.lettuce.core.api.sync.RedisCommands;

/**
 * Limiter zdarzen w oknie czasowym (Redis, INCR+EXPIRE). Anti-bot na limbo:
 * np. limit nowych polaczen per IP/sekunde. Proste okno staloczasowe - wystarczajace
 * jako pierwsza warstwa; mozna podmienic na sliding window.
 */
public final class RateLimiter {

    private final RedisCommands<String, String> redis;

    public RateLimiter(RedisCommands<String, String> redis) {
        this.redis = redis;
    }

    /** true => zdarzenie dozwolone; false => limit przekroczony. */
    public boolean allow(String bucket, String id, int limit, int windowSeconds) {
        String key = "rl:" + bucket + ":" + id;
        long count = redis.incr(key);
        if (count == 1L) {
            redis.expire(key, windowSeconds);
        } else {
            if (redis.ttl(key) == -1L) {
                redis.expire(key, windowSeconds);
            }
        }
        return count <= limit;
    }
}
