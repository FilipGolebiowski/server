package gg.elcartel.velocity.punish;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import gg.elcartel.common.Durations;
import gg.elcartel.data.CoreData;
import gg.elcartel.data.model.Punishment;
import net.kyori.adventure.text.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import gg.elcartel.velocity.util.LegacyText;

/**
 * Egzekwowanie kar na proxy:
 *  - LoginEvent: aktywny ban NETWORK -> odmowa wejscia (cala siec),
 *  - ServerPreConnect: aktywny ban danego TRYBU -> blokada wejscia na shard tego trybu.
 * Tryb shardu wyprowadzamy z nazwy serwera (np. survival-1 -> survival; limbo/statyczne pomijamy).
 * Teksty z konfigurowalnych Messages (kody '&').
 */
public final class PunishmentGuard {

    private static final Pattern SHARD = Pattern.compile("^(.+)-\\d+$");

    private final ProxyServer server;
    private final CoreData data;

    public PunishmentGuard(ProxyServer server, CoreData data) {
        this.server = server;
        this.data = data;
    }

    @Subscribe(order = PostOrder.LAST)
    public EventTask onLogin(LoginEvent event) {
        return EventTask.async(() -> {
            Punishment ban = data.punishments().active(event.getPlayer().getUniqueId(), "BAN", Punishment.NETWORK);
            if (ban != null) {
                event.setResult(ResultedEvent.ComponentResult.denied(banScreen(data, ban)));
            }
        });
    }

    @Subscribe(order = PostOrder.LATE)
    public EventTask onServerPreConnect(ServerPreConnectEvent event) {
        String mode = modeOf(targetName(event));
        if (mode == null) {
            return EventTask.resumeWhenComplete(java.util.concurrent.CompletableFuture.completedFuture(null));
        }
        return EventTask.async(() -> {
            Punishment ban = data.punishments().active(event.getPlayer().getUniqueId(), "BAN", mode);
            if (ban != null) {
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
                event.getPlayer().sendMessage(LegacyText.legacy(data.messages().format("ban.deny",
                    "scope", ban.getScope(), "reason", ban.getReason(),
                    "duration", Durations.formatUntil(ban.getExpiresAt()), "by", ban.getByName())));
            }
        });
    }

    /** Ekran bana (kick screen) z konfigurowalnego szablonu ban.screen. */
    public static Component banScreen(CoreData data, Punishment ban) {
        return LegacyText.legacy(data.messages().format("ban.screen",
            "scope", ban.getScope(), "reason", ban.getReason(),
            "duration", Durations.formatUntil(ban.getExpiresAt()), "by", ban.getByName()));
    }

    public static String modeOf(String serverName) {
        if (serverName == null) {
            return null;
        }
        Matcher m = SHARD.matcher(serverName);
        return m.matches() ? m.group(1) : null;
    }

    private static String targetName(ServerPreConnectEvent e) {
        RegisteredServer s = e.getResult().getServer().orElse(e.getOriginalServer());
        return (s != null) ? s.getServerInfo().getName() : null;
    }
}
