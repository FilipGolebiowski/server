package gg.elcartel.paper.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import gg.elcartel.paper.util.LegacyText;

public final class ChatListener implements Listener {

    private final ChatService chatService;

    public ChatListener(ChatService chatService) {
        this.chatService = chatService;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        // Ignorujemy, poniewaz nasluchujemy na priority NORMAL (MuteListener to LOW).
        // MuteListener anulowal juz event, jesli gracz ma mute, wiec tu wejdziemy tylko gdy nie jest zmutowany.
        event.setCancelled(true);
        Player player = event.getPlayer();

        String prefixStr = "&7";
        String suffixStr = "";
        try {
            LuckPerms api = LuckPermsProvider.get();
            User user = api.getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                String p = user.getCachedData().getMetaData().getPrefix();
                if (p != null) prefixStr = p;
                String s = user.getCachedData().getMetaData().getSuffix();
                if (s != null) suffixStr = s;
            }
        } catch (Exception ignored) {
        }

        Component prefix = LegacyText.legacy(prefixStr);
        Component suffix = LegacyText.legacy(suffixStr);
        Component name = Component.text(player.getName());
        Component msg = event.message().color(NamedTextColor.WHITE);

        // Zlozenie: <Prefix> <Nick><Suffix>: <Wiadomosc>
        Component finalMessage = prefix.append(name).append(suffix).append(Component.text(": ")).append(msg);

        // Wyslanie asynchronicznie przez pub/sub do wszystkich serwerow w trybie (w tym tego)
        chatService.sendCrossServer(finalMessage);
    }
}
