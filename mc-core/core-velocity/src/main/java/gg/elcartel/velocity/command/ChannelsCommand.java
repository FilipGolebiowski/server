package gg.elcartel.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import gg.elcartel.data.model.ShardInfo;
import net.kyori.adventure.text.Component;

import java.util.List;
import gg.elcartel.velocity.shard.ShardRouter;
import gg.elcartel.velocity.shard.ShardWatcher;

/** /channels <tryb> [shard] - lista kanalow trybu lub dolaczenie do konkretnego. */
public final class ChannelsCommand implements SimpleCommand {

    private final ProxyServer server;
    private final ShardRouter router;
    private final ShardWatcher watcher;

    public ChannelsCommand(ProxyServer server, ShardRouter router, ShardWatcher watcher) {
        this.server = server;
        this.router = router;
        this.watcher = watcher;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("Tylko dla graczy."));
            return;
        }
        String[] args = invocation.arguments();
        if (args.length < 1) {
            player.sendMessage(Component.text("Uzyj: /channels <tryb> [shard]"));
            return;
        }
        String mode = args[0].toLowerCase();
        
        String currentServerName = player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("");
        ShardInfo currentShard = router.get(currentServerName);
        if (currentShard != null && currentShard.hasSector() && !currentShard.isSpawnSector()) {
            player.sendMessage(Component.text("Komendy /channels mozna uzyc tylko na spawnie."));
            return;
        }

        if (args.length >= 2) {
            ShardInfo shard = router.get(args[1]);
            if (shard == null || !shard.getMode().equalsIgnoreCase(mode)) {
                player.sendMessage(Component.text("Nie ma kanalu '" + args[1] + "' w trybie " + mode + "."));
                return;
            }
            if (shard.hasSector() && !shard.isSpawnSector()) {
                player.sendMessage(Component.text("Zamiana mozliwa tylko pomiedzy sektorami spawnowymi."));
                return;
            }
            if (!shard.isJoinable(System.currentTimeMillis(), ShardRouter.STALE_MS)) {
                player.sendMessage(Component.text("Kanal pelny lub niedostepny."));
                return;
            }
            RegisteredServer target = watcher.ensure(shard);
            player.sendMessage(Component.text("Lacze z " + shard.getId() + "..."));
            player.createConnectionRequest(target).fireAndForget();
            return;
        }

        List<ShardInfo> list = router.listFresh(mode);
        list.removeIf(s -> s.hasSector() && !s.isSpawnSector());
        if (list.isEmpty()) {
            player.sendMessage(Component.text("Brak aktywnych kanalow trybu '" + mode + "'."));
            return;
        }
        player.sendMessage(Component.text("Kanaly trybu " + mode + ":"));
        for (ShardInfo s : list) {
            player.sendMessage(Component.text(" - " + s.getId() + ": " + s.getPlayers() + "/" + s.getSoftCap()
                + " (" + s.getState() + ")   /channels " + mode + " " + s.getId()));
        }
    }

    @Override
    public java.util.List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0 || args.length == 1) {
            return java.util.List.of("survival", "oceanblock");
        } else if (args.length == 2) {
            String mode = args[0].toLowerCase(java.util.Locale.ROOT);
            return router.listFresh(mode).stream()
                .filter(s -> !s.hasSector() || s.isSpawnSector())
                .map(ShardInfo::getId)
                .toList();
        }
        return java.util.List.of();
    }
}
