package gg.elcartel.modes.survival.shop;

import org.bukkit.Material;

public class ShopItem {
    private final String id;
    private final Material material;
    private final double buyPrice; // za 1 szt.
    private final double sellPrice; // za 1 szt.

    public ShopItem(String id, Material material, double buyPrice, double sellPrice) {
        this.id = id;
        this.material = material;
        this.buyPrice = buyPrice;
        this.sellPrice = sellPrice;
    }

    public String getId() { return id; }
    public Material getMaterial() { return material; }
    public double getBuyPrice() { return buyPrice; }
    public double getSellPrice() { return sellPrice; }
}
