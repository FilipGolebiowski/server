package gg.elcartel.mode.hub;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public final class HubSpawnManager implements Listener, CommandExecutor {

    private final HubPlugin plugin;
    private Location spawnLoc;

    public HubSpawnManager(HubPlugin plugin) {
        this.plugin = plugin;
        loadSpawn();
        
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getCommand("setspawn").setExecutor(this);
    }

    private void loadSpawn() {
        if (plugin.getConfig().contains("spawn.world")) {
            String w = plugin.getConfig().getString("spawn.world");
            double x = plugin.getConfig().getDouble("spawn.x");
            double y = plugin.getConfig().getDouble("spawn.y");
            double z = plugin.getConfig().getDouble("spawn.z");
            float yaw = (float) plugin.getConfig().getDouble("spawn.yaw");
            float pitch = (float) plugin.getConfig().getDouble("spawn.pitch");
            spawnLoc = new Location(Bukkit.getWorld(w), x, y, z, yaw, pitch);
        } else {
            spawnLoc = Bukkit.getWorlds().get(0).getSpawnLocation();
        }
    }

    public void teleportToSpawn(Player player) {
        if (spawnLoc != null && spawnLoc.getWorld() != null) {
            player.teleport(spawnLoc);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        teleportToSpawn(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (spawnLoc != null && spawnLoc.getWorld() != null) {
            event.setRespawnLocation(spawnLoc);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() != null && event.getTo().getY() < 0) {
            teleportToSpawn(event.getPlayer());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (!player.hasPermission("elcartel.admin")) {
            player.sendMessage("Brak uprawnien.");
            return true;
        }

        Location loc = player.getLocation();
        plugin.getConfig().set("spawn.world", loc.getWorld().getName());
        plugin.getConfig().set("spawn.x", loc.getX());
        plugin.getConfig().set("spawn.y", loc.getY());
        plugin.getConfig().set("spawn.z", loc.getZ());
        plugin.getConfig().set("spawn.yaw", loc.getYaw());
        plugin.getConfig().set("spawn.pitch", loc.getPitch());
        plugin.saveConfig();
        
        loadSpawn();
        player.sendMessage("Spawn huba zostal ustawiony!");
        return true;
    }
}
