package gg.elcartel.paper.profile;

import gg.elcartel.data.CoreData;
import gg.elcartel.data.model.ModeProfile;
import gg.elcartel.data.model.Position;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import gg.elcartel.paper.sector.SectorService;
import gg.elcartel.paper.util.InventorySerializer;
import gg.elcartel.paper.util.LegacyText;

/**
 * Handoff PER-TRYB (BLUEPRINT-modes sek. 2). Ten shard ma jeden tryb (ELCARTEL_MODE);
 * operuje na ModeProfile(uuid, mode), lock/sygnal kluczowane po "<uuid>:<mode>".
 * Dane survival i oceanblock sa wiec rozdzielone (osobne saldo/eq/staty).
 * Konto global (PlayerAccount) jest jedno - tylko odswiezane (touch).
 * Operacje Redis/Mongo asynchronicznie; odczyt/zapis Bukkit na watku glownym.
 */
public final class ProfileService {

    private static final long LOCK_TTL_MS = 15000L;
    private static final long ACQUIRE_TIMEOUT_MS = 6000L;
    private static final long FRESH_WAIT_MS = 3000L;
    private static final long LEASE_PERIOD_TICKS = 100L;
    private static final long AUTOSAVE_PERIOD_TICKS = 1200L;
    private static final long HANDOFF_TTL_MS = 30000L;

    private final JavaPlugin plugin;
    private final CoreData data;
    private final String shardId;
    private final String mode;
    private final Map<UUID, ModeProfile> online = new ConcurrentHashMap<>();
    private final Cache<UUID, ModeProfile> preloaded = CacheBuilder.newBuilder().expireAfterWrite(30, TimeUnit.SECONDS).build();
    private final Cache<UUID, Boolean> pendingSectorDest = CacheBuilder.newBuilder().expireAfterWrite(30, TimeUnit.SECONDS).build();
    private BukkitTask leaseTask;
    private BukkitTask autosaveTask;
    private final java.util.Set<UUID> transferring = ConcurrentHashMap.newKeySet();

    public ProfileService(JavaPlugin plugin, CoreData data, String shardId, String mode) {
        this.plugin = plugin;
        this.data = data;
        this.shardId = shardId;
        this.mode = mode;
    }

    private String subject(UUID id) {
        return id + ":" + mode;
    }

    public ModeProfile get(UUID id) {
        return online.get(id);
    }
    
    public boolean isTransferring(UUID id) {
        return transferring.contains(id);
    }

