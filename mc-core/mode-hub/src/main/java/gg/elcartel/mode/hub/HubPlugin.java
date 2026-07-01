package gg.elcartel.mode.hub;

import gg.elcartel.data.api.CoreApi;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Przyklad pluginu trybu HUB. Wgrywany TYLKO na shardy huba (templates/hub/plugins).
 * Z rdzeniem gada przez CoreApi - bez wlasnych polaczen do bazy.
 */
public final class HubPlugin extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        CoreApi api = CoreApi.get();
        if (api == null) {
            getLogger().warning("Brak CoreApi - czy core-paper ma ELCARTEL_SHARD_ID? Funkcje huba ograniczone.");
        } else {
            getLogger().info("mode-hub aktywny dla trybu '" + api.mode() + "' (shard " + api.shardId() + ").");
            
            if (getConfig().getBoolean("scoreboard.enabled", false)) {
                String title = getConfig().getString("scoreboard.title", "&f&lELCARTEL.GG");
                java.util.List<String> lines = getConfig().getStringList("scoreboard.lines");
                api.setScoreboardTemplate(title, lines);
                getLogger().info("Szablon scoreboardu zostal pomyslnie zarejestrowany w CoreApi.");
            }
        }
        
        new HubSpawnManager(this);
        ModeSelectorMenu selectorMenu = new ModeSelectorMenu(this);
        new HubItemsManager(this, selectorMenu);
        
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, "elcartel:switch");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        p.setGameMode(GameMode.ADVENTURE);
        
        String msg = getConfig().getString("join.message", "&aWitaj w hubie El Cartel! Wpisz &e/play <tryb>&a, by zagrac.");
        if (msg != null && !msg.isEmpty()) {
            p.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(msg));
        }
        
        String title = getConfig().getString("join.title", "&6&lEL CARTEL");
        String subtitle = getConfig().getString("join.subtitle", "&eWitaj na serwerze!");
        if (title != null || subtitle != null) {
            p.sendTitle(
                title != null ? org.bukkit.ChatColor.translateAlternateColorCodes('&', title) : "",
                subtitle != null ? org.bukkit.ChatColor.translateAlternateColorCodes('&', subtitle) : "",
                10, 70, 20
            );
        }
        
        String sound = getConfig().getString("join.sound", "ENTITY_PLAYER_LEVELUP");
        if (sound != null && !sound.isEmpty() && !sound.equalsIgnoreCase("none")) {
            try {
                p.playSound(p.getLocation(), org.bukkit.Sound.valueOf(sound.toUpperCase()), 1.0f, 1.0f);
            } catch (Exception ignored) {}
        }
    }
}
