package gg.elcartel.data.api;

import gg.elcartel.data.CoreData;

/**
 * API rdzenia dla pluginow trybow (mode-hub, mode-survival, ...).
 * Plugin trybu deklaruje `depend: [CorePaper]` w plugin.yml i pobiera API przez {@link #get()}
 * w onEnable. Daje dostep do wspolnej warstwy danych (Mongo/Redis), ekonomii, nazwy trybu
 * sharda itd. - BEZ wlasnych polaczen. Mieszka w core-data (nie w grubym core-paper), wiec
 * pluginy trybow zaleza tylko od lekkiego core-data (bez shadow).
 */
public final class CoreApi {

    private static volatile CoreApi instance;

    private final CoreData data;
    private final String mode;
    private final String shardId;

    private String scoreboardTitle;
    private java.util.List<String> scoreboardLines;

    public CoreApi(CoreData data, String mode, String shardId) {
        this.data = data;
        this.mode = mode;
        this.shardId = shardId;
    }

    /** API rdzenia lub null, jesli core nie zainicjowal warstwy danych (np. brak ELCARTEL_SHARD_ID). */
    public static CoreApi get() {
        return instance;
    }

    public static void set(CoreApi api) {
        instance = api;
    }

    public CoreData data() { return data; }
    public String mode() { return mode; }
    public String shardId() { return shardId; }

    public void setScoreboardTemplate(String title, java.util.List<String> lines) {
        this.scoreboardTitle = title;
        this.scoreboardLines = lines;
    }

    public String getScoreboardTitle() { return scoreboardTitle; }
    public java.util.List<String> getScoreboardLines() { return scoreboardLines; }
}