    public void start() {
        leaseTask = plugin.getServer().getScheduler()
            .runTaskTimerAsynchronously(plugin, this::renewLocks, LEASE_PERIOD_TICKS, LEASE_PERIOD_TICKS);
        autosaveTask = plugin.getServer().getScheduler()
            .runTaskTimer(plugin, this::autosaveAll, AUTOSAVE_PERIOD_TICKS, AUTOSAVE_PERIOD_TICKS);
            
        data.messenger().subscribe("core:handoff:force", (ch, msg) -> {
            try {
                UUID id = UUID.fromString(msg);
                Player p = plugin.getServer().getPlayer(id);
                if (p != null && online.containsKey(id)) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> prepareHandoff(p));
                }
            } catch (Exception ignored) {}
        });
    }

    public void stop() {
        if (leaseTask != null) leaseTask.cancel();
        if (autosaveTask != null) autosaveTask.cancel();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            ModeProfile profile = online.remove(player.getUniqueId());
            if (profile == null) continue;
            capture(player, profile);
            try {
                long v = data.modes().save(profile);
                data.handoff().set(subject(player.getUniqueId()), v, shardId, "", HANDOFF_TTL_MS);
                data.locks().release(subject(player.getUniqueId()), shardId);
            } catch (Exception e) {
                plugin.getLogger().severe("Blad zapisu profilu dla " + player.getName() + " podczas wylaczania: " + e.getMessage());
            }
        }
    }

    public void onPreLogin(UUID id, String name, java.net.InetAddress address) {
        String ip = address != null ? address.getHostAddress() : null;
        String subj = subject(id);
        if (!acquireWithRetry(subj)) {
            data.locks().take(subj, shardId, LOCK_TTL_MS);
            plugin.getLogger().warning("Handoff: wymuszono przejecie locka " + subj + " (timeout).");
        }
        ModeProfile profile = waitForFreshProfile(id);
        if (profile == null) {
            profile = new ModeProfile(id, mode);
        }
        data.handoff().clear(subj);
        data.handoff().clear(subj);
        data.accounts().touch(id, name, ip);
        pendingSectorDest.put(id, data.sectorDest().peek(id) != null);
        preloaded.put(id, profile);
    }

    public void onJoin(Player player) {
        UUID id = player.getUniqueId();
        ModeProfile profile = preloaded.getIfPresent(id);
        preloaded.invalidate(id);
        Boolean destObj = pendingSectorDest.getIfPresent(id);
        boolean hasDest = destObj != null && destObj;
        pendingSectorDest.invalidate(id);
        
        if (profile != null) {
            online.put(id, profile);
            applyToPlayer(player, profile, hasDest);
        } else {
            plugin.getLogger().warning("Profile not preloaded for " + player.getName());
            online.put(id, new ModeProfile(id, mode));
        }
    }

    public void onQuit(Player player) {
        UUID id = player.getUniqueId();
        transferring.remove(id);
        ModeProfile profile = online.remove(id);
        if (profile == null) {
            return;
        }
        capture(player, profile);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                long v = data.modes().save(profile);
                data.handoff().set(subject(id), v, shardId, "", HANDOFF_TTL_MS);
            } catch (Exception e) {
                plugin.getLogger().severe("Blad zapisu profilu przy wyjsciu: " + e.getMessage());
            } finally {
                data.locks().release(subject(id), shardId);
            }
        });
    }

    /** 
     * Przygotowuje profil do natychmiastowego transferu, uwalniajac locka ZANIM
     * proxy rozpocznie probe laczenia z nowym serwerem. Dzieki temu docelowy
     * serwer nie czeka 6 sekund na timeout locka starego serwera.
     */
    public void prepareHandoff(Player player) {
        UUID id = player.getUniqueId();
        ModeProfile profile = online.remove(id);
        if (profile == null) return;
        transferring.add(id);
        capture(player, profile);
        try {
            long v = data.modes().save(profile);
            data.handoff().set(subject(id), v, shardId, "", HANDOFF_TTL_MS);
            data.locks().release(subject(id), shardId);
        } catch (Exception e) {
            plugin.getLogger().warning("Blad podczas prepareHandoff: " + e.getMessage());
        }
    }

    private boolean acquireWithRetry(String subject) {
        long deadline = System.currentTimeMillis() + ACQUIRE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (data.locks().acquire(subject, shardId, LOCK_TTL_MS)) {
                return true;
            }
            if (!sleep(100L)) {
                return false;
            }
        }
        return false;
    }

    private ModeProfile waitForFreshProfile(UUID id) {
        Map<String, String> hs = data.handoff().get(subject(id));
        long want = parseLong(hs == null ? null : hs.get("version"));
        long deadline = System.currentTimeMillis() + FRESH_WAIT_MS;
        ModeProfile profile = data.modes().load(id, mode);
        while (profile != null && want > 0 && profile.getVersion() < want && System.currentTimeMillis() < deadline) {
            if (!sleep(100L)) {
                break;
            }
            profile = data.modes().load(id, mode);
        }
        return profile;
    }

    private void renewLocks() {
        for (UUID id : online.keySet()) {
            data.locks().renew(subject(id), shardId, LOCK_TTL_MS);
        }
    }

    private void autosaveAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            ModeProfile profile = online.get(player.getUniqueId());
            if (profile == null) continue;
            capture(player, profile);
            final ModeProfile snap = profile;
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> data.modes().save(snap));
        }
    }

    private void capture(Player player, ModeProfile profile) {
        profile.setInventory(InventorySerializer.toBytes(player.getInventory().getContents()));
        profile.setEnderChest(InventorySerializer.toBytes(player.getEnderChest().getContents()));
        profile.setLevel(player.getLevel());
        profile.setExp(player.getExp());
        profile.setHealth(player.getHealth());
        profile.setFoodLevel(player.getFoodLevel());
        profile.setGameMode(player.getGameMode().ordinal());
        Location l = player.getLocation();
        profile.getPositions().put(player.getWorld().getName(),
            new Position(l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()));
    }

    private void applyToPlayer(Player player, ModeProfile profile, boolean hasSectorDest) {
        byte[] inv = profile.getInventory();
        if (inv != null && inv.length > 0) {
            player.getInventory().setContents(InventorySerializer.fromBytes(inv));
        }
        byte[] ec = profile.getEnderChest();
        if (ec != null && ec.length > 0) {
            player.getEnderChest().setContents(InventorySerializer.fromBytes(ec));
        }
        player.setLevel(profile.getLevel());
        player.setExp(profile.getExp());
        player.setFoodLevel(profile.getFoodLevel());
        double hp = profile.getHealth();
        double max = 20.0;
        try { max = player.getMaxHealth(); } catch (Throwable ignored) { }
        if (hp > 0) {
            player.setHealth(Math.max(0.5, Math.min(hp, max)));
        }
        GameMode[] gameModes = GameMode.values();
        int gi = profile.getGameMode();
        player.setGameMode((gi >= 0 && gi < gameModes.length) ? gameModes[gi] : GameMode.SURVIVAL);
        
        String tpTargetStr = data.tpDest().take(player.getUniqueId());
        if (tpTargetStr != null) {
            org.bukkit.entity.Player target = org.bukkit.Bukkit.getPlayer(UUID.fromString(tpTargetStr));
            if (target != null) {
                player.teleport(target.getLocation());
                player.sendMessage(LegacyText.legacy("&aZostales przeteleportowany."));
                return;
            }
        }

        Position pos = profile.getPositions().get(player.getWorld().getName());
        if (pos != null && !hasSectorDest) {
            // pomijamy, gdy czeka transfer sektorowy - pozycje ustawi SectorService
            player.teleport(new Location(player.getWorld(),
                pos.getX(), pos.getY(), pos.getZ(), pos.getYaw(), pos.getPitch()));
        } else if (pos == null && !hasSectorDest && plugin.getConfig().getBoolean("spawn.enabled", false)) {
            org.bukkit.World w = org.bukkit.Bukkit.getWorld(plugin.getConfig().getString("spawn.world", "world"));
            if (w != null) {
                player.teleport(new Location(w,
                    plugin.getConfig().getDouble("spawn.x", 0.5),
                    plugin.getConfig().getDouble("spawn.y", 65.0),
                    plugin.getConfig().getDouble("spawn.z", 0.5),
                    (float) plugin.getConfig().getDouble("spawn.yaw", 0.0),
                    (float) plugin.getConfig().getDouble("spawn.pitch", 0.0)));
            }
        }
    }

    private static String ipOf(Player player) {
        InetSocketAddress a = player.getAddress();
        return (a != null && a.getAddress() != null) ? a.getAddress().getHostAddress() : null;
    }

    private static boolean sleep(long ms) {
        try { Thread.sleep(ms); return true; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    private static long parseLong(String v) {
        try { return v == null ? 0L : Long.parseLong(v.trim()); } catch (Exception e) { return 0L; }
    }
}
