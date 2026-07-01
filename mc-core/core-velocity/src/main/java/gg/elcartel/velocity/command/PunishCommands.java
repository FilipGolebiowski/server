package gg.elcartel.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import gg.elcartel.common.Durations;
import gg.elcartel.data.CoreData;
import gg.elcartel.data.model.PlayerAccount;
import gg.elcartel.data.model.Punishment;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import gg.elcartel.velocity.punish.PunishmentGuard;
import gg.elcartel.velocity.util.LegacyText;

/**
 * Komendy kar (proxy). Dwie rodziny:
 *  - bez sufiksu (np. /ban) -> zakres = TRYB nadawcy (lub -t &lt;tryb&gt; override),
 *  - sufiks "proxy" (np. /banproxy) -> zakres = cala SIEC.
 * Ostatni argument steruje rozgloszeniem na czacie: pokaz | cichy (domyslnie: pokaz).
 * Skladnia: /ban &lt;gracz&gt; [czas] [powod...] [pokaz|cichy]   (czas: 30m/2h/7d/perm)
 * Uprawnienia (LuckPerms / ELCARTEL_ADMINS): elcartel.ban / .mute / .kick / .warn.
 * Teksty konfigurowalne (Messages / messages.properties).
 */
public final class PunishCommands implements SimpleCommand {

    private final Object plugin;
    private final ProxyServer server;
    private final CoreData data;
    private final Set<String> bootstrapAdmins;

    public PunishCommands(Object plugin, ProxyServer server, CoreData data, Set<String> bootstrapAdmins) {
        this.plugin = plugin;
        this.server = server;
        this.data = data;
        this.bootstrapAdmins = bootstrapAdmins;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String alias = invocation.alias().toLowerCase(Locale.ROOT);
        boolean network = alias.endsWith("proxy");
        String base = network ? alias.substring(0, alias.length() - "proxy".length()) : alias;

        String perm = permFor(base);
        if (!hasAccess(source, perm)) {
            msg(source, "cmd.no-perm", "perm", perm);
            return;
        }
        String[] args = invocation.arguments();
        if (args.length < 1) {
            msg(source, "cmd.usage", "cmd", alias);
            return;
        }

        Parsed pr = parse(args);

        String scope;
        if (network) {
            scope = Punishment.NETWORK;
        } else {
            scope = (pr.modeOverride != null) ? pr.modeOverride : senderMode(source);
            if (scope == null) {
                msg(source, "cmd.no-mode", "cmd", base);
                return;
            }
        }
        String by = (source instanceof Player p) ? p.getUsername() : "Console";
        Req req = new Req(base, scope, network, pr.broadcast, pr.target, pr.expiresAt, pr.reason, by);
        server.getScheduler().buildTask(plugin, () -> run(source, req)).schedule();
    }

