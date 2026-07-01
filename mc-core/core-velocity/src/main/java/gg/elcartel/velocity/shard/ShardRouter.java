package gg.elcartel.velocity.shard;

import gg.elcartel.data.model.ShardInfo;
import gg.elcartel.data.redis.ShardRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Wybor sharda dla trybu na podstawie rejestru (najmniej obciazony, swiezy, OPEN). */
public final class ShardRouter {

    public static final long STALE_MS = 5000L;

    private final ShardRegistry registry;

    public ShardRouter(ShardRegistry registry) {
        this.registry = registry;
    }

    public ShardRegistry getRegistry() {
        return registry;
    }

    /** Najmniej obciazony joinable shard trybu (remis -> wyzszy TPS). */
    public Optional<ShardInfo> chooseLeastLoaded(String mode) {
        long now = System.currentTimeMillis();
        ShardInfo best = null;
        for (String id : registry.listMode(mode)) {
            ShardInfo s = registry.get(id);
            if (s == null || !s.isJoinable(now, STALE_MS)) {
                continue;
            }
            if (s.hasSector() && !s.isSpawnSector()) {
                continue;
            }
            if (best == null
                || s.getPlayers() < best.getPlayers()
                || (s.getPlayers() == best.getPlayers() && s.getTps() > best.getTps())) {
                best = s;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Wszystkie swieze kanaly trybu (do listy/wyboru), posortowane wg obciazenia. */
    public List<ShardInfo> listFresh(String mode) {
        long now = System.currentTimeMillis();
        List<ShardInfo> out = new ArrayList<>();
        for (String id : registry.listMode(mode)) {
            ShardInfo s = registry.get(id);
            if (s != null && s.isFresh(now, STALE_MS)) {
                out.add(s);
            }
        }
        out.sort((a, b) -> Integer.compare(a.getPlayers(), b.getPlayers()));
        return out;
    }

    public ShardInfo get(String id) {
        return registry.get(id);
    }
}
