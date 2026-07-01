package gg.elcartel.data.redis;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Lockout po serii nieudanych logowan (Redis). Chroni przed brute-force.
 * Klucz po IP i/lub nicku. Po przekroczeniu progu ustawia blokade na lockoutSeconds.
 */
public final class LoginThrottle {

    private final RedisCommands<String, String> redis;

    public LoginThrottle(RedisCommands<String, String> redis) {
        this.redis = redis;
    }

    private static String failKey(String id) { return "login:fail:" + id; }
    private static String lockKey(String id) { return "login:lock:" + id; }

    /** Pozostaly czas blokady w ms (0 = brak). */
    public long lockedForMs(String id) {
        Long pttl = redis.pttl(lockKey(id));
        return (pttl != null && pttl > 0) ? pttl : 0L;
    }

    /** Rejestruje nieudana probe; po osiagnieciu maxAttempts ustawia blokade. */
    public void recordFailure(String id, int maxAttempts, int lockoutSeconds) {
        String fk = failKey(id);
        long n = redis.incr(fk);
        if (n == 1L) {
            redis.expire(fk, lockoutSeconds);
        }
        if (n >= maxAttempts) {
            redis.set(lockKey(id), "1", SetArgs.Builder.ex(lockoutSeconds));
            redis.del(fk);
        }
    }

    /** Reset po udanym logowaniu. */
    public void reset(String id) {
        redis.del(failKey(id));
        redis.del(lockKey(id));
    }
}
