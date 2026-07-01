package gg.elcartel.modes.survival;

import gg.elcartel.modes.survival.shop.ShopManager;
import gg.elcartel.modes.survival.shop.gui.ShopCategoryMenu;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class ShopCommand implements CommandExecutor {

    private final ShopManager shopManager;

    public ShopCommand(ShopManager shopManager) {
        this.shopManager = shopManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Tylko dla graczy.");
            return true;
        }

        if (shopManager.getCategories().isEmpty()) {
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&cSklep jest aktualnie pusty lub wylaczony."));
            return true;
        }

        new ShopCategoryMenu(shopManager).open(player);
        return true;
    }
}
