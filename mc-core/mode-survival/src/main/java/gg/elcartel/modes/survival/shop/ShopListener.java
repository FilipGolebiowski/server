package gg.elcartel.modes.survival.shop;

import gg.elcartel.modes.survival.shop.gui.ShopCategoryMenu;
import gg.elcartel.modes.survival.shop.gui.ShopGUIHolder;
import gg.elcartel.modes.survival.shop.gui.ShopItemsMenu;
import gg.elcartel.modes.survival.shop.gui.ShopTransactionMenu;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public class ShopListener implements Listener {

    private final Economy economy;
    private final java.util.Map<java.util.UUID, Long> lastClick = new java.util.concurrent.ConcurrentHashMap<>();

    public ShopListener(Economy economy) {
        this.economy = economy;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof ShopGUIHolder)) return;

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        long now = System.currentTimeMillis();
        long last = lastClick.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < 200L) { // 200ms cooldown for shop interactions
            return;
        }
        lastClick.put(player.getUniqueId(), now);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return;

        if (holder instanceof ShopCategoryMenu categoryMenu) {
            ShopCategory category = categoryMenu.getCategoryAt(slot);
            if (category != null) {
                new ShopItemsMenu(category).open(player);
            }
        } else if (holder instanceof ShopItemsMenu itemsMenu) {
            ShopItem item = itemsMenu.getItemAt(slot);
            if (item != null) {
                new ShopTransactionMenu(item).open(player);
            }
        } else if (holder instanceof ShopTransactionMenu transactionMenu) {
            ShopItem item = transactionMenu.getItem();
            
            int amount = 0;
            boolean buying = false;

            if (slot == 10) { amount = 1; buying = true; }
            else if (slot == 11) { amount = 16; buying = true; }
            else if (slot == 12) { amount = 64; buying = true; }
            else if (slot == 14) { amount = 1; buying = false; }
            else if (slot == 15) { amount = 16; buying = false; }
            else if (slot == 16) { amount = 64; buying = false; }
            else { return; }

            if (buying) {
                double cost = item.getBuyPrice() * amount;
                if (cost < 0) return;
                
                if (economy.has(player, cost)) {
                    economy.withdrawPlayer(player, cost);
                    player.getInventory().addItem(new ItemStack(item.getMaterial(), amount));
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&aKupiono " + amount + "x " + item.getMaterial().name() + " za " + cost + "$"));
                } else {
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&cNie masz wystarczajaco srodkow!"));
                }
            } else {
                double reward = item.getSellPrice() * amount;
                if (reward < 0) return;

                if (takeItems(player, item.getMaterial(), amount)) {
                    economy.depositPlayer(player, reward);
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&aSprzedano " + amount + "x " + item.getMaterial().name() + " za " + reward + "$"));
                } else {
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&cNie masz tyle przedmiotow w ekwipunku!"));
                }
            }
        }
    }

    private boolean takeItems(Player player, Material material, int amount) {
        int count = 0;
        for (ItemStack is : player.getInventory().getContents()) {
            if (is != null && is.getType() == material) count += is.getAmount();
        }
        if (count < amount) return false;

        int toRemove = amount;
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack is = player.getInventory().getItem(i);
            if (is != null && is.getType() == material) {
                if (is.getAmount() <= toRemove) {
                    toRemove -= is.getAmount();
                    player.getInventory().setItem(i, null);
                } else {
                    is.setAmount(is.getAmount() - toRemove);
                    toRemove = 0;
                }
                if (toRemove <= 0) break;
            }
        }
        return true;
    }
}
