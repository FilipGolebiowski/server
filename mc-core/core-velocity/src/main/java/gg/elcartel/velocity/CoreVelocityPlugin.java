package gg.elcartel.velocity;

import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import gg.elcartel.common.CoreConstants;
import gg.elcartel.data.CoreData;
import gg.elcartel.data.config.Config;
import gg.elcartel.data.config.MongoSettings;
import gg.elcartel.data.config.RedisSettings;
import gg.elcartel.data.model.ShardInfo;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.UUID;
import gg.elcartel.velocity.auth.AuthGateway;
import gg.elcartel.velocity.auth.AuthGuard;
import gg.elcartel.velocity.auth.AuthState;
import gg.elcartel.velocity.command.ChannelsCommand;
import gg.elcartel.velocity.command.LobbyCommand;
import gg.elcartel.velocity.command.PlayCommand;
import gg.elcartel.velocity.command.PunishCommands;
import gg.elcartel.velocity.command.TpCommand;
import gg.elcartel.velocity.punish.PunishmentGuard;
import gg.elcartel.velocity.shard.ShardRouter;
import gg.elcartel.velocity.shard.ShardWatcher;

/** Plugin Velocity: gateway auth + guard + routing (kanaly + hub po autoryzacji). */
@Plugin(
    id = "core-velocity",
    name = "CoreVelocity",
    version = CoreConstants.CORE_VERSION,
    description = "El Cartel core (Velocity) - gateway auth, guard, routing",
    authors = {"ElCartel"}
)
public final class CoreVelocityPlugin {

    private final ProxyServer server;
    private final Logger logger;
    private static final MinecraftChannelIdentifier SWITCH = MinecraftChannelIdentifier.from(CoreConstants.CHANNEL_SWITCH);
    private static final MinecraftChannelIdentifier AUTH = MinecraftChannelIdentifier.from(CoreConstants.CHANNEL_AUTH);
    public static final MinecraftChannelIdentifier TP_CHANNEL = MinecraftChannelIdentifier.from(CoreConstants.CHANNEL_TP);
    private final AuthState authState = new AuthState();
    private CoreData data;
    private ShardRouter router;
    private ShardWatcher watcher;
    private String hubMode = "hub";
    private String hubFallback = "";

    @Inject
    public CoreVelocityPlugin(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        Config cfg = new Config();
        String mongoUri = cfg.get("ELCARTEL_MONGO_URI");
        String redisUri = cfg.get("ELCARTEL_REDIS_URI");
        if (mongoUri == null || redisUri == null) {
            logger.warn("core-velocity: brak konfiguracji (elcartel.properties lub ENV ELCARTEL_MONGO_URI/REDIS_URI) - gateway WYLACZONY.");
            return;
        }
        String mongoDb = cfg.get("ELCARTEL_MONGO_DB", "elcartel");
        String limbo = cfg.get("ELCARTEL_LIMBO", "limbo");
        this.hubMode = cfg.get("ELCARTEL_HUB_MODE", "hub");
        this.hubFallback = cfg.get("ELCARTEL_HUB", "");

        this.data = new CoreData(new MongoSettings(mongoUri, mongoDb), new RedisSettings(redisUri));
        this.data.accounts().ensureIndexes();
        this.data.punishments().ensureIndexes();
        this.router = new ShardRouter(data.shards());
        this.watcher = new ShardWatcher(server, data.shards());
        this.watcher.start(this);
        server.getChannelRegistrar().register(SWITCH, AUTH, TP_CHANNEL);

        server.getEventManager().register(this, new AuthGateway(server, data, limbo));
        server.getEventManager().register(this, new AuthGuard(server, authState, limbo));
        server.getEventManager().register(this, new PunishmentGuard(server, data));

        CommandManager commands = server.getCommandManager();
        commands.register(commands.metaBuilder("play").build(), new PlayCommand(server, router, watcher));
        commands.register(commands.metaBuilder("lobby").aliases("hub").build(), new LobbyCommand(server, router, watcher));
        commands.register(commands.metaBuilder("channels").aliases("kanaly").build(), new ChannelsCommand(server, router, watcher));
        commands.register(commands.metaBuilder("tp").build(), new TpCommand(server, data));
        java.util.Set<String> admins = new java.util.HashSet<>();
        for (String a : cfg.get("ELCARTEL_ADMINS", "").split(",")) {
            String trimmed = a.trim().toLowerCase(java.util.Locale.ROOT);
            if (!trimmed.isEmpty()) {
                admins.add(trimmed);
            }
        }
        PunishCommands punish = new PunishCommands(this, server, data, admins);
        for (String c : new String[]{"ban", "banproxy", "unban", "unbanproxy", "mute", "muteproxy", "unmute", "unmuteproxy", "kick", "kickproxy", "warn", "warnproxy"}) {
            commands.register(commands.metaBuilder(c).build(), punish);
        }

        if (cfg.loadedFrom() != null) {
            logger.info("core-velocity: konfiguracja z {}", cfg.loadedFrom());
        }
        logger.info("{} core-velocity {} - gateway + guard + routing aktywny (limbo={}, hub-mode={}).",
            CoreConstants.NETWORK, CoreConstants.CORE_VERSION, limbo, hubMode);
    }

