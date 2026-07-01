package gg.elcartel.paper.security;

import gg.elcartel.paper.CorePaperPlugin;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import gg.elcartel.paper.util.LegacyText;

public final class AntiSpamListener implements Listener {

    private final CorePaperPlugin plugin;

    public AntiSpamListener(CorePaperPlugin plugin) {
        this.plugin = plugin;
    }

    private long getChatCooldown() {
        return plugin.getConfig().getLong("security.anti_spam.chat_cooldown_ms", 1500L);
    }

    private long getCommandCooldown() {
        return plugin.getConfig().getLong("security.anti_spam.command_cooldown_ms", 1000L);
    }

    private final Map<UUID, Long> lastChat = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCommand = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(gg.elcartel.common.CoreConstants.PERM_BYPASS_SPAM)) return;

        long now = System.currentTimeMillis();
        long last = lastChat.getOrDefault(player.getUniqueId(), 0L);

        if (now - last < getChatCooldown()) {
            event.setCancelled(true);
            player.sendMessage(LegacyText.legacy("&cNie spamuj na czacie! Odczekaj chwile."));
            return;
        }
        lastChat.put(player.getUniqueId(), now);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(gg.elcartel.common.CoreConstants.PERM_BYPASS_SPAM)) return;

        long now = System.currentTimeMillis();
        long last = lastCommand.getOrDefault(player.getUniqueId(), 0L);

        if (now - last < getCommandCooldown()) {
            event.setCancelled(true);
            player.sendMessage(LegacyText.legacy("&cNie spamuj komendami! Odczekaj chwile."));
            return;
        }
        lastCommand.put(player.getUniqueId(), now);
    }
}
