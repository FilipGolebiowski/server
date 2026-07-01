package gg.elcartel.paper.sector;

import gg.elcartel.paper.CorePaperPlugin;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import gg.elcartel.data.CoreData;
import gg.elcartel.data.model.ShardInfo;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import gg.elcartel.paper.util.LegacyText;

/**
 * System sektorow (na razie survival). Swiat podzielony na kwadraty rozmiaru {@code size},
 * kazdy sektor (sx,sz) obslugiwany przez >=1 shard (instancje). Na skraju sektora stoi bariera
 * (worldborder); proba jej przekroczenia przenosi gracza na sasiedni sektor, z zachowaniem
 * globalnych wspolrzednych (ten sam seed -> ciagly teren). Gdy docelowy sektor jest pelny,
 * gracz trafia na inna instancje tego samego sektora (to samo miejsce na mapie).
 */
public final class SectorService implements Listener {

    private static final int EAST = 1;
    private static final int WEST = 2;
    private static final int SOUTH = 3;
    private static final int NORTH = 4;
    private static final double NUDGE = 2.0;     // ile blokow w glab sasiada
    private static final double BORDER_BUFFER = 4.0;
    private static final long COOLDOWN_MS = 3000L;
    private static final long STALE_MS = 6000L;
    private static final long DEST_TTL_MS = 12000L;

    private final Plugin plugin;
    private final CoreData data;
    private final String mode;
    private final String shardId;
    private final int sectorX;
    private final int sectorZ;
    private final int size;
    private final String world;
    private final double minX;
    private final double minZ;
    private final double maxX;
    private final double maxZ;
    private final ConcurrentHashMap<UUID, Long> cooldown = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, org.bukkit.boss.BossBar> borderBars = new ConcurrentHashMap<>();
    private static final double BOSSBAR_DISTANCE = 20.0;

    public SectorService(Plugin plugin, CoreData data, String mode, String shardId,
                         int sectorX, int sectorZ, int size, String world) {
        this.plugin = plugin;
        this.data = data;
        this.mode = mode;
        this.shardId = shardId;
        this.sectorX = sectorX;
        this.sectorZ = sectorZ;
        this.size = size;
        this.world = world;
        this.minX = (double) sectorX * size - (size / 2.0);
        this.minZ = (double) sectorZ * size - (size / 2.0);
        this.maxX = minX + size;
        this.maxZ = minZ + size;
    }

    /** Ustawia barriere (worldborder) na granicach sektora. */
    public void start() {
        World w = Bukkit.getWorld(world);
        if (w == null) {
            plugin.getLogger().warning("Sektor: brak swiata '" + world + "' - bariera nieustawiona.");
            return;
        }
        WorldBorder b = w.getWorldBorder();
        b.setCenter(minX + size / 2.0, minZ + size / 2.0);
        b.setSize(size + 2.0 * BORDER_BUFFER);
        b.setWarningDistance(3);
        b.setDamageAmount(0.0);
        plugin.getLogger().info("Sektor (" + sectorX + "," + sectorZ + ") rozmiar " + size
            + " -> region X[" + (long) minX + ".." + (long) maxX + "] Z[" + (long) minZ + ".." + (long) maxZ + "].");
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        Location from = event.getFrom();
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) {
            return; // bez zmiany bloku - nic nie liczymy
        }
        if (to.getWorld() == null || !to.getWorld().getName().equals(world)) {
            return;
        }
        double x = to.getX();
        double z = to.getZ();
        int dir = 0;
        if (x >= maxX) {
            dir = EAST;
        } else if (x <= minX) {
            dir = WEST;
        } else if (z >= maxZ) {
            dir = SOUTH;
        } else if (z <= minZ) {
            dir = NORTH;
        }
        if (dir != 0) {
            Player p = event.getPlayer();
            long now = System.currentTimeMillis();
            Long last = cooldown.get(p.getUniqueId());
            if (last == null || now - last >= COOLDOWN_MS) {
                cooldown.put(p.getUniqueId(), now);
                beginTransfer(p, dir, to);
            }
            return;
        }

        Player p = event.getPlayer();
        double distE = maxX - x;
        double distW = x - minX;
        double distS = maxZ - z;
        double distN = z - minZ;
        double minDist = Math.min(Math.min(distE, distW), Math.min(distS, distN));
        
