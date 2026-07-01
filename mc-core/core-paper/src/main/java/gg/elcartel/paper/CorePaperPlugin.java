package gg.elcartel.paper;

import gg.elcartel.common.CoreConstants;
import gg.elcartel.data.CoreData;
import gg.elcartel.data.auth.SecretCipher;
import gg.elcartel.data.config.Config;
import gg.elcartel.data.config.MongoSettings;
import gg.elcartel.data.config.RedisSettings;
import gg.elcartel.data.model.ShardInfo;
import gg.elcartel.data.api.CoreApi;
import org.bukkit.plugin.java.JavaPlugin;
import gg.elcartel.paper.security.AntiDupeListener;
import gg.elcartel.paper.security.AntiLagListener;
import gg.elcartel.paper.security.AntiSpamListener;
import gg.elcartel.paper.security.CrashProtectionListener;
import gg.elcartel.paper.security.AntiCheatBridge;
import gg.elcartel.paper.auth.AuthCommands;
import gg.elcartel.paper.auth.AuthGate;
import gg.elcartel.paper.auth.AuthListener;
import gg.elcartel.paper.chat.ChatListener;
import gg.elcartel.paper.chat.ChatService;
import gg.elcartel.paper.chat.MuteListener;
import gg.elcartel.paper.chat.MuteService;
import gg.elcartel.paper.eco.EcoCommands;
import gg.elcartel.paper.eco.EconomyWatcher;
import gg.elcartel.paper.profile.ProfileListener;
import gg.elcartel.paper.profile.ProfileService;
import gg.elcartel.paper.sector.SectorMenu;
import gg.elcartel.paper.sector.SectorService;
import gg.elcartel.paper.sector.ShardService;
import gg.elcartel.paper.sector.SpawnCommand;
import gg.elcartel.paper.util.LegacyText;
import gg.elcartel.paper.integration.PlaceholderAPIExpansion;

/**
 * Plugin Paper. Per-serwer (ENV):
 *  - ELCARTEL_ROLE=limbo  -> auth-gate,
 *  - ELCARTEL_SHARD_ID    -> rejestracja sharda + heartbeat (kanal trybu).
 * Sekrety (Mongo/Redis) z elcartel.properties; identyfikatory per-serwer z ENV.
 */
public final class CorePaperPlugin extends JavaPlugin {

    private CoreData data;
    private ShardService shardService;
    private ProfileService profileService;
    private MuteService muteService;
    private gg.elcartel.paper.board.ScoreboardService scoreboardService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Config cfg = new Config();
        String role = System.getenv("ELCARTEL_ROLE");
        String shardId = System.getenv("ELCARTEL_SHARD_ID");
        boolean wantLimbo = "limbo".equalsIgnoreCase(role);
        boolean wantShard = shardId != null && !shardId.isEmpty();

        if (!wantLimbo && !wantShard) {
            getLogger().info(CoreConstants.NETWORK + " core-paper " + CoreConstants.CORE_VERSION
                + " - rola=" + role + ", brak ELCARTEL_SHARD_ID (pasywny).");
            return;
        }

        String mongoUri = cfg.get("ELCARTEL_MONGO_URI");
        String redisUri = cfg.get("ELCARTEL_REDIS_URI");
        if (mongoUri == null || redisUri == null) {
            getLogger().warning("core-paper: brak konfiguracji (elcartel.properties lub ENV) - core WYLACZONY.");
            return;
        }
        String mongoDb = cfg.get("ELCARTEL_MONGO_DB", "elcartel");
        this.data = new CoreData(new MongoSettings(mongoUri, mongoDb), new RedisSettings(redisUri));

