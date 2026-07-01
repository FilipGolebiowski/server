package gg.elcartel.mode.hub;

import gg.elcartel.data.api.CoreApi;
import gg.elcartel.paper.util.LegacyText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ModeSelectorMenu implements Listener {

    private final HubPlugin plugin;
    private final Map<java.util.UUID, Long> lastClick = new ConcurrentHashMap<>();

    public ModeSelectorMenu(HubPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player player) {
        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, 27, noItalic(LegacyText.legacy("&8Wybierz Tryb")));
        holder.inventory = inv;

        ItemStack survival = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta surMeta = survival.getItemMeta();
        surMeta.displayName(noItalic(LegacyText.legacy("&aSurvival")));
        List<Component> surLore = new ArrayList<>();
        surLore.add(noItalic(LegacyText.legacy("&7Kliknij, aby dolaczyc do Survival!")));
        surMeta.lore(surLore);
        survival.setItemMeta(surMeta);
        inv.setItem(11, survival);
        holder.slots.put(11, "survival");

        ItemStack oceanblock = new ItemStack(Material.WATER_BUCKET);
        ItemMeta oceanMeta = oceanblock.getItemMeta();
        oceanMeta.displayName(noItalic(LegacyText.legacy("&bOceanBlock")));
        List<Component> oceanLore = new ArrayList<>();
        oceanLore.add(noItalic(LegacyText.legacy("&7Kliknij, aby dolaczyc do OceanBlock!")));
        oceanMeta.lore(oceanLore);
        oceanblock.setItemMeta(oceanMeta);
        inv.setItem(15, oceanblock);
        holder.slots.put(15, "oceanblock");

        player.openInventory(inv);
    }

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
        if (now - last < 500L) {
            return;
        }
        lastClick.put(player.getUniqueId(), now);

        String target = holder.slots.get(event.getRawSlot());
        if (target == null) {
            return;
        }

        player.closeInventory();
        player.sendMessage(LegacyText.legacy("&aŁączenie z trybem " + target + "..."));
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            gg.elcartel.paper.CorePaperPlugin core = (gg.elcartel.paper.CorePaperPlugin) Bukkit.getPluginManager().getPlugin("core-paper");
            if (core != null && core.getProfileService() != null) {
                core.getProfileService().prepareHandoff(player);
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    @SuppressWarnings("UnstableApiUsage")
                    com.google.common.io.ByteArrayDataOutput out = com.google.common.io.ByteStreams.newDataOutput();
                    out.writeUTF("SPAWN:" + target);
                    player.sendPluginMessage(plugin, "elcartel:switch", out.toByteArray());
                }
            });
        });
    }

    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }

    public static final class Holder implements InventoryHolder {
        private final Map<Integer, String> slots = new java.util.HashMap<>();
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
