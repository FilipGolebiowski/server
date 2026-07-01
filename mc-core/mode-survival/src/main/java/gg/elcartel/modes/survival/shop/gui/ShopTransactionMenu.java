package gg.elcartel.modes.survival.shop.gui;

import gg.elcartel.modes.survival.shop.ShopItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;

public class ShopTransactionMenu extends ShopGUIHolder {

    private final ShopItem item;

    public ShopTransactionMenu(ShopItem item) {
        this.item = item;
    }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(this, 27, LegacyComponentSerializer.legacyAmpersand().deserialize("&8&lKup/Sprzedaj"));
        setInventory(inv);

        // Prezentacja przedmiotu na środku
        ItemStack icon = new ItemStack(item.getMaterial());
        ItemMeta iconMeta = icon.getItemMeta();
        if (iconMeta != null) {
            iconMeta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize("&e" + item.getMaterial().name()));
            icon.setItemMeta(iconMeta);
        }
        inv.setItem(13, icon);

        // Opcje kupna
        if (item.getBuyPrice() >= 0) {
            inv.setItem(10, createActionItem(Material.LIME_STAINED_GLASS_PANE, "&aKup x1", "&7Koszt: &a" + (item.getBuyPrice() * 1) + "$"));
            inv.setItem(11, createActionItem(Material.LIME_STAINED_GLASS_PANE, "&aKup x16", "&7Koszt: &a" + (item.getBuyPrice() * 16) + "$"));
            inv.setItem(12, createActionItem(Material.LIME_STAINED_GLASS_PANE, "&aKup x64", "&7Koszt: &a" + (item.getBuyPrice() * 64) + "$"));
        }

        // Opcje sprzedaży
        if (item.getSellPrice() >= 0) {
            inv.setItem(14, createActionItem(Material.RED_STAINED_GLASS_PANE, "&cSprzedaj x1", "&7Zysk: &a" + (item.getSellPrice() * 1) + "$"));
            inv.setItem(15, createActionItem(Material.RED_STAINED_GLASS_PANE, "&cSprzedaj x16", "&7Zysk: &a" + (item.getSellPrice() * 16) + "$"));
            inv.setItem(16, createActionItem(Material.RED_STAINED_GLASS_PANE, "&cSprzedaj x64", "&7Zysk: &a" + (item.getSellPrice() * 64) + "$"));
        }

        player.openInventory(inv);
    }

    private ItemStack createActionItem(Material mat, String name, String loreLine) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(name));
            meta.lore(Arrays.asList(Component.empty(), LegacyComponentSerializer.legacyAmpersand().deserialize(loreLine)));
            item.setItemMeta(meta);
        }
        return item;
    }

    public ShopItem getItem() {
        return item;
    }
}
