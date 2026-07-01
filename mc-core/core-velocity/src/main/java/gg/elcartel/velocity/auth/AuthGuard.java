package gg.elcartel.velocity.auth;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;

import java.util.Optional;

/**
 * Twarda granica autoryzacji na proxy. Niezalogowany gracz:
 *  - NIE moze opuscic limbo (np. /server lobby) - kazda probe wejscia gdzie indziej
 *    niz limbo zamieniamy z powrotem na limbo,
 *  - NIE moze uzyc zadnej komendy proxy poza /login /register /otp (te musza dojsc
 *    do backendu limbo).
 * To zamyka bypass, bo /server jest komenda PROXY (Velocity), nie backendu.
 */
public final class AuthGuard {

    private final ProxyServer server;
    private final AuthState authState;
    private final String limboName;

    public AuthGuard(ProxyServer server, AuthState authState, String limboName) {
        this.server = server;
        this.authState = authState;
        this.limboName = limboName;
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        if (authState.isAuthed(player.getUniqueId())) {
            return;
        }
        RegisteredServer target = event.getResult().getServer().orElse(event.getOriginalServer());
        String targetName = (target != null) ? target.getServerInfo().getName() : null;
        if (targetName != null && targetName.equalsIgnoreCase(limboName)) {
            return; // limbo dozwolone (tam sie loguje)
        }
        Optional<RegisteredServer> limbo = server.getServer(limboName);
        if (limbo.isPresent()) {
            event.setResult(ServerPreConnectEvent.ServerResult.allowed(limbo.get()));
        } else {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onCommand(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player player)) {
            return;
        }
        if (authState.isAuthed(player.getUniqueId())) {
            return;
        }
        String root = event.getCommand().split(" ", 2)[0].toLowerCase();
        if (root.equals("login") || root.equals("register") || root.equals("otp") || root.equals("2fa")) {
            return; // komendy auth -> backend limbo
        }
        event.setResult(CommandExecuteEvent.CommandResult.denied());
        player.sendMessage(Component.text("Najpierw zaloguj sie."));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        authState.clear(event.getPlayer().getUniqueId());
    }
}
