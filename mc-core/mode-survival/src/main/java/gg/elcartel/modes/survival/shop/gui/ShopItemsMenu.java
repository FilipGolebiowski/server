package gg.elcartel.modes.survival.shop.gui;

import gg.elcartel.modes.survival.shop.ShopCategory;
import gg.elcartel.modes.survival.shop.ShopItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ShopItemsMenu extends ShopGUIHolder {

    private final ShopCategory category;
    private final Map<Integer, ShopItem> slotMap = new HashMap<>();

    public ShopItemsMenu(ShopCategory category) {
        this.category = category;
    }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(this, 54, LegacyComponentSerializer.legacyAmpersand().deserialize("&8&lKategoria: &a" + category.getName()));
        setInventory(inv);

        int slot = 0;
        for (ShopItem item : category.getItems()) {
            if (slot >= 54) break;
            ItemStack stack = new ItemStack(item.getMaterial());
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                List<Component> lore = new ArrayList<>();
                lore.add(Component.empty());
                if (item.getBuyPrice() >= 0) {
                    lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize("&7Cena kupna: &a" + item.getBuyPrice() + "$ &8/ szt."));
                }
                if (item.getSellPrice() >= 0) {
                    lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize("&7Cena sprzedazy: &c" + item.getSellPrice() + "$ &8/ szt."));
                }
                lore.add(Component.empty());
                lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize("&e&lKliknij &7aby otworzyc menu transakcji!"));
                meta.lore(lore);
                stack.setItemMeta(meta);
            }
            inv.setItem(slot, stack);
            slotMap.put(slot, item);
            slot++;
        }

        player.openInventory(inv);
    }

    public ShopItem getItemAt(int slot) {
        return slotMap.get(slot);
    }
}
