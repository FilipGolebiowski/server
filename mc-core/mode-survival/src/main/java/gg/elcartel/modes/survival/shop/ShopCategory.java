package gg.elcartel.modes.survival.shop;

import org.bukkit.Material;
import java.util.List;

public class ShopCategory {
    private final String id;
    private final String name;
    private final Material icon;
    private final int slot;
    private final List<ShopItem> items;

    public ShopCategory(String id, String name, Material icon, int slot, List<ShopItem> items) {
        this.id = id;
        this.name = name;
        this.icon = icon;
        this.slot = slot;
        this.items = items;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Material getIcon() { return icon; }
    public int getSlot() { return slot; }
    public List<ShopItem> getItems() { return items; }
}
