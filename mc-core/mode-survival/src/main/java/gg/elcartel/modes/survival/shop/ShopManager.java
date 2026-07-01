package gg.elcartel.modes.survival.shop;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ShopManager {

    private final JavaPlugin plugin;
    private final Map<String, ShopCategory> categories = new HashMap<>();

    public ShopManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        categories.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("shop.categories");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            ConfigurationSection catSec = section.getConfigurationSection(key);
            if (catSec == null) continue;

            String name = catSec.getString("name", key);
            Material icon = Material.matchMaterial(catSec.getString("icon", "STONE"));
            if (icon == null) icon = Material.STONE;
            int slot = catSec.getInt("slot", 0);

            List<ShopItem> items = new ArrayList<>();
            ConfigurationSection itemsSec = catSec.getConfigurationSection("items");
            if (itemsSec != null) {
                for (String itemId : itemsSec.getKeys(false)) {
                    ConfigurationSection itemSec = itemsSec.getConfigurationSection(itemId);
                    if (itemSec == null) continue;

                    Material mat = Material.matchMaterial(itemSec.getString("material", "STONE"));
                    if (mat == null) mat = Material.STONE;
                    double buy = itemSec.getDouble("buy", -1);
                    double sell = itemSec.getDouble("sell", -1);

                    items.add(new ShopItem(itemId, mat, buy, sell));
                }
            }
            categories.put(key.toLowerCase(), new ShopCategory(key, name, icon, slot, items));
        }
    }

    public List<ShopCategory> getCategories() {
        return new ArrayList<>(categories.values());
    }

    public ShopCategory getCategory(String id) {
        return categories.get(id.toLowerCase());
    }
}
