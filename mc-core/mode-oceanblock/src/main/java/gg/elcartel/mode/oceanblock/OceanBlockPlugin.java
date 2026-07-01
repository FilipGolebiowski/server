package gg.elcartel.mode.oceanblock;

import gg.elcartel.data.api.CoreApi;
import org.bukkit.plugin.java.JavaPlugin;

/** Plugin trybu oceanblock (stub). Mechaniki: wyspy, /is, wyzwania - korzystaj z CoreApi.get().data(). */
public final class OceanBlockPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        CoreApi api = CoreApi.get();
        getLogger().info("mode-oceanblock aktywny" + (api != null ? " (tryb " + api.mode() + ")" : " (brak CoreApi)") + ".");
        
        if (api != null && getConfig().getBoolean("scoreboard.enabled", false)) {
            String title = getConfig().getString("scoreboard.title", "&b&lOCEANBLOCK");
            java.util.List<String> lines = getConfig().getStringList("scoreboard.lines");
            api.setScoreboardTemplate(title, lines);
            getLogger().info("Szablon scoreboardu zostal pomyslnie zarejestrowany w CoreApi.");
        }
    }
}
