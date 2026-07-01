package gg.elcartel.paper.profile;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Spina join/quit gracza z handoffem profilu. */
public final class ProfileListener implements Listener {

    private final ProfileService service;

    public ProfileListener(ProfileService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(org.bukkit.event.player.AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() == org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            service.onPreLogin(event.getUniqueId(), event.getName(), event.getAddress());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        event.setJoinMessage(null);
        service.onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        event.setQuitMessage(null);
        service.onQuit(event.getPlayer());
    }
}
