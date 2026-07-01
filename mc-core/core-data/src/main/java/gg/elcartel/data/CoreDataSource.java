package gg.elcartel.data;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import gg.elcartel.data.config.MongoSettings;
import gg.elcartel.data.config.RedisSettings;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.bson.UuidRepresentation;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;

import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;

/** Zrodlo polaczen: MongoDB (POJO codec, UUID standard, write majority) + Redis (Lettuce). */
public final class CoreDataSource implements AutoCloseable {

    private final MongoClient mongoClient;
    private final MongoDatabase database;
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> redisConnection;

    public CoreDataSource(MongoSettings mongo, RedisSettings redis) {
        CodecRegistry pojoRegistry = fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            fromProviders(PojoCodecProvider.builder().automatic(true).build())
        );

        MongoClientSettings settings = MongoClientSettings.builder()
            .applyConnectionString(new ConnectionString(mongo.uri()))
            .uuidRepresentation(UuidRepresentation.STANDARD)
            .codecRegistry(pojoRegistry)
            .writeConcern(WriteConcern.MAJORITY)
            .build();

        this.mongoClient = MongoClients.create(settings);
        this.database = mongoClient.getDatabase(mongo.database());

        this.redisClient = RedisClient.create(redis.uri());
        this.redisConnection = redisClient.connect();
    }

    public MongoDatabase database() {
        return database;
    }

    public RedisClient redisClient() {
        return redisClient;
    }

    public RedisCommands<String, String> redis() {
        return redisConnection.sync();
    }

    @Override
    public void close() {
        try { redisConnection.close(); } catch (Exception ignored) { }
        try { redisClient.shutdown(); } catch (Exception ignored) { }
        try { mongoClient.close(); } catch (Exception ignored) { }
    }
}
