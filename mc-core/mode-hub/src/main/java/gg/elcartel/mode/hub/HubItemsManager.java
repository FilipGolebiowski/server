package gg.elcartel.mode.hub;

import gg.elcartel.paper.util.LegacyText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.event.block.Action;

public final class HubItemsManager implements Listener {

    private final HubPlugin plugin;
    private final ModeSelectorMenu selectorMenu;

    public HubItemsManager(HubPlugin plugin, ModeSelectorMenu selectorMenu) {
        this.plugin = plugin;
        this.selectorMenu = selectorMenu;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            
            p.getInventory().clear();
            p.getInventory().setArmorContents(null);
            
            ItemStack compass = new ItemStack(Material.COMPASS);
            ItemMeta compassMeta = compass.getItemMeta();
            compassMeta.displayName(noItalic(LegacyText.legacy("&aWybór Trybu")));
            compass.setItemMeta(compassMeta);
            
            ItemStack star = new ItemStack(Material.NETHER_STAR);
            ItemMeta starMeta = star.getItemMeta();
            starMeta.displayName(noItalic(LegacyText.legacy("&eWybór Kanału")));
            star.setItemMeta(starMeta);

            p.getInventory().setItem(0, compass);
            p.getInventory().setItem(8, star);
        }, 5L); // 5 ticks delay to ensure ProfileListener has finished loading inventory
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL) return;
        if (event.getItem() == null) return;
        
        Player p = event.getPlayer();
        if (event.getItem().getType() == Material.COMPASS) {
            selectorMenu.open(p);
            event.setCancelled(true);
        } else if (event.getItem().getType() == Material.NETHER_STAR) {
            p.performCommand("ch");
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked().hasPermission("elcartel.admin") && event.getWhoClicked().getGameMode() == org.bukkit.GameMode.CREATIVE) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (event.getPlayer().hasPermission("elcartel.admin") && event.getPlayer().getGameMode() == org.bukkit.GameMode.CREATIVE) {
            return;
        }
        event.setCancelled(true);
    }
    
    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        event.setCancelled(true);
    }

    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }
}
