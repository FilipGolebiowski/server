package gg.elcartel.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import gg.elcartel.data.model.ShardInfo;
import net.kyori.adventure.text.Component;

import java.util.Optional;
import gg.elcartel.velocity.shard.ShardRouter;
import gg.elcartel.velocity.shard.ShardWatcher;

/** /play <tryb> - dolaczenie do najmniej obciazonego kanalu danego trybu. */
public final class PlayCommand implements SimpleCommand {

    private final ProxyServer server;
    private final ShardRouter router;
    private final ShardWatcher watcher;

    public PlayCommand(ProxyServer server, ShardRouter router, ShardWatcher watcher) {
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
            player.sendMessage(Component.text("Uzyj: /play <tryb>"));
            return;
        }
        String mode = args[0].toLowerCase();
        Optional<ShardInfo> best = router.chooseLeastLoaded(mode);
        if (best.isEmpty()) {
            player.sendMessage(Component.text("Brak wolnych kanalow trybu '" + mode + "'. Sprobuj za chwile."));
            return;
        }
        ShardInfo shard = best.get();
        RegisteredServer target = watcher.ensure(shard);
        player.sendMessage(Component.text("Lacze z " + shard.getId() + "..."));
        
        // Wymuszamy na obecnym backendzie (jesli to serwer gry) by natychmiast zwolnil
        // lock dla tego gracza, zapobiegajac 6-sekundowemu deadlockowi
        router.getRegistry().publish("core:handoff:force", player.getUniqueId().toString());
        
        server.getScheduler().buildTask(server.getPluginManager().getPlugin("core-velocity").get(), () -> {
            player.createConnectionRequest(target).fireAndForget();
        }).delay(150L, java.util.concurrent.TimeUnit.MILLISECONDS).schedule();
    }

    @Override
    public java.util.List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length == 1 || invocation.arguments().length == 0) {
            return java.util.List.of("survival", "oceanblock", "hub");
        }
        return java.util.List.of();
    }
}
