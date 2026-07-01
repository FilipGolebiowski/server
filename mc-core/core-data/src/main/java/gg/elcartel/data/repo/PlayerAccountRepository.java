package gg.elcartel.data.repo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import gg.elcartel.data.model.PlayerAccount;

import java.util.UUID;

import static com.mongodb.client.model.Filters.eq;

/** Konta graczy (global) - kolekcja "players". Trzyma tez rezerwacje nicku premium. */
public final class PlayerAccountRepository {

    private final MongoCollection<PlayerAccount> collection;

    public PlayerAccountRepository(MongoDatabase database) {
        this.collection = database.getCollection("players", PlayerAccount.class);
    }

    public void ensureIndexes() {
        collection.createIndex(Indexes.ascending("name"));
        collection.createIndex(Indexes.ascending("nameLower"));
    }

    public PlayerAccount load(UUID id) {
        return collection.find(eq("_id", id)).first();
    }

    public PlayerAccount findByNameLower(String name) {
        if (name == null) {
            return null;
        }
        return collection.find(eq("nameLower", name.toLowerCase())).first();
    }

    /** Czy nick jest zaklepany jako premium (rezerwacja). Niezalezne od API Mojanga. */
    public boolean isPremiumName(String name) {
        PlayerAccount a = findByNameLower(name);
        return a != null && a.isPremium();
    }

    public long save(PlayerAccount account) {
        if (account.getName() != null) {
            account.setNameLower(account.getName().toLowerCase());
        }
        account.setVersion(account.getVersion() + 1);
        collection.replaceOne(eq("_id", account.getId()), account, new ReplaceOptions().upsert(true));
        return account.getVersion();
    }

    /** Upsert "widzialnosci": tworzy konto jesli brak, odswieza nick/lastSeen/lastIp. */
    public PlayerAccount touch(UUID id, String name, String ip) {
        return upsert(id, name, ip, false);
    }

    /** Zaklepanie konta jako PREMIUM przy pierwszym wejsciu (rezerwacja nicku). */
    public PlayerAccount claimPremium(UUID id, String name, String ip) {
        return upsert(id, name, ip, true);
    }

    private PlayerAccount upsert(UUID id, String name, String ip, boolean premium) {
        PlayerAccount account = load(id);
        long now = System.currentTimeMillis();
        if (account == null) {
            account = new PlayerAccount(id, name);
            account.setFirstSeen(now);
        }
        account.setName(name);
        account.setLastSeen(now);
        if (ip != null) {
            account.setLastIp(ip);
        }
        if (premium) {
            account.setPremium(true);
        }
        save(account);
        return account;
    }
}
