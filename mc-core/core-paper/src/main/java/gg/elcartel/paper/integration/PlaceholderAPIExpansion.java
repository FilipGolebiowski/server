package gg.elcartel.paper.integration;

import gg.elcartel.data.CoreData;
import gg.elcartel.data.api.CoreApi;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

public class PlaceholderAPIExpansion extends PlaceholderExpansion {

    private final CoreData data;

    public PlaceholderAPIExpansion(CoreData data) {
        this.data = data;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "elcartel";
    }

    @Override
    public @NotNull String getAuthor() {
        return "ElCartel";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true; // Rozszerzenie nie zniknie po reloadzie
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (params.equalsIgnoreCase("global_online")) {
            return String.valueOf(data.shards().getGlobalPlayers());
        }

        if (params.startsWith("online_mode_")) {
            String mode = params.substring("online_mode_".length());
            return String.valueOf(data.shards().getPlayersInMode(mode));
        }
        
        if (params.equalsIgnoreCase("shard_id")) {
            CoreApi api = CoreApi.get();
            return api != null && api.shardId() != null ? api.shardId() : "unknown";
        }

        return null;
    }
}
