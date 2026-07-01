package gg.elcartel.paper.auth;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Zamrozenie i izolacja gracza dopoki nie przejdzie autoryzacji. */
public final class AuthListener implements Listener {

    private final AuthGate gate;

    public AuthListener(AuthGate gate) {
        this.gate = gate;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.setJoinMessage(null);
        gate.onJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.setQuitMessage(null);
        gate.onQuit(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!gate.isLocked(event.getPlayer().getUniqueId())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location keep = from.clone();
            keep.setYaw(to.getYaw());
            keep.setPitch(to.getPitch());
            event.setTo(keep);
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (gate.isLocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("Najpierw uzyj /login lub /register."));
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!gate.isLocked(event.getPlayer().getUniqueId())) {
            return;
        }
        String msg = event.getMessage().toLowerCase();
        if (msg.startsWith("/login") || msg.startsWith("/register")
            || msg.startsWith("/otp") || msg.startsWith("/2fa")) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.text("Najpierw zaloguj sie."));
    }
}
