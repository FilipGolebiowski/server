package gg.elcartel.paper.sector;

import gg.elcartel.paper.CorePaperPlugin;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import gg.elcartel.data.CoreData;
import gg.elcartel.data.model.ShardInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import gg.elcartel.paper.util.LegacyText;

/**
 * GUI wyboru kanalu/sektora (/ch). Listuje kanaly biezacego trybu z liczba graczy
 * (z rejestru Redis), podswietla biezacy, a klik przelacza gracza na wybrany shard
 * przez plugin-message "elcartel:switch" (proxy laczy -> handoff przenosi profil).
 */
public final class SectorMenu implements CommandExecutor, Listener {

    public static final String CHANNEL = "elcartel:switch";
    private static final long STALE_MS = 6000L;

    private final Plugin plugin;
    private final CoreData data;
    private final String mode;
    private final String selfShardId;
    private final boolean isSector;
    private final boolean isSpawnSector;

    public SectorMenu(Plugin plugin, CoreData data, String mode, String selfShardId, boolean isSector, boolean isSpawnSector) {
        this.plugin = plugin;
        this.data = data;
        this.mode = mode;
        this.selfShardId = selfShardId;
        this.isSector = isSector;
        this.isSpawnSector = isSpawnSector;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Tylko gracz.");
            return true;
        }
        if (isSector && !isSpawnSector) {
            player.sendMessage(LegacyText.legacy("&cKomendy /ch mozna uzyc tylko na spawnie."));
            return true;
        }
        open(player);
        return true;
    }

    public void open(Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            List<ShardInfo> list = new ArrayList<>();
            for (String id : data.shards().listMode(mode)) {
                ShardInfo s = data.shards().get(id);
                if (s != null && s.isFresh(now, STALE_MS)) {
                    if (s.hasSector() && !s.isSpawnSector()) {
                        continue; // zamiana tylko pomiedzy sektorami spawnowymi
                    }
                    list.add(s);
                }
            }
            list.sort((a, b) -> a.getId().compareToIgnoreCase(b.getId()));
            Bukkit.getScheduler().runTask(plugin, () -> build(player, list));
        });
    }

    private void build(Player player, List<ShardInfo> list) {
        int rows = Math.max(1, Math.min(6, (Math.max(list.size(), 1) + 8) / 9));
        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, rows * 9, noItalic(LegacyText.legacy(msg("sector.menu.title"))));
        holder.inventory = inv;

        int slot = 0;
        for (ShardInfo s : list) {
            boolean self = s.getId().equalsIgnoreCase(selfShardId);
            ItemStack item = new ItemStack(self ? Material.LIME_DYE : Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(noItalic(LegacyText.legacy(msg("sector.item.name", "channel", s.getId().toUpperCase()))));
            List<Component> lore = new ArrayList<>();
            lore.add(noItalic(LegacyText.legacy(msg("sector.item.players",
                "players", String.valueOf(s.getPlayers()), "cap", String.valueOf(s.getSoftCap())))));
            lore.add(noItalic(LegacyText.legacy(msg(self ? "sector.item.current" : "sector.item.join"))));
            meta.lore(lore);
            item.setItemMeta(meta);
            inv.setItem(slot, item);
            holder.slots.put(slot, s.getId());
            slot++;
        }
        if (list.isEmpty()) {
            ItemStack none = new ItemStack(Material.BARRIER);
            ItemMeta meta = none.getItemMeta();
            meta.displayName(noItalic(LegacyText.legacy(msg("sector.empty"))));
            none.setItemMeta(meta);
            inv.setItem(4, none);
        }
        player.openInventory(inv);
    }

    private final Map<java.util.UUID, Long> lastClick = new java.util.concurrent.ConcurrentHashMap<>();

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        long now = System.currentTimeMillis();
        long last = lastClick.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < 500L) { // 500ms cooldown for sector switching
            return;
        }
        lastClick.put(player.getUniqueId(), now);
        String target = holder.slots.get(event.getRawSlot());
        if (target == null) {
            return;
        }
        if (target.equalsIgnoreCase(selfShardId)) {
            player.sendMessage(LegacyText.legacy(msg("sector.already")));
            return;
        }
        player.closeInventory();
        player.sendMessage(LegacyText.legacy(msg("sector.switching", "channel", target.toUpperCase())));
        switchTo(player, target);
    }

    private void switchTo(Player player, String server) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            CorePaperPlugin core = (CorePaperPlugin) plugin;
            if (core.getProfileService() != null) {
                core.getProfileService().prepareHandoff(player);
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    ByteArrayDataOutput out = ByteStreams.newDataOutput();
                    out.writeUTF(server);
                    player.sendPluginMessage(plugin, CHANNEL, out.toByteArray());
                }
            });
        });
    }

    private String msg(String key, String... kv) {
        return data.messages().format(key, kv);
    }

    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }

    /** Trzyma mapowanie slot -> shardId; identyfikuje nasze inventory. */
    public static final class Holder implements InventoryHolder {
        private final Map<Integer, String> slots = new HashMap<>();
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
