package gg.elcartel.paper.util;

import org.bukkit.inventory.ItemStack;

/**
 * Serializacja ekwipunku do/ze surowych bajtow przez natywne NBT Papera
 * (ItemStack.serializeItemsAsBytes / deserializeItemsFromBytes) - odporne na wersje,
 * obsluguje puste sloty (null). Wymaga Paper 1.20.5+.
 */
public final class InventorySerializer {

    private InventorySerializer() {
    }

    public static byte[] toBytes(ItemStack[] items) {
        if (items == null || items.length == 0) {
            return new byte[0];
        }
        return ItemStack.serializeItemsAsBytes(items);
    }

    public static ItemStack[] fromBytes(byte[] data) {
        if (data == null || data.length == 0) {
            return new ItemStack[0];
        }
        return ItemStack.deserializeItemsFromBytes(data);
    }
}
