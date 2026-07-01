package gg.elcartel.paper.sector;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import gg.elcartel.data.model.Position;
import gg.elcartel.data.model.ShardInfo;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import gg.elcartel.paper.profile.ProfileService;
import gg.elcartel.paper.util.LegacyText;

public final class SpawnCommand implements CommandExecutor {

    private final Plugin plugin;
    private final ProfileService profileService;
    private final ShardInfo info;

    public SpawnCommand(Plugin plugin, ProfileService profileService, ShardInfo info) {
        this.plugin = plugin;
        this.profileService = profileService;
        this.info = info;
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Tylko dla graczy.");
            return true;
        }

        World w = Bukkit.getWorld(plugin.getConfig().getString("spawn.world", "world"));
        if (w == null) {
            player.sendMessage(LegacyText.legacy("&cBrak skonfigurowanego swiata spawnu (spawn.world w config.yml)."));
            return true;
        }

        double x = plugin.getConfig().getDouble("spawn.x", 0.5);
        double y = plugin.getConfig().getDouble("spawn.y", 65.0);
        double z = plugin.getConfig().getDouble("spawn.z", 0.5);
        float yaw = (float) plugin.getConfig().getDouble("spawn.yaw", 0.0);
        float pitch = (float) plugin.getConfig().getDouble("spawn.pitch", 0.0);

        Location spawnLoc = new Location(w, x, y, z, yaw, pitch);

        // Zawsze aktualizujemy pozycje w profilu na spawn, by miec pewnosc, 
        // ze po przelaczeniu miedzy serwerami ProfileService uzyje tej lokacji (lub bezposrednio przy teleporcie na serwerze).
        var profile = profileService.get(player.getUniqueId());
        if (profile != null) {
            profile.getPositions().put(w.getName(), new Position(x, y, z, yaw, pitch));
        }

        if (info.isSpawnSector()) {
            player.teleport(spawnLoc);
            player.sendMessage(LegacyText.legacy("&aZostales przeteleportowany na spawn."));
        } else {
            player.sendMessage(LegacyText.legacy("&aTeleportacja na spawn... (ladowanie sektora)"));
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("SPAWN:" + info.getMode());
            player.sendPluginMessage(plugin, SectorMenu.CHANNEL, out.toByteArray());
        }

        return true;
    }
}