    /** Backend prosi o przelaczenie gracza na inny shard (GUI /ch / transfer sektora). */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (event.getIdentifier().equals(AUTH)) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            try {
                String uuidStr = ByteStreams.newDataInput(event.getData()).readUTF();
                onAuthSuccess(uuidStr);
            } catch (RuntimeException ex) {
                logger.warn("Blad dekodowania payloadu na kanale AUTH: {}", ex.getMessage());
            }
            return;
        }
        if (!event.getIdentifier().equals(SWITCH)) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection conn)) {
            logger.warn("Odrzucono wiadomosc SWITCH, zrodlo to: {}", event.getSource().getClass().getName());
            return;
        }
        String target;
        try {
            target = ByteStreams.newDataInput(event.getData()).readUTF();
            logger.info("Otrzymano wiadomosc SWITCH od gracza {}, target={}", conn.getPlayer().getUsername(), target);
        } catch (RuntimeException ex) {
            logger.warn("Blad dekodowania payloadu na kanale SWITCH: {}", ex.getMessage());
            return;
        }
        Player player = conn.getPlayer();
        if (target.startsWith("SPAWN:")) {
            String mode = target.substring(6);
            Optional<ShardInfo> best = router.chooseLeastLoaded(mode);
            if (best.isPresent()) {
                server.getServer(best.get().getId()).ifPresent(rs -> player.createConnectionRequest(rs).fireAndForget());
            } else {
                player.sendMessage(Component.text("Brak wolnych kanalow spawnowych dla trybu " + mode + ". Sprobuj za chwile."));
            }
            return;
        }
        server.getServer(target).ifPresent(rs -> player.createConnectionRequest(rs).fireAndForget());
    }

    private void onAuthSuccess(String uuidStr) {
        try {
            UUID uuid = UUID.fromString(uuidStr.trim());
            authState.markAuthed(uuid); // oznacz PRZED przerzutem, by guard wpuscil na hub
            server.getPlayer(uuid).ifPresent(this::sendToHub);
        } catch (RuntimeException ignored) {
            // niepoprawny uuid w wiadomosci
        }
    }

    /** Po autoryzacji: najmniej obciazony shard trybu hub; fallback statyczny; inaczej komunikat. */
    private void sendToHub(Player player) {
        Optional<ShardInfo> best = router.chooseLeastLoaded(hubMode);
        if (best.isPresent()) {
            player.createConnectionRequest(watcher.ensure(best.get())).fireAndForget();
            return;
        }
        if (hubFallback != null && !hubFallback.isEmpty()) {
            Optional<RegisteredServer> fallback = server.getServer(hubFallback);
            if (fallback.isPresent()) {
                player.createConnectionRequest(fallback.get()).fireAndForget();
                return;
            }
        }
        player.sendMessage(Component.text("Brak dostepnego huba - sprobuj za chwile."));
    }
}
