package gg.elcartel.data.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

import java.util.function.BiConsumer;

/**
 * Cienka warstwa pub/sub miedzy serwerami i proxy (kanaly core:*).
 * UWAGA: w Redis Cluster zwykly pub/sub ma ograniczenia propagacji - docelowo sharded pub/sub.
 */
public final class RedisMessenger implements AutoCloseable {

    private final StatefulRedisPubSubConnection<String, String> pubConnection;
    private final StatefulRedisPubSubConnection<String, String> subConnection;

    public RedisMessenger(RedisClient client) {
        this.pubConnection = client.connectPubSub();
        this.subConnection = client.connectPubSub();
    }

    public void publish(String channel, String message) {
        pubConnection.sync().publish(channel, message);
    }

    /** Rejestruje handler (kanal, wiadomosc) i subskrybuje kanal. */
    public void subscribe(String channel, BiConsumer<String, String> handler) {
        subConnection.addListener(new RedisPubSubAdapter<String, String>() {
            @Override
            public void message(String ch, String msg) {
                if (ch.equals(channel)) {
                    handler.accept(ch, msg);
                }
            }
        });
        subConnection.sync().subscribe(channel);
    }

    @Override
    public void close() {
        try { pubConnection.close(); } catch (Exception ignored) { }
        try { subConnection.close(); } catch (Exception ignored) { }
    }
}
