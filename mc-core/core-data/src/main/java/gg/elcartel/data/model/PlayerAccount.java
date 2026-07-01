package gg.elcartel.data.model;

import org.bson.codecs.pojo.annotations.BsonId;

import java.util.UUID;

/** Konto gracza - dane GLOBALNE (wspolne dla wszystkich trybow). Kolekcja "players". */
public final class PlayerAccount {

    @BsonId
    private UUID id;
    private String name;
    private String nameLower;     // do rezerwacji nicku (lookup case-insensitive)
    private boolean premium;      // true = konto zaklepane jako premium (rezerwacja nicku)
    private long firstSeen;
    private long lastSeen;
    private String lastIp;
    private String group = "default";
    private long version;

    public PlayerAccount() {
    }

    public PlayerAccount(UUID id, String name) {
        this.id = id;
        setName(name);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; this.nameLower = (name == null) ? null : name.toLowerCase(); }
    public String getNameLower() { return nameLower; }
    public void setNameLower(String nameLower) { this.nameLower = nameLower; }
    public boolean isPremium() { return premium; }
    public void setPremium(boolean premium) { this.premium = premium; }
    public long getFirstSeen() { return firstSeen; }
    public void setFirstSeen(long firstSeen) { this.firstSeen = firstSeen; }
    public long getLastSeen() { return lastSeen; }
    public void setLastSeen(long lastSeen) { this.lastSeen = lastSeen; }
    public String getLastIp() { return lastIp; }
    public void setLastIp(String lastIp) { this.lastIp = lastIp; }
    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
