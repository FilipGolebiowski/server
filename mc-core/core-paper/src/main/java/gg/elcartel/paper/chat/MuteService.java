package gg.elcartel.paper.chat;

import gg.elcartel.common.Durations;
import gg.elcartel.data.CoreData;
import gg.elcartel.data.model.Punishment;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import gg.elcartel.paper.util.LegacyText;

/**
 * Cache wyciszen na shardzie. Wyciszony = aktywny MUTE w zakresie NETWORK albo tego trybu.
 * Czat sprawdza pamiec (szybko), a kanal Redis core:punish aktualizuje stan na zywo.
 * Komunikat dla wyciszonego pochodzi z konfigurowalnego szablonu mute.deny.
 */
public final class MuteService {

    private final Plugin plugin;
    private final CoreData data;
    private final String mode;
    private final ConcurrentHashMap<UUID, Long> expiry = new ConcurrentHashMap<>();   // brak wpisu = nie wyciszony
    private final ConcurrentHashMap<UUID, String> reason = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> scope = new ConcurrentHashMap<>();

    public MuteService(Plugin plugin, CoreData data, String mode) {
        this.plugin = plugin;
        this.data = data;
        this.mode = mode;
    }

    public void start() {
        data.messenger().subscribe("core:punish", (channel, message) -> {
            try {
                UUID id = UUID.fromString(message.trim());
                if (Bukkit.getPlayer(id) != null) {
                    reload(id);
                }
            } catch (IllegalArgumentException ignored) {
            }
        });
    }

    public void onJoin(UUID id) {
        reload(id);
    }

    public void onQuit(UUID id) {
        expiry.remove(id);
        reason.remove(id);
        scope.remove(id);
    }

    private void reload(UUID id) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Punishment m = data.punishments().effectiveMute(id, mode);
            if (m != null) {
                expiry.put(id, m.getExpiresAt());
                reason.put(id, (m.getReason() == null || m.getReason().isEmpty()) ? "-" : m.getReason());
                scope.put(id, m.getScope());
            } else {
                expiry.remove(id);
                reason.remove(id);
                scope.remove(id);
            }
        });
    }

    /** Komunikat (komponent) dla wyciszonego, albo null jesli moze pisac. */
    public Component denyComponent(UUID id) {
        Long exp = expiry.get(id);
        if (exp == null) {
            return null;
        }
        if (exp != 0 && exp <= System.currentTimeMillis()) {
            onQuit(id); // wygaslo -> czysc
            return null;
        }
        return LegacyText.legacy(data.messages().format("mute.deny",
            "scope", scope.getOrDefault(id, "-"),
            "duration", Durations.formatUntil(exp),
            "reason", reason.getOrDefault(id, "-")));
    }
}
