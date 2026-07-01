package gg.elcartel.paper.board;

import gg.elcartel.data.CoreData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import java.util.regex.Pattern;

public class ScoreboardService implements Listener {

    private final JavaPlugin plugin;
    private final CoreData data;
    private final String shardId;
    private final Map<UUID, FastBoard> boards = new ConcurrentHashMap<>();
    private BukkitTask task;

    private static final Pattern MODE_PATTERN = Pattern.compile("%online_mode_([a-zA-Z0-9]+)%");

    public ScoreboardService(JavaPlugin plugin, CoreData data, String shardId) {
        this.plugin = plugin;
        this.data = data;
        this.shardId = shardId;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (gg.elcartel.data.api.CoreApi.get() == null || gg.elcartel.data.api.CoreApi.get().getScoreboardTitle() == null) return;
            
            // Cacheujemy globalne wyniki, by nie obciazac Redisa 50 razy na sekunde
            int globalCount = data.shards().getGlobalPlayers();
            
            for (Player player : Bukkit.getOnlinePlayers()) {
                FastBoard board = boards.get(player.getUniqueId());
                if (board == null) continue;
                
                updateBoard(player, board, globalCount);
            }
        }, 20L, 20L); // co sekunde
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
        boards.values().forEach(FastBoard::delete);
        boards.clear();
    }

    public void addPlayer(Player player) {
        if (gg.elcartel.data.api.CoreApi.get() != null && gg.elcartel.data.api.CoreApi.get().getScoreboardTitle() != null) {
            boards.put(player.getUniqueId(), new FastBoard(player));
        }
    }

    public void removePlayer(Player player) {
        FastBoard board = boards.remove(player.getUniqueId());
        if (board != null) {
            board.delete();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        addPlayer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removePlayer(event.getPlayer());
    }

    private void updateBoard(Player player, FastBoard board, int globalCount) {
        String titleStr = replacePlaceholders(player, gg.elcartel.data.api.CoreApi.get().getScoreboardTitle(), globalCount);
        board.updateTitle(LegacyComponentSerializer.legacyAmpersand().deserialize(titleStr));

        List<Component> newLines = new ArrayList<>();
        for (String lineStr : gg.elcartel.data.api.CoreApi.get().getScoreboardLines()) {
            String parsed = replacePlaceholders(player, lineStr, globalCount);
            newLines.add(LegacyComponentSerializer.legacyAmpersand().deserialize(parsed));
        }
        
        board.updateLines(newLines);
    }

    private String replacePlaceholders(Player player, String text, int globalCount) {
        if (text == null) return "";
        
        String result = text
                .replace("%shard_id%", shardId != null ? shardId : "unknown")
                .replace("%online_global%", String.valueOf(globalCount));

        if (result.contains("%rank%")) {
            String rank = "Brak";
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
                rank = LuckPermsHook.getRank(player);
            }
            result = result.replace("%rank%", rank);
        }

        // Parowanie trybow: %online_mode_AnarchiaSMP%, %online_mode_hub% itp.
        if (result.contains("%online_mode_")) {
            Matcher m = MODE_PATTERN.matcher(result);
            while (m.find()) {
                String modeName = m.group(1);
                int modeCount = data.shards().getPlayersInMode(modeName.toLowerCase()); // modes are lowercase usually, or check exactly
                // actually listMode relies on exact string match, we should maybe ensure mode is passed exactly or lowercase
                // ShardRegistry uses mode exactly as passed. Let's try exact.
                // Or maybe we try the exact group, if 0, try lowercase.
                if (modeCount == 0 && !modeName.equals(modeName.toLowerCase())) {
                    modeCount = data.shards().getPlayersInMode(modeName.toLowerCase());
                }
                result = result.replace(m.group(0), String.valueOf(modeCount));
            }
        }
        return result;
    }
}
