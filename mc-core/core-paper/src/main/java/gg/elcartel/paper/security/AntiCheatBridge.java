package gg.elcartel.paper.security;

import gg.elcartel.paper.CorePaperPlugin;

import gg.elcartel.data.CoreData;
import gg.elcartel.data.model.PlayerAccount;
import gg.elcartel.data.model.Punishment;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import java.util.Arrays;
import java.util.UUID;
import gg.elcartel.paper.util.LegacyText;

public class AntiCheatBridge implements CommandExecutor, Listener {

    private final CorePaperPlugin plugin;
    private final CoreData data;

    public AntiCheatBridge(CorePaperPlugin plugin, CoreData data) {
        this.plugin = plugin;
        this.data = data;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Zabezpieczenie przed graczami. Tylko konsola (AntiCheat) moze uzywac tej komendy
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            sender.sendMessage(LegacyText.legacy("&cKomenda zarezerwowana dla silnika AntiCheat."));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("Uzycie: /ac-punish <gracz> <System> [powod]");
            return true;
        }

        String targetName = args[0];
        String systemName = args[1]; // np. Vulcan, GrimAC
        
        StringBuilder reasonBuilder = new StringBuilder("Niedozwolone modyfikacje (" + systemName + ")");
        if (args.length > 2) {
            reasonBuilder.append(" - ").append(String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
        }
        String reason = reasonBuilder.toString();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID targetUuid = null;
            Player onlinePlayer = Bukkit.getPlayerExact(targetName);
            
            if (onlinePlayer != null) {
                targetUuid = onlinePlayer.getUniqueId();
            } else {
                PlayerAccount account = data.accounts().findByNameLower(targetName);
                if (account != null) {
                    targetUuid = account.getId();
                }
            }

            if (targetUuid == null) {
                plugin.getLogger().warning("[AntiCheatBridge] Nie znaleziono gracza o nicku: " + targetName);
                return;
            }

            // Ban na 14 dni
            long durationMs = 14L * 24L * 60L * 60L * 1000L;
            long expiresAt = System.currentTimeMillis() + durationMs;

            Punishment p = new Punishment();
            p.setUuid(targetUuid);
            p.setType("BAN");
            p.setScope(Punishment.NETWORK); // Globalny ban na cala siec
            p.setReason(reason);
            p.setByName("AntiCheat");
            p.setCreatedAt(System.currentTimeMillis());
            p.setExpiresAt(expiresAt);
            p.setActive(true);

            // Zapisz kare w globalnej bazie danych
            data.punishments().add(p);
            plugin.getLogger().info("[AntiCheatBridge] Pomyslnie zbanowano gracza: " + targetName + " przez " + systemName);

            // Jesli gracz gra na tej instancji serwera, wykop go natychmiast
            if (onlinePlayer != null && onlinePlayer.isOnline()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    onlinePlayer.kickPlayer("Zostales zablokowany przez system zabezpieczen (" + systemName + ")\nSkontaktuj sie z administracja.");
                });
            }

            // Opublikuj informacje na Redis do serwerow Proxy, zeby wykopaly go jezeli jest podlaczony z innego serwera (np lobby)
            // Zakladamy ze KickGuard na Proxy czyta baze, ale wystarczy mu poslac /kick
            // Z proxy mozemy uzyc wiadomosci "core:punish" na ktore reaguje Proxy
            data.messenger().publish("core:punish", targetUuid.toString());
        });

        return true;
    }
}