        if (minDist <= BOSSBAR_DISTANCE && minDist >= 0) {
            org.bukkit.boss.BossBar bar = borderBars.computeIfAbsent(p.getUniqueId(), k -> {
                org.bukkit.boss.BossBar b = Bukkit.createBossBar("", org.bukkit.boss.BarColor.RED, org.bukkit.boss.BarStyle.SOLID);
                b.addPlayer(p);
                return b;
            });
            int blocks = (int) minDist;
            bar.setTitle(org.bukkit.ChatColor.translateAlternateColorCodes('&', "&cZbliżasz się do sektora: &e" + blocks + " &cbloków"));
            bar.setProgress(Math.max(0.0, Math.min(1.0, 1.0 - (minDist / BOSSBAR_DISTANCE))));
        } else {
            removeBossBar(p);
        }

    }

    private void beginTransfer(Player p, int dir, Location to) {
        int nx = sectorX;
        int nz = sectorZ;
        double tx = to.getX();
        double tz = to.getZ();
        final double ty = to.getY();
        final float yaw = to.getYaw();
        final float pitch = to.getPitch();
        switch (dir) {
            case EAST -> { nx++; tx = maxX + NUDGE; }
            case WEST -> { nx--; tx = minX - NUDGE; }
            case SOUTH -> { nz++; tz = maxZ + NUDGE; }
            case NORTH -> { nz--; tz = minZ - NUDGE; }
            default -> { }
        }
        final UUID id = p.getUniqueId();
        final double fx = tx;
        final double fz = tz;
        final int gnx = nx;
        final int gnz = nz;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ShardInfo target = chooseInstance(gnx, gnz);
            if (target == null) {
                cooldown.remove(id);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (p.isOnline()) {
                        p.sendMessage(LegacyText.legacy(data.messages().format("sector.edge")));
                        pushBack(p, dir);
                    }
                });
                return;
            }
            String payload = world + "|" + fx + "|" + ty + "|" + fz + "|" + yaw + "|" + pitch;
            data.sectorDest().set(id, payload, DEST_TTL_MS);
            CorePaperPlugin core = (CorePaperPlugin) plugin;
            if (core.getProfileService() != null) {
                core.getProfileService().prepareHandoff(p);
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) {
                    ByteArrayDataOutput out = ByteStreams.newDataOutput();
                    out.writeUTF(target.getId());
                    p.sendPluginMessage(plugin, SectorMenu.CHANNEL, out.toByteArray());
                    p.sendActionBar(LegacyText.legacy("&aŁączenie..."));
                }
            });
        });
    }

    /** Najmniej obciazona instancja danego sektora (preferuje joinable; null gdy brak). */
    private ShardInfo chooseInstance(int nx, int nz) {
        long now = System.currentTimeMillis();
        ShardInfo best = null;
        int bestPlayers = Integer.MAX_VALUE;
        boolean bestJoinable = false;
        for (String id : data.shards().listMode(mode)) {
            ShardInfo s = data.shards().get(id);
            if (s == null || !s.hasSector() || s.getSectorX() != nx || s.getSectorZ() != nz) {
                continue;
            }
            if (!s.isFresh(now, STALE_MS)) {
                continue;
            }
            boolean joinable = s.isJoinable(now, STALE_MS);
            if (best == null || (joinable && !bestJoinable)
                || (joinable == bestJoinable && s.getPlayers() < bestPlayers)) {
                best = s;
                bestPlayers = s.getPlayers();
                bestJoinable = joinable;
            }
        }
        return best;
    }

    private void pushBack(Player p, int dir) {
        Location l = p.getLocation();
        switch (dir) {
            case EAST -> l.setX(maxX - 2);
            case WEST -> l.setX(minX + 2);
            case SOUTH -> l.setZ(maxZ - 2);
            case NORTH -> l.setZ(minZ + 2);
            default -> { }
        }
        p.teleport(l);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        applyDest(event.getPlayer(), id);
    }

    /** Po wejsciu: jesli czeka pozycja po transferze, teleportuj (nadpisuje pozycje z profilu). */
    private void applyDest(Player p, UUID id) {
        if (!p.isOnline()) {
            return;
        }
        String payload = data.sectorDest().take(id);
        if (payload == null) {
            return;
        }
        String[] a = payload.split("\\|");
        if (a.length < 6) {
            return;
        }
        try {
            World w = Bukkit.getWorld(a[0]);
            if (w == null) {
                w = p.getWorld();
            }
            p.teleport(new Location(w,
                Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]),
                Float.parseFloat(a[4]), Float.parseFloat(a[5])));
        } catch (NumberFormatException ignored) {
        }
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        removeBossBar(event.getPlayer());
    }

    private void removeBossBar(Player p) {
        org.bukkit.boss.BossBar bar = borderBars.remove(p.getUniqueId());
        if (bar != null) {
            bar.removeAll();
        }
    }
}
