package gg.elcartel.paper.board;

import org.bukkit.entity.Player;

public class LuckPermsHook {
    public static String getRank(Player player) {
        try {
            net.luckperms.api.model.user.User user = net.luckperms.api.LuckPermsProvider.get().getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                String rank = user.getPrimaryGroup();
                if (rank != null && !rank.isEmpty() && !rank.equalsIgnoreCase("default")) {
                    return rank.substring(0, 1).toUpperCase() + rank.substring(1);
                }
            }
        } catch (Throwable ignored) {
        }
        return "Brak";
    }
}
