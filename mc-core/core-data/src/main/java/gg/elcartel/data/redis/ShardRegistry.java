package gg.elcartel.data.redis;

import gg.elcartel.data.model.ShardInfo;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Rejestr shardow w Redis: shards:index, shards:mode:<mode>, shard:<id> (hash). */
public final class ShardRegistry {

    private static final String INDEX = "shards:index";

    private final RedisCommands<String, String> redis;

    public ShardRegistry(RedisCommands<String, String> redis) {
        this.redis = redis;
    }

    private static String shardKey(String id) { return "shard:" + id; }
    private static String modeKey(String mode) { return "shards:mode:" + mode; }

    public void register(ShardInfo info) {
        info.setHeartbeat(System.currentTimeMillis());
        redis.sadd(INDEX, info.getId());
        redis.sadd(modeKey(info.getMode()), info.getId());
        redis.hset(shardKey(info.getId()), info.toHash());
    }

    public void heartbeat(String id, int players, double tps, double mspt, String state) {
        Map<String, String> m = new HashMap<>();
        m.put("players", String.valueOf(players));
        m.put("tps", String.valueOf(tps));
        m.put("mspt", String.valueOf(mspt));
        m.put("state", state);
        m.put("heartbeat", String.valueOf(System.currentTimeMillis()));
        redis.hset(shardKey(id), m);
    }

    public void unregister(String id, String mode) {
        redis.srem(INDEX, id);
        redis.srem(modeKey(mode), id);
        redis.del(shardKey(id));
    }

    public Set<String> listMode(String mode) {
        return redis.smembers(modeKey(mode));
    }

    public Set<String> listAll() {
        return redis.smembers(INDEX);
    }

    public ShardInfo get(String id) {
        Map<String, String> h = redis.hgetall(shardKey(id));
        if (h == null || h.isEmpty()) {
            return null;
        }
        return ShardInfo.fromHash(id, h);
    }

    public int getPlayersInMode(String mode) {
        int count = 0;
        Set<String> shards = listMode(mode);
        long now = System.currentTimeMillis();
        for (String id : shards) {
            ShardInfo info = get(id);
            if (info != null && (now - info.getHeartbeat() < 15000)) {
                count += info.getPlayers();
            }
        }
        return count;
    }

    public int getGlobalPlayers() {
        int count = 0;
        Set<String> shards = listAll();
        long now = System.currentTimeMillis();
        for (String id : shards) {
            ShardInfo info = get(id);
            if (info != null && (now - info.getHeartbeat() < 15000)) {
                count += info.getPlayers();
            }
        }
        return count;
    }

    public void publish(String channel, String message) {
        redis.publish(channel, message);
    }
}
