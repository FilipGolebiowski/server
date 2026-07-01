package gg.elcartel.data.model;

import org.bson.codecs.pojo.annotations.BsonId;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Profil gracza w danym trybie (PER-TRYB). Kazdy tryb ma WLASNA kolekcje (profiles_&lt;mode&gt;),
 * wiec _id = uuid gracza, a tryb wynika z nazwy kolekcji. Ekonomia jest osobno
 * (economy_&lt;mode&gt;); tu stan gry: ekwipunek, ender, statystyki, exp/hp/food/gamemode/pozycje.
 */
public final class ModeProfile {

    @BsonId
    private UUID uuid;        // _id = uuid gracza
    private String mode;      // dla referencji (tryb = nazwa kolekcji)

    private byte[] inventory;
    private byte[] enderChest;
    private int level;
    private float exp;
    private double health = 20.0;
    private int foodLevel = 20;
    private int gameMode;
    private Map<String, Position> positions = new HashMap<>();
    private Map<String, Long> stats = new HashMap<>();

    private long version;

    public ModeProfile() {
    }

    public ModeProfile(UUID uuid, String mode) {
        this.uuid = uuid;
        this.mode = mode;
    }

    public UUID getUuid() { return uuid; }
    public void setUuid(UUID uuid) { this.uuid = uuid; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public byte[] getInventory() { return inventory; }
    public void setInventory(byte[] inventory) { this.inventory = inventory; }
    public byte[] getEnderChest() { return enderChest; }
    public void setEnderChest(byte[] enderChest) { this.enderChest = enderChest; }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }
    public float getExp() { return exp; }
    public void setExp(float exp) { this.exp = exp; }
    public double getHealth() { return health; }
    public void setHealth(double health) { this.health = health; }
    public int getFoodLevel() { return foodLevel; }
    public void setFoodLevel(int foodLevel) { this.foodLevel = foodLevel; }
    public int getGameMode() { return gameMode; }
    public void setGameMode(int gameMode) { this.gameMode = gameMode; }
    public Map<String, Position> getPositions() { return positions; }
    public void setPositions(Map<String, Position> positions) { this.positions = positions; }
    public Map<String, Long> getStats() { return stats; }
    public void setStats(Map<String, Long> stats) { this.stats = stats; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
