package gg.elcartel.velocity.shard;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import gg.elcartel.data.model.ShardInfo;
import gg.elcartel.data.redis.ShardRegistry;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Synchronizuje liste serwerow Velocity z rejestrem shardow w Redis (autoscaling):
 * nowy/swiezy shard -> registerServer; znikniety/stale -> unregisterServer.
 * Dotyka tylko serwerow, ktore SAM zarejestrowal (statyczne lobby/limbo nietkniete).
 */
public final class ShardWatcher {

    private final ProxyServer server;
    private final ShardRegistry registry;
    private final Set<String> managed = new HashSet<>();

    public ShardWatcher(ProxyServer server, ShardRegistry registry) {
        this.server = server;
        this.registry = registry;
    }

    public void start(Object plugin) {
        server.getScheduler().buildTask(plugin, this::sync).repeat(3L, TimeUnit.SECONDS).schedule();
        sync();
    }

    /** Zapewnia, ze shard jest zarejestrowany jako serwer Velocity; zwraca go. */
    public RegisteredServer ensure(ShardInfo info) {
        Optional<RegisteredServer> existing = server.getServer(info.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        RegisteredServer rs = server.registerServer(new ServerInfo(info.getId(), parseAddr(info.getAddr())));
        managed.add(info.getId());
        return rs;
    }

    public void sync() {
        long now = System.currentTimeMillis();
        for (String id : registry.listAll()) {
            ShardInfo s = registry.get(id);
            if (s != null && s.isFresh(now, ShardRouter.STALE_MS)) {
                ensure(s);
            }
        }
        for (String name : new HashSet<>(managed)) {
            ShardInfo s = registry.get(name);
            if (s == null || !s.isFresh(now, ShardRouter.STALE_MS)) {
                server.getServer(name).map(RegisteredServer::getServerInfo).ifPresent(server::unregisterServer);
                managed.remove(name);
            }
        }
    }

    private static InetSocketAddress parseAddr(String addr) {
        int i = (addr == null) ? -1 : addr.lastIndexOf(':');
        if (i <= 0) {
            return new InetSocketAddress("127.0.0.1", 25565);
        }
        try {
            return new InetSocketAddress(addr.substring(0, i), Integer.parseInt(addr.substring(i + 1)));
        } catch (RuntimeException e) {
            return new InetSocketAddress("127.0.0.1", 25565);
        }
    }
}
