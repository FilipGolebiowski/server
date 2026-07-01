package gg.elcartel.modes.survival.shop.gui;

import gg.elcartel.modes.survival.shop.ShopCategory;
import gg.elcartel.modes.survival.shop.ShopManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Map;

public class ShopCategoryMenu extends ShopGUIHolder {

    private final ShopManager manager;
    private final Map<Integer, ShopCategory> slotMap = new HashMap<>();

    public ShopCategoryMenu(ShopManager manager) {
        this.manager = manager;
    }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(this, 27, LegacyComponentSerializer.legacyAmpersand().deserialize("&8&lKategorie Sklepu"));
        setInventory(inv);

        for (ShopCategory cat : manager.getCategories()) {
            ItemStack item = new ItemStack(cat.getIcon());
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize("&a&l" + cat.getName()));
                item.setItemMeta(meta);
            }
            inv.setItem(cat.getSlot(), item);
            slotMap.put(cat.getSlot(), cat);
        }

        player.openInventory(inv);
    }

    public ShopCategory getCategoryAt(int slot) {
        return slotMap.get(slot);
    }
}
