package gg.elcartel.data.repo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;
import gg.elcartel.data.model.Punishment;

import java.util.UUID;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.or;

/** Kary (kolekcja "punishments"). Zakres network lub per-tryb. */
public final class PunishmentRepository {

    private final MongoCollection<Punishment> collection;

    public PunishmentRepository(MongoDatabase database) {
        this.collection = database.getCollection("punishments", Punishment.class);
    }

    public void ensureIndexes() {
        collection.createIndex(Indexes.ascending("uuid"));
        collection.createIndex(Indexes.ascending("uuid", "type", "scope", "active"));
    }

    public void add(Punishment p) {
        if (p.getId() == null) {
            p.setId(UUID.randomUUID().toString());
        }
        if (p.getCreatedAt() == 0) {
            p.setCreatedAt(System.currentTimeMillis());
        }
        collection.insertOne(p);
    }

    /** Aktywna, niewygasla kara danego typu w danym zakresie (lub null). */
    public Punishment active(UUID uuid, String type, String scope) {
        long now = System.currentTimeMillis();
        return collection.find(and(
            eq("uuid", uuid), eq("type", type), eq("scope", scope), eq("active", true),
            or(eq("expiresAt", 0L), gt("expiresAt", now))
        )).first();
    }

    /** Efektywny ban: najpierw network (priorytet), potem dany tryb. */
    public Punishment effectiveBan(UUID uuid, String mode) {
        Punishment net = active(uuid, "BAN", Punishment.NETWORK);
        return (net != null) ? net : active(uuid, "BAN", mode);
    }

    public Punishment effectiveMute(UUID uuid, String mode) {
        Punishment net = active(uuid, "MUTE", Punishment.NETWORK);
        return (net != null) ? net : active(uuid, "MUTE", mode);
    }

    /** Dezaktywuje aktywne kary danego typu w zakresie. Zwraca liczbe. */
    public long pardon(UUID uuid, String type, String scope) {
        return collection.updateMany(
            and(eq("uuid", uuid), eq("type", type), eq("scope", scope), eq("active", true)),
            Updates.set("active", false)
        ).getModifiedCount();
    }
}
