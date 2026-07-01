package gg.elcartel.data.repo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOptions;
import gg.elcartel.data.model.ModeProfile;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.mongodb.client.model.Filters.eq;

/**
 * Profile per-tryb - KAZDY TRYB MA WLASNA KOLEKCJE: profiles_&lt;mode&gt;.
 * Czytelny podzial w bazie, niezalezne indeksy/sharding, usuniecie trybu = drop kolekcji.
 * _id = uuid gracza (tryb wynika z nazwy kolekcji).
 */
public final class ModeProfileRepository {

    private final MongoDatabase database;
    private final Map<String, MongoCollection<ModeProfile>> cache = new ConcurrentHashMap<>();

    public ModeProfileRepository(MongoDatabase database) {
        this.database = database;
    }

    /** Nazwa kolekcji dla trybu, np. "survival" -> "profiles_survival". */
    public static String collectionName(String mode) {
        String safe = (mode == null ? "unknown" : mode.toLowerCase().replaceAll("[^a-z0-9_]", "_"));
        return "profiles_" + safe;
    }

    private MongoCollection<ModeProfile> collection(String mode) {
        return cache.computeIfAbsent(mode, m -> database.getCollection(collectionName(m), ModeProfile.class));
    }

    public ModeProfile load(UUID uuid, String mode) {
        return collection(mode).find(eq("_id", uuid)).first();
    }

    /** Zapis z inkrementacja wersji (handoff) + upsert. Zwraca nowa wersje. */
    public long save(ModeProfile profile) {
        profile.setVersion(profile.getVersion() + 1);
        collection(profile.getMode()).replaceOne(eq("_id", profile.getUuid()), profile, new ReplaceOptions().upsert(true));
        return profile.getVersion();
    }
}
