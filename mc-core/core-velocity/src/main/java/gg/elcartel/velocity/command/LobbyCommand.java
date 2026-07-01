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

/** /lobby, /hub - dolaczenie do najmniej obciazonego huba. */
public final class LobbyCommand implements SimpleCommand {

    private final ProxyServer server;
    private final ShardRouter router;
    private final ShardWatcher watcher;

    public LobbyCommand(ProxyServer server, ShardRouter router, ShardWatcher watcher) {
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
        
        Optional<ShardInfo> best = router.chooseLeastLoaded("hub");
        if (best.isEmpty()) {
            player.sendMessage(Component.text("Brak wolnych kanalow trybu 'hub'. Sprobuj za chwile."));
            return;
        }
        ShardInfo shard = best.get();
        RegisteredServer target = watcher.ensure(shard);
        player.sendMessage(Component.text("Lacze z " + shard.getId() + "..."));
        
        router.getRegistry().publish("core:handoff:force", player.getUniqueId().toString());
        
        server.getScheduler().buildTask(server.getPluginManager().getPlugin("core-velocity").get(), () -> {
            player.createConnectionRequest(target).fireAndForget();
        }).delay(150L, java.util.concurrent.TimeUnit.MILLISECONDS).schedule();
    }
}