    private void run(CommandSource source, Req r) {
        UUID uuid = resolve(r.target);
        if (uuid == null) {
            msg(source, "cmd.target-unknown", "player", r.target);
            return;
        }
        String dur = Durations.formatUntil(r.expiresAt);
        switch (r.base) {
            case "ban" -> {
                Punishment pu = make(uuid, "BAN", r.scope, r.reason, r.by, r.expiresAt);
                data.punishments().add(pu);
                server.getPlayer(uuid).ifPresent(pl -> {
                    if (r.network || onMode(pl, r.scope)) {
                        pl.disconnect(PunishmentGuard.banScreen(data, pu));
                    }
                });
                announce(r, "ban.broadcast", uuid, dur);
                msg(source, "cmd.ban.ok", "player", r.target, "scope", r.scope, "duration", dur);
            }
            case "unban" -> {
                long n = data.punishments().pardon(uuid, "BAN", r.scope);
                msg(source, n > 0 ? "cmd.unban.ok" : "cmd.unban.none", "player", r.target, "scope", r.scope);
            }
            case "mute" -> {
                data.punishments().add(make(uuid, "MUTE", r.scope, r.reason, r.by, r.expiresAt));
                data.messenger().publish("core:punish", uuid.toString());
                server.getPlayer(uuid).ifPresent(pl -> pl.sendMessage(LegacyText.legacy(
                    data.messages().format("mute.notify", "scope", r.scope, "reason", r.reason, "duration", dur, "by", r.by))));
                announce(r, "mute.broadcast", uuid, dur);
                msg(source, "cmd.mute.ok", "player", r.target, "scope", r.scope, "duration", dur);
            }
            case "unmute" -> {
                long n = data.punishments().pardon(uuid, "MUTE", r.scope);
                data.messenger().publish("core:punish", uuid.toString());
                msg(source, n > 0 ? "cmd.unmute.ok" : "cmd.unmute.none", "player", r.target, "scope", r.scope);
            }
            case "kick" -> {
                Optional<Player> online = server.getPlayer(uuid);
                if (r.network) {
                    online.ifPresent(pl -> pl.disconnect(LegacyText.legacy(
                        data.messages().format("kick.screen", "reason", r.reason, "by", r.by))));
                    data.punishments().add(make(uuid, "KICK", r.scope, r.reason, r.by, 0));
                    announce(r, "kick.broadcast", uuid, dur);
                    msg(source, "cmd.kick.ok", "player", r.target);
                } else if (online.isPresent() && onMode(online.get(), r.scope)) {
                    online.get().disconnect(LegacyText.legacy(
                        data.messages().format("kick.screen", "reason", r.reason, "by", r.by)));
                    data.punishments().add(make(uuid, "KICK", r.scope, r.reason, r.by, 0));
                    announce(r, "kick.broadcast", uuid, dur);
                    msg(source, "cmd.kick.ok", "player", r.target);
                } else {
                    msg(source, "cmd.kick.not-here", "player", r.target, "scope", r.scope);
                }
            }
            case "warn" -> {
                data.punishments().add(make(uuid, "WARN", r.scope, r.reason, r.by, 0));
                server.getPlayer(uuid).ifPresent(pl -> pl.sendMessage(LegacyText.legacy(
                    data.messages().format("warn.notify", "by", r.by, "reason", r.reason))));
                announce(r, "warn.broadcast", uuid, dur);
                msg(source, "cmd.warn.ok", "player", r.target, "scope", r.scope);
            }
            default -> source.sendMessage(Component.text("Nieznana komenda."));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        String alias = invocation.alias().toLowerCase(Locale.ROOT);
        boolean network = alias.endsWith("proxy");
        String base = network ? alias.substring(0, alias.length() - "proxy".length()) : alias;
        
        if (args.length == 0 || args.length == 1) {
            String prefix = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
            String mode = senderMode(invocation.source());
            return server.getAllPlayers().stream()
                .filter(p -> p.getUsername().toLowerCase(Locale.ROOT).startsWith(prefix))
                .filter(p -> network || onMode(p, mode))
                .map(Player::getUsername)
                .toList();
        }
        
        if (base.equals("kick") || base.equals("warn") || base.equals("unban") || base.equals("unmute")) {
            if (args.length == 2) return List.of("<powod>");
            if (args.length >= 3) return List.of("<powod...>", "pokaz", "cichy");
        } else if (base.equals("ban") || base.equals("mute")) {
            if (args.length == 2) return List.of("<czas_np_1h_lub_perm>");
            if (args.length == 3) return List.of("<powod>");
            if (args.length >= 4) return List.of("<powod...>", "pokaz", "cichy");
        }
        
        return List.of();
    }

    /** Rozgloszenie kary do wszystkich online (gdy broadcast=true). */
    private void announce(Req r, String key, UUID uuid, String dur) {
        if (!r.broadcast) {
            return;
        }
        Component c = LegacyText.legacy(data.messages().format(key,
            "player", r.target, "scope", r.scope, "reason", r.reason, "duration", dur, "by", r.by));
        server.getAllPlayers().forEach(p -> p.sendMessage(c));
    }

    private Parsed parse(String[] args) {
        List<String> a = new ArrayList<>(Arrays.asList(args));
        String target = a.remove(0);
        String modeOverride = null;
        int ti = a.indexOf("-t");
        if (ti >= 0 && ti + 1 < a.size()) {
            modeOverride = a.get(ti + 1).toLowerCase(Locale.ROOT);
            a.remove(ti + 1);
            a.remove(ti);
        }
        boolean broadcast = true;
        if (!a.isEmpty()) {
            Boolean b = broadcastToken(a.get(a.size() - 1));
            if (b != null) {
                broadcast = b;
                a.remove(a.size() - 1);
            }
        }
        long expiresAt = 0L;
        if (!a.isEmpty()) {
            long ms = Durations.parseMs(a.get(0));
            if (ms >= 0) {
                expiresAt = (ms == 0) ? 0L : System.currentTimeMillis() + ms;
                a.remove(0);
            }
        }
        return new Parsed(target, modeOverride, expiresAt, String.join(" ", a), broadcast);
    }

    private String senderMode(CommandSource source) {
        if (source instanceof Player p) {
            return p.getCurrentServer()
                .map(s -> PunishmentGuard.modeOf(s.getServerInfo().getName()))
                .orElse(null);
        }
        return null;
    }

    private UUID resolve(String name) {
        Optional<Player> online = server.getPlayer(name);
        if (online.isPresent()) {
            return online.get().getUniqueId();
        }
        PlayerAccount acc = data.accounts().findByNameLower(name);
        return (acc != null) ? acc.getId() : null;
    }

    private static boolean onMode(Player player, String mode) {
        return player.getCurrentServer()
            .map(s -> PunishmentGuard.modeOf(s.getServerInfo().getName()))
            .filter(mode::equals)
            .isPresent();
    }

    private static Boolean broadcastToken(String t) {
        switch (t.toLowerCase(Locale.ROOT)) {
            case "pokaz": case "broadcast": case "bc": case "-b": return Boolean.TRUE;
            case "cichy": case "silent": case "-s": return Boolean.FALSE;
            default: return null;
        }
    }

    private void msg(CommandSource source, String key, String... kv) {
        source.sendMessage(LegacyText.legacy(data.messages().format(key, kv)));
    }

    private boolean hasAccess(CommandSource source, String perm) {
        if (!(source instanceof Player player)) {
            return true; // konsola proxy
        }
        if (player.hasPermission(perm)) {
            return true; // dostawca uprawnien (LuckPerms)
        }
        return bootstrapAdmins.contains(player.getUsername().toLowerCase(Locale.ROOT))
            || bootstrapAdmins.contains(player.getUniqueId().toString().toLowerCase(Locale.ROOT));
    }

    private static Punishment make(UUID uuid, String type, String scope, String reason, String by, long expiresAt) {
        Punishment p = new Punishment();
        p.setUuid(uuid);
        p.setType(type);
        p.setScope(scope);
        p.setReason(reason);
        p.setByName(by);
        p.setCreatedAt(System.currentTimeMillis());
        p.setExpiresAt(expiresAt);
        p.setActive(true);
        return p;
    }

    private static String permFor(String base) {
        switch (base) {
            case "ban": case "unban": return "elcartel.ban";
            case "mute": case "unmute": return "elcartel.mute";
            case "kick": return "elcartel.kick";
            case "warn": return "elcartel.warn";
            default: return "elcartel.staff";
        }
    }

    private static final class Parsed {
        final String target;
        final String modeOverride;
        final long expiresAt;
        final String reason;
        final boolean broadcast;
        Parsed(String target, String modeOverride, long expiresAt, String reason, boolean broadcast) {
            this.target = target;
            this.modeOverride = modeOverride;
            this.expiresAt = expiresAt;
            this.reason = reason;
            this.broadcast = broadcast;
        }
    }

    private static final class Req {
        final String base;
        final String scope;
        final boolean network;
        final boolean broadcast;
        final String target;
        final long expiresAt;
        final String reason;
        final String by;
        Req(String base, String scope, boolean network, boolean broadcast,
            String target, long expiresAt, String reason, String by) {
            this.base = base;
            this.scope = scope;
            this.network = network;
            this.broadcast = broadcast;
            this.target = target;
            this.expiresAt = expiresAt;
            this.reason = reason;
            this.by = by;
        }
    }
}
