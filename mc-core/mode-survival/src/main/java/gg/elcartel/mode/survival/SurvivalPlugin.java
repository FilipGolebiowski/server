package gg.elcartel.mode.survival;

import gg.elcartel.data.api.CoreApi;
import gg.elcartel.modes.survival.ShopCommand;
import gg.elcartel.modes.survival.shop.ShopListener;
import gg.elcartel.modes.survival.shop.ShopManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/** Plugin trybu survival (stub). Mechaniki: RTP, claimy, eventy - korzystaj z CoreApi.get().data(). */
public final class SurvivalPlugin extends JavaPlugin {

    private ShopManager shopManager;

    @Override
    public void onEnable() {
        reloadConfig();
        saveDefaultConfig();

        if (CoreApi.get() != null) {
            String title = getConfig().getString("scoreboard.title", "&e&lANARCHIA.GG");
            java.util.List<String> lines = getConfig().getStringList("scoreboard.lines");
            if (lines.isEmpty()) {
                lines = java.util.Arrays.asList("&7Brak konfiguracji");
            }
            CoreApi.get().setScoreboardTemplate(title, lines);
        }

        // 2. Inicjalizacja Sklepu
        shopManager = new ShopManager(this);
        shopManager.load();

        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            Economy economy = rsp.getProvider();
            getServer().getPluginManager().registerEvents(new ShopListener(economy), this);
            if (getCommand("sklep") != null) {
                getCommand("sklep").setExecutor(new ShopCommand(shopManager));
            }
            getLogger().info("Zainicjowano sklep z podpiętą ekonomią Vault!");
        } else {
            getLogger().warning("Vault nie zostal znaleziony! Sklep zablokowany.");
        }

        getLogger().info("mode-survival włączony!");
    }
}
