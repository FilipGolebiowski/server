package gg.elcartel.paper.chat;

import gg.elcartel.data.CoreData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChatService {

    private final JavaPlugin plugin;
    private final CoreData data;
    private final String mode;
    private final String channelName;

    public ChatService(JavaPlugin plugin, CoreData data, String mode) {
        this.plugin = plugin;
        this.data = data;
        this.mode = mode;
        this.channelName = "core:chat:" + mode;
    }

    public void start() {
        data.messenger().subscribe(channelName, (ch, msg) -> {
            try {
                Component component = GsonComponentSerializer.gson().deserialize(msg);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.getServer().sendMessage(component);
                });
            } catch (Exception e) {
                plugin.getLogger().warning("Blad deserializacji czatu: " + e.getMessage());
            }
        });
    }

    public void sendCrossServer(Component message) {
        String json = GsonComponentSerializer.gson().serialize(message);
        data.messenger().publish(channelName, json);
    }
}
