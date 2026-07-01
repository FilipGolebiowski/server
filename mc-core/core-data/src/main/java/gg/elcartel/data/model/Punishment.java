package gg.elcartel.data.model;

import org.bson.codecs.pojo.annotations.BsonId;

import java.util.UUID;

/**
 * Kara. Kolekcja "punishments". Zakres (scope): "network" (cala siec) albo nazwa trybu
 * (np. "survival") - kara obowiazuje tylko na tym trybie.
 */
public final class Punishment {

    public static final String NETWORK = "network";

    @BsonId
    private String id;       // losowy uuid kary
    private UUID uuid;       // ukarany gracz
    private String type;     // BAN | MUTE | KICK | WARN
    private String scope;    // "network" | nazwa trybu
    private String reason;
    private String byName;   // kto nadal
    private long createdAt;
    private long expiresAt;  // 0 = na stale
    private boolean active = true;

    public Punishment() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public UUID getUuid() { return uuid; }
    public void setUuid(UUID uuid) { this.uuid = uuid; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getByName() { return byName; }
    public void setByName(String byName) { this.byName = byName; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