        this.scoreboardService = new gg.elcartel.paper.board.ScoreboardService(this, data, shardId);
        scoreboardService.start();
        getServer().getPluginManager().registerEvents(scoreboardService, this);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PlaceholderAPIExpansion(data).register();
            getLogger().info("Zarejestrowano rozszerzenie PlaceholderAPI (elcartel_*)");
        }

        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            getServer().getServicesManager().register(net.milkbowl.vault.economy.Economy.class, 
                new gg.elcartel.paper.eco.VaultEconomyProvider(this, data), this, org.bukkit.plugin.ServicePriority.Highest);
            getLogger().info("Zarejestrowano VaultEconomyProvider jako silnik ekonomii!");
        }

        if (wantLimbo) {
            SecretCipher cipher = null;
            String totpKey = cfg.get("ELCARTEL_TOTP_KEY");
            if (totpKey != null && !totpKey.isEmpty()) {
                cipher = SecretCipher.fromBase64(totpKey);
            }
            AuthGate gate = new AuthGate(this, data, cipher);
            getServer().getPluginManager().registerEvents(new AuthListener(gate), this);
            AuthCommands commands = new AuthCommands(gate);
            bind("register", commands);
            bind("login", commands);
            bind("otp", commands);
            getServer().getMessenger().registerOutgoingPluginChannel(this, "elcartel:auth");
            getLogger().info(CoreConstants.NETWORK + " core-paper limbo - auth-gate aktywny.");
        }

        if (wantShard) {
            String mode = envOr("ELCARTEL_MODE", "game");
            String addr = envOr("ELCARTEL_ADDR", "127.0.0.1:" + getServer().getPort());
            int softCap = parseInt(System.getenv("ELCARTEL_SOFTCAP"), 180);
            int hardCap = parseInt(System.getenv("ELCARTEL_HARDCAP"), 200);
            ShardInfo info = new ShardInfo(shardId, mode, addr, softCap, hardCap);
            String secX = System.getenv("ELCARTEL_SECTOR_X");
            String secZ = System.getenv("ELCARTEL_SECTOR_Z");
            boolean sectorShard = secX != null && !secX.isEmpty() && secZ != null && !secZ.isEmpty();
            int sectorX = parseInt(secX, 0);
            int sectorZ = parseInt(secZ, 0);
            boolean spawnSector = "true".equalsIgnoreCase(System.getenv("ELCARTEL_SECTOR_SPAWN")) || (sectorX == 0 && sectorZ == 0);
            int sectorSize = parseInt(System.getenv("ELCARTEL_SECTOR_SIZE"), 1000);
            String sectorWorld = envOr("ELCARTEL_WORLD", "world");
            if (sectorShard) {
                info.setSector(sectorX, sectorZ);
                info.setSpawnSector(spawnSector);
            }
            CoreApi.set(new CoreApi(data, mode, shardId)); // udostepnij API pluginom trybow
            this.shardService = new ShardService(this, data, info);
            shardService.start();
            this.profileService = new ProfileService(this, data, shardId, mode);
            profileService.start();
            getServer().getPluginManager().registerEvents(new ProfileListener(profileService), this);
            getServer().getPluginManager().registerEvents(new AntiDupeListener(profileService, this), this);
            getServer().getPluginManager().registerEvents(new AntiSpamListener(this), this);
            getServer().getPluginManager().registerEvents(new CrashProtectionListener(this), this);
            getServer().getPluginManager().registerEvents(new AntiLagListener(this), this);
            if (getServer().getPluginManager().getPlugin("Vault") != null) {
                try {
                    EconomyWatcher economyWatcher = new EconomyWatcher(this);
                    economyWatcher.init();
                    
                    EcoCommands eco = new EcoCommands(this, data, mode);
                    bind("balance", eco);
                    bind("pay", eco);
                    bind("eco", eco);
                } catch (Throwable t) {
                    getLogger().warning("Blad ladowania modulow ekonomii: " + t.getMessage());
                }
            }
            AntiCheatBridge ac = new AntiCheatBridge(this, data);
            bind("ac-punish", ac);
            getServer().getPluginManager().registerEvents(ac, this);
            this.muteService = new MuteService(this, data, mode);
            muteService.start();
            getServer().getPluginManager().registerEvents(new MuteListener(muteService), this);
            getServer().getMessenger().registerOutgoingPluginChannel(this, SectorMenu.CHANNEL);
        
        getServer().getMessenger().registerIncomingPluginChannel(this, "elcartel:tp", (channel, player, message) -> {
            try {
                com.google.common.io.ByteArrayDataInput in = com.google.common.io.ByteStreams.newDataInput(message);
                java.util.UUID sourceId = java.util.UUID.fromString(in.readUTF());
                java.util.UUID targetId = java.util.UUID.fromString(in.readUTF());
                org.bukkit.entity.Player source = getServer().getPlayer(sourceId);
                org.bukkit.entity.Player target = getServer().getPlayer(targetId);
                if (source != null && target != null) {
                    source.teleport(target.getLocation());
                    source.sendMessage(LegacyText.legacy("&aZostales przeteleportowany."));
                }
            } catch (Exception ignored) {}
        });
            SectorMenu sectorMenu = new SectorMenu(this, data, mode, shardId, sectorShard, spawnSector);
            getServer().getPluginManager().registerEvents(sectorMenu, this);
            bind("ch", sectorMenu);

            ChatService chatService = new ChatService(this, data, mode);
            chatService.start();
            getServer().getPluginManager().registerEvents(new ChatListener(chatService), this);
            bind("spawn", new SpawnCommand(this, profileService, info));
            if (sectorShard) {
                SectorService sectorService = new SectorService(this, data, mode, shardId, sectorX, sectorZ, sectorSize, sectorWorld);
                sectorService.start();
                getServer().getPluginManager().registerEvents(sectorService, this);
                getLogger().info(CoreConstants.NETWORK + " sektor (" + sectorX + "," + sectorZ + ") aktywny na " + shardId + ".");
            }
            getLogger().info(CoreConstants.NETWORK + " core-paper shard " + shardId
                + " (" + mode + ", soft=" + softCap + ") - heartbeat aktywny.");
        }

        if (cfg.loadedFrom() != null) {
            getLogger().info("core-paper: konfiguracja z " + cfg.loadedFrom());
        }
    }

    @Override
    public void onDisable() {
        if (scoreboardService != null) {
            scoreboardService.stop();
        }
        if (profileService != null) {
            profileService.stop();
        }
        if (shardService != null) {
            shardService.stop();
        }
        if (data != null) {
            data.close();
        }
    }

    public ProfileService getProfileService() {
        return profileService;
    }

    private void bind(String name, org.bukkit.command.CommandExecutor executor) {
        if (getCommand(name) != null) {
            getCommand(name).setExecutor(executor);
        }
    }

    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static int parseInt(String v, int def) {
        try { return v == null ? def : Integer.parseInt(v.trim()); } catch (Exception e) { return def; }
    }
}
