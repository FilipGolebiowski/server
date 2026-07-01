package gg.elcartel.paper.eco;

import gg.elcartel.paper.CorePaperPlugin;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class EconomyWatcher implements Runnable {

    private final CorePaperPlugin plugin;
    private Economy economy;
    private final boolean enabled;
    private final double limit1m;
    private final double limit5m;
    private final boolean logToConsole;

    // UUID -> array[last check balance, 1 min ago balance, 5 min ago balance]
    private final Map<UUID, BalanceHistory> history = new ConcurrentHashMap<>();
    
    private int ticks = 0;
    
    public EconomyWatcher(CorePaperPlugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("security.economy_guard.enabled", true);
        this.limit1m = plugin.getConfig().getDouble("security.economy_guard.limit_1m", 100000.0);
        this.limit5m = plugin.getConfig().getDouble("security.economy_guard.limit_5m", 1000000.0);
        this.logToConsole = plugin.getConfig().getBoolean("security.economy_guard.log_to_console", true);

        if (!enabled) return;

        RegisteredServiceProvider<Economy> rsp = Bukkit.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            this.economy = rsp.getProvider();
        } else {
            plugin.getLogger().warning("Vault Economy nie zostalo odnalezione! EconomyWatcher zostanie wylaczony.");
        }
    }

    public void init() {
        if (!enabled || economy == null) return;
        long intervalSec = plugin.getConfig().getLong("security.economy_guard.interval_seconds", 10L);
        // Uruchomienie co zadany interwal w osobnym watku (asynchronicznie, by nie obciazac tickow)
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this, 20L * intervalSec, 20L * intervalSec);
    }

    @Override
    public void run() {
        ticks++;
        boolean shift1m = (ticks % (60 / getInterval())) == 0;
        boolean shift5m = (ticks % (300 / getInterval())) == 0;

        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            double currentBal = economy.getBalance(p);

            BalanceHistory h = history.computeIfAbsent(id, k -> new BalanceHistory(currentBal));

            // Obliczenie roznic
            double diff1m = currentBal - h.bal1m;
            double diff5m = currentBal - h.bal5m;

            if (diff5m > limit5m) {
                alert(p, "5 minut", diff5m, limit5m);
                h.bal5m = currentBal;
                h.bal1m = currentBal; // Zeby nie dostac kolejnego powiadomienia za 1m
            } else if (diff1m > limit1m) {
                alert(p, "1 minute", diff1m, limit1m);
                // Reset limitu by nie spamowac caly czas
                h.bal1m = currentBal;
            }

            if (shift1m) h.bal1m = h.lastBal;
            if (shift5m) h.bal5m = h.lastBal;
            
            h.lastBal = currentBal;
        }
        
        // Czyszczenie graczy, ktorzy wyszli
        history.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private void alert(Player p, String timeFrame, double gained, double limit) {
        String msg = "§4[SECURITY] §cGracz §e" + p.getName() + " §czarobil §a$" + String.format("%.2f", gained) + " §cw " + timeFrame + " (Limit: $" + limit + ")!";
        if (logToConsole) {
            plugin.getLogger().severe("ECONOMY-GUARD: Gracz " + p.getName() + " zarobil $" + gained + " w czasie " + timeFrame + "!");
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player admin : Bukkit.getOnlinePlayers()) {
                if (admin.hasPermission(gg.elcartel.common.CoreConstants.PERM_ADMIN)) {
                    admin.sendMessage(msg);
                    admin.playSound(admin.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
                }
            }
        });
    }

    private int getInterval() {
        return Math.max(1, plugin.getConfig().getInt("security.economy_guard.interval_seconds", 10));
    }

    private static class BalanceHistory {
        double lastBal;
        double bal1m;
        double bal5m;

        BalanceHistory(double initialBal) {
            this.lastBal = initialBal;
            this.bal1m = initialBal;
            this.bal5m = initialBal;
        }
    }
}
