package gg.elcartel.data.repo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOptions;
import gg.elcartel.data.model.AuthRecord;

import java.util.UUID;

import static com.mongodb.client.model.Filters.eq;

/** Repozytorium danych auth (kolekcja "auth", klucz _id = uuid). */
public final class AuthRepository {

    private final MongoCollection<AuthRecord> collection;

    public AuthRepository(MongoDatabase database) {
        this.collection = database.getCollection("auth", AuthRecord.class);
    }

    public AuthRecord load(UUID id) {
        return collection.find(eq("_id", id)).first();
    }

    public void save(AuthRecord record) {
        collection.replaceOne(eq("_id", record.getId()), record, new ReplaceOptions().upsert(true));
    }

    /** RODO: trwale usuniecie danych auth gracza. */
    public void delete(UUID id) {
        collection.deleteOne(eq("_id", id));
    }
}
