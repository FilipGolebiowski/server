package gg.elcartel.paper.security;

import gg.elcartel.paper.CorePaperPlugin;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AntiLagListener implements Listener {

    private final CorePaperPlugin plugin;

    // Track redstone updates per chunk. Key: WorldName_ChunkX_ChunkZ
    private final Map<String, ChunkRedstoneData> redstoneData = new ConcurrentHashMap<>();

    public AntiLagListener(CorePaperPlugin plugin) {
        this.plugin = plugin;
    }

    private int getMaxRedstoneUpdates() {
        return plugin.getConfig().getInt("security.anti_lag.redstone_max_updates_per_chunk", 150);
    }

    private long getRedstoneWindow() {
        return plugin.getConfig().getLong("security.anti_lag.redstone_window_ms", 5000L);
    }

    private int getMaxAnimals() {
        return plugin.getConfig().getInt("security.anti_lag.max_animals_per_chunk", 50);
    }

    private int getMaxMonsters() {
        return plugin.getConfig().getInt("security.anti_lag.max_monsters_per_chunk", 50);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onRedstone(BlockRedstoneEvent event) {
        // Ignorujemy zgasniecia pradu, zliczamy tylko aktywacje/zmiany stanu na wyzszy
        if (event.getOldCurrent() >= event.getNewCurrent()) return;

        Block block = event.getBlock();
        Chunk chunk = block.getChunk();
        String chunkKey = chunk.getWorld().getName() + "_" + chunk.getX() + "_" + chunk.getZ();

        long now = System.currentTimeMillis();
        long window = getRedstoneWindow();
        int maxUpdates = getMaxRedstoneUpdates();

        ChunkRedstoneData data = redstoneData.computeIfAbsent(chunkKey, k -> new ChunkRedstoneData(now));

        // Zresetuj okno jesli minelo
        if (now - data.startTime > window) {
            data.startTime = now;
            data.count = 0;
        }

        data.count++;

        if (data.count > maxUpdates) {
            // Przekroczono limit w danym oknie czasowym - niszczymy maszyne lagujaca
            event.setNewCurrent(0);
            if (block.getType() == Material.REDSTONE_WIRE || block.getType().name().contains("REPEATER") || block.getType().name().contains("COMPARATOR")) {
                block.breakNaturally();
            }
            // Zeby nie spamic niszczeniem i resetowaniem:
            data.count = 0; 
            data.startTime = now + 10000L; // Dajemy 10 sekund "kary" spokoju
            
            plugin.getLogger().warning("Zniszczono zrodlo szybkiego zegara Redstone na koordynatach: " 
                + block.getX() + ", " + block.getY() + ", " + block.getZ() + " (Chunk: " + chunkKey + ")");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        checkEntityLimit(event.getEntity(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        checkEntityLimit(event.getEntity(), event);
    }

    private void checkEntityLimit(Entity spawned, org.bukkit.event.Cancellable event) {
        boolean isAnimal = spawned instanceof Animals;
        boolean isMonster = spawned instanceof Monster;

        if (!isAnimal && !isMonster) return;

        Chunk chunk = spawned.getLocation().getChunk();
        int count = 0;
        int max = isAnimal ? getMaxAnimals() : getMaxMonsters();

        for (Entity e : chunk.getEntities()) {
            if (isAnimal && e instanceof Animals) count++;
            else if (isMonster && e instanceof Monster) count++;
        }

        if (count >= max) {
            event.setCancelled(true);
        }
    }

    private static class ChunkRedstoneData {
        long startTime;
        int count;

        ChunkRedstoneData(long startTime) {
            this.startTime = startTime;
            this.count = 0;
        }
    }
}
