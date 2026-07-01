package gg.elcartel.paper.sector;

import gg.elcartel.data.CoreData;
import gg.elcartel.data.model.ShardInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Rejestracja sharda i heartbeat do Redis (BLUEPRINT-kanaly, sekcja 3).
 * Odczyty Bukkit (gracze/TPS) na watku glownym, zapis do Redis asynchronicznie.
 */
public final class ShardService {

    private static final long PERIOD_TICKS = 40L; // ~2 s

    private final JavaPlugin plugin;
    private final CoreData data;
    private final ShardInfo info;
    private BukkitTask task;

    public ShardService(JavaPlugin plugin, CoreData data, ShardInfo info) {
        this.plugin = plugin;
        this.data = data;
        this.info = info;
    }

    public void start() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> data.shards().register(info));
        this.task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, PERIOD_TICKS, PERIOD_TICKS);
    }

    private void tick() {
        int players = plugin.getServer().getOnlinePlayers().size();
        double[] tpsArr = plugin.getServer().getTPS();
        double tps = (tpsArr != null && tpsArr.length > 0) ? tpsArr[0] : 20.0;
        double mspt = plugin.getServer().getAverageTickTime();
        String state = players >= info.getSoftCap() ? "FULL" : "OPEN";
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
            () -> data.shards().heartbeat(info.getId(), players, tps, mspt, state));
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
        // przy wylaczaniu serwera wyrejestrowujemy synchronicznie (i tak konczymy prace)
        try {
            data.shards().unregister(info.getId(), info.getMode());
        } catch (Exception ignored) {
            // brak polaczenia przy shutdownie - heartbeat i tak wygasnie (freshness)
        }
    }
}
