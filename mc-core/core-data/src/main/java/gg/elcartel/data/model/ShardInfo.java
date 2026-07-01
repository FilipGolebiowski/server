package gg.elcartel.data.model;

import java.util.HashMap;
import java.util.Map;

/** Opis sharda (kanalu) w rejestrze Redis (BLUEPRINT-kanaly, sekcja 3). */
public final class ShardInfo {

    private final String id;
    private final String mode;
    private String addr;
    private String state = "OPEN"; // OPEN | FULL | DRAINING | DOWN
    private int players;
    private int softCap;
    private int hardCap;
    private double tps = 20.0;
    private double mspt;
    private long heartbeat;
    private int sectorX = Integer.MIN_VALUE;
    private int sectorZ = Integer.MIN_VALUE;
    private boolean spawnSector;

    public ShardInfo(String id, String mode, String addr, int softCap, int hardCap) {
        this.id = id;
        this.mode = mode;
        this.addr = addr;
        this.softCap = softCap;
        this.hardCap = hardCap;
    }

    public String getId() { return id; }
    public String getMode() { return mode; }
    public String getAddr() { return addr; }
    public void setAddr(String addr) { this.addr = addr; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public int getPlayers() { return players; }
    public void setPlayers(int players) { this.players = players; }
    public int getSoftCap() { return softCap; }
    public void setSoftCap(int softCap) { this.softCap = softCap; }
    public int getHardCap() { return hardCap; }
    public void setHardCap(int hardCap) { this.hardCap = hardCap; }
    public double getTps() { return tps; }
    public void setTps(double tps) { this.tps = tps; }
    public double getMspt() { return mspt; }
    public void setMspt(double mspt) { this.mspt = mspt; }
    public long getHeartbeat() { return heartbeat; }
    public void setHeartbeat(long heartbeat) { this.heartbeat = heartbeat; }
    public boolean hasSector() { return sectorX != Integer.MIN_VALUE; }
    public int getSectorX() { return sectorX; }
    public int getSectorZ() { return sectorZ; }
    public void setSector(int x, int z) { this.sectorX = x; this.sectorZ = z; }
    public boolean isSpawnSector() { return spawnSector; }
    public void setSpawnSector(boolean spawn) { this.spawnSector = spawn; }

    /** Heartbeat swiezy (shard zyje)? */
    public boolean isFresh(long now, long staleMs) {
        return now - heartbeat <= staleMs;
    }

    /** Czy mozna tu kierowac nowych graczy? */
    public boolean isJoinable(long now, long staleMs) {
        return isFresh(now, staleMs) && "OPEN".equals(state) && players < softCap;
    }

    public Map<String, String> toHash() {
        Map<String, String> m = new HashMap<>();
        m.put("mode", mode);
        m.put("addr", addr == null ? "" : addr);
        m.put("state", state);
        m.put("players", String.valueOf(players));
        m.put("softCap", String.valueOf(softCap));
        m.put("hardCap", String.valueOf(hardCap));
        m.put("tps", String.valueOf(tps));
        m.put("mspt", String.valueOf(mspt));
        m.put("heartbeat", String.valueOf(heartbeat));
        if (hasSector()) {
            m.put("sx", String.valueOf(sectorX));
            m.put("sz", String.valueOf(sectorZ));
            if (spawnSector) m.put("spawn", "1");
        }
        return m;
    }

    public static ShardInfo fromHash(String id, Map<String, String> h) {
        ShardInfo s = new ShardInfo(id, h.getOrDefault("mode", ""), h.getOrDefault("addr", ""),
            parseInt(h.get("softCap")), parseInt(h.get("hardCap")));
        s.state = h.getOrDefault("state", "OPEN");
        s.players = parseInt(h.get("players"));
        s.tps = parseDouble(h.get("tps"));
        s.mspt = parseDouble(h.get("mspt"));
        s.heartbeat = parseLong(h.get("heartbeat"));
        if (h.containsKey("sx") && h.containsKey("sz")) {
            s.setSector(parseInt(h.get("sx")), parseInt(h.get("sz")));
            s.setSpawnSector("1".equals(h.get("spawn")));
        }
        return s;
    }

    private static int parseInt(String v) {
        try { return v == null ? 0 : Integer.parseInt(v.trim()); } catch (Exception e) { return 0; }
    }
    private static long parseLong(String v) {
        try { return v == null ? 0L : Long.parseLong(v.trim()); } catch (Exception e) { return 0L; }
    }
    private static double parseDouble(String v) {
        try { return v == null ? 0.0 : Double.parseDouble(v.trim()); } catch (Exception e) { return 0.0; }
    }
}
