package gg.elcartel.velocity.command;

import gg.elcartel.velocity.CoreVelocityPlugin;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import gg.elcartel.data.CoreData;

import java.util.List;
import java.util.Locale;
import gg.elcartel.velocity.punish.PunishmentGuard;

public final class TpCommand implements SimpleCommand {

    private final ProxyServer server;
    private final CoreData data;

    public TpCommand(ProxyServer server, CoreData data) {
        this.server = server;
        this.data = data;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendMessage(net.kyori.adventure.text.Component.text("Tylko dla graczy."));
            return;
        }
        if (!player.hasPermission("elcartel.tp")) {
            player.sendMessage(net.kyori.adventure.text.Component.text("Brak uprawnien (elcartel.tp)."));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length < 1) {
            player.sendMessage(net.kyori.adventure.text.Component.text("Uzycie: /tp <gracz>"));
            return;
        }

        String targetName = args[0];
        server.getPlayer(targetName).ifPresentOrElse(target -> {
            String myMode = PunishmentGuard.modeOf(player.getCurrentServer().get().getServerInfo().getName());
            String targetMode = PunishmentGuard.modeOf(target.getCurrentServer().get().getServerInfo().getName());
            
            if (!myMode.equals(targetMode)) {
                player.sendMessage(net.kyori.adventure.text.Component.text("Gracz przebywa na innym trybie!"));
                return;
            }

            // Jesli na tym samym podserwerze - wyslij plugin message, zeby od razu przeteleportowalo
            // Jesli na innym - ustaw TpDest i przerzuc Velocity
            data.tpDest().set(player.getUniqueId(), target.getUniqueId().toString(), 30000L);
            
            if (player.getCurrentServer().get().getServer().equals(target.getCurrentServer().get().getServer())) {
                try {
                    com.google.common.io.ByteArrayDataOutput out = com.google.common.io.ByteStreams.newDataOutput();
                    out.writeUTF(player.getUniqueId().toString());
                    out.writeUTF(target.getUniqueId().toString());
                    player.getCurrentServer().get().getServer().sendPluginMessage(CoreVelocityPlugin.TP_CHANNEL, out.toByteArray());
                } catch (Exception e) {
                    player.sendMessage(net.kyori.adventure.text.Component.text("Blad podczas bezposredniej teleportacji."));
                }
            } else {
                player.sendMessage(net.kyori.adventure.text.Component.text("Ladowanie sektora gracza..."));
                player.createConnectionRequest(target.getCurrentServer().get().getServer()).fireAndForget();
            }

        }, () -> player.sendMessage(net.kyori.adventure.text.Component.text("Gracz offline lub nie znaleziono.")));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length > 1) return List.of();
        
        String prefix = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (!(invocation.source() instanceof Player p)) return List.of();
        if (p.getCurrentServer().isEmpty()) return List.of();
        
        String mode = PunishmentGuard.modeOf(p.getCurrentServer().get().getServerInfo().getName());

        return server.getAllPlayers().stream()
            .filter(target -> target.getUsername().toLowerCase(Locale.ROOT).startsWith(prefix))
            .filter(target -> target.getCurrentServer().isPresent() && mode.equals(PunishmentGuard.modeOf(target.getCurrentServer().get().getServerInfo().getName())))
            .map(Player::getUsername)
            .toList();
    }
}
