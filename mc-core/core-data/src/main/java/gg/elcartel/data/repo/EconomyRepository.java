package gg.elcartel.data.repo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gte;

/**
 * Ekonomia PER-TRYB - KAZDY TRYB MA WLASNA KOLEKCJE: economy_&lt;mode&gt;, _id = uuid.
 * Operacje atomowe ($inc / warunkowy withdraw) - bezpieczne niezaleznie od tego, ktory shard
 * trzyma gracza (saldo poza blobem handoffu). Kwalifikujemy Updates.set/inc jawnie (klasa ma metode set).
 */
public final class EconomyRepository {

    private final MongoDatabase database;
    private final Map<String, MongoCollection<Document>> cache = new ConcurrentHashMap<>();

    public EconomyRepository(MongoDatabase database) {
        this.database = database;
    }

    /** Nazwa kolekcji dla trybu, np. "survival" -> "economy_survival". */
    public static String collectionName(String mode) {
        String safe = (mode == null ? "unknown" : mode.toLowerCase().replaceAll("[^a-z0-9_]", "_"));
        return "economy_" + safe;
    }

    private MongoCollection<Document> collection(String mode) {
        return cache.computeIfAbsent(mode, m -> database.getCollection(collectionName(m)));
    }

    public double get(UUID uuid, String mode) {
        Document d = collection(mode).find(eq("_id", uuid)).first();
        if (d == null) {
            return 0.0;
        }
        Object b = d.get("balance");
        return (b instanceof Number) ? ((Number) b).doubleValue() : 0.0;
    }

    public void deposit(UUID uuid, String mode, double amount) {
        if (amount <= 0) {
            return;
        }
        collection(mode).updateOne(eq("_id", uuid), Updates.inc("balance", amount), new UpdateOptions().upsert(true));
    }

    /** Atomowo: pobierz tylko jesli saldo >= amount. true = pobrano. */
    public boolean withdraw(UUID uuid, String mode, double amount) {
        if (amount <= 0) {
            return false;
        }
        long modified = collection(mode).updateOne(
            and(eq("_id", uuid), gte("balance", amount)),
            Updates.inc("balance", -amount)
        ).getModifiedCount();
        return modified > 0;
    }

    public void set(UUID uuid, String mode, double amount) {
        collection(mode).updateOne(eq("_id", uuid), Updates.set("balance", amount), new UpdateOptions().upsert(true));
    }

    /** Przelew w obrebie tego samego trybu: atomowy withdraw + deposit (+ refund przy bledzie). */
    public boolean transfer(UUID from, UUID to, String mode, double amount) {
        if (!withdraw(from, mode, amount)) {
            return false;
        }
        try {
            deposit(to, mode, amount);
            return true;
        } catch (RuntimeException e) {
            deposit(from, mode, amount);
            throw e;
        }
    }
}
