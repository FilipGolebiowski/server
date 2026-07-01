package gg.elcartel.paper.chat;

import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Egzekwowanie wyciszen na czacie shardu (komunikat z konfigurowalnego mute.deny). */
public final class MuteListener implements Listener {

    private final MuteService service;

    public MuteListener(MuteService service) {
        this.service = service;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        service.onJoin(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.onQuit(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Component deny = service.denyComponent(event.getPlayer().getUniqueId());
        if (deny != null) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(deny);
        }
    }
}
