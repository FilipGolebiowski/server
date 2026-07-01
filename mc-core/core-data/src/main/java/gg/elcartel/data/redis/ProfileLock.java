package gg.elcartel.data.redis;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Lock-lease na profil per-tryb (BLUEPRINT-kanaly sek. 5, BLUEPRINT-modes sek. 2).
 * Subject = "<uuid>:<mode>" - osobny lock dla danych gracza w danym trybie.
 */
public final class ProfileLock {

    private static final String RENEW =
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end";
    private static final String RELEASE =
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final RedisCommands<String, String> redis;

    public ProfileLock(RedisCommands<String, String> redis) {
        this.redis = redis;
    }

    private static String key(String subject) {
        return "lock:profile:" + subject;
    }

    public boolean acquire(String subject, String owner, long ttlMs) {
        return "OK".equals(redis.set(key(subject), owner, SetArgs.Builder.nx().px(ttlMs)));
    }

    /** Wymuszone przejecie (bez NX) - gdy poprzedni wlasciciel na pewno odszedl. */
    public void take(String subject, String owner, long ttlMs) {
        redis.set(key(subject), owner, SetArgs.Builder.px(ttlMs));
    }

    public boolean renew(String subject, String owner, long ttlMs) {
        Long r = redis.eval(RENEW, ScriptOutputType.INTEGER, new String[]{key(subject)}, owner, String.valueOf(ttlMs));
        return r != null && r > 0;
    }

    public boolean release(String subject, String owner) {
        Long r = redis.eval(RELEASE, ScriptOutputType.INTEGER, new String[]{key(subject)}, owner);
        return r != null && r > 0;
    }
}
