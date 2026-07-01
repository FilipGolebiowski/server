package gg.elcartel.paper.eco;

import gg.elcartel.data.CoreData;
import gg.elcartel.data.model.PlayerAccount;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.UUID;

/**
 * Komendy ekonomii PER-TRYB (saldo trybu tego sharda, ELCARTEL_MODE):
 *   /balance [gracz]            - saldo
 *   /pay <gracz> <kwota>        - przelew (w obrebie trybu)
 *   /eco <give|take|set> <gracz> <kwota>  - admin (perm elcartel.eco.admin)
 * Operacje na bazie sa asynchroniczne; komunikaty wracaja na watek glowny.
 */
public final class EcoCommands implements org.bukkit.command.TabExecutor {

    private final JavaPlugin plugin;
    private final CoreData data;
    private final String mode;

    public EcoCommands(JavaPlugin plugin, CoreData data, String mode) {
        this.plugin = plugin;
        this.data = data;
        this.mode = mode;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Tylko dla graczy."));
            return true;
        }
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "balance" -> balance(player, args);
            case "pay" -> pay(player, args);
            case "eco" -> eco(player, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void balance(Player player, String[] args) {
        if (args.length >= 1) {
            String name = args[0];
            async(() -> {
                PlayerAccount acc = data.accounts().findByNameLower(name);
                if (acc == null) { msg(player, "Nie ma gracza " + name + "."); return; }
                double b = data.economy().get(acc.getId(), mode);
                msg(player, "Saldo " + acc.getName() + " (" + mode + "): " + fmt(b));
            });
            return;
        }
        UUID id = player.getUniqueId();
        async(() -> msg(player, "Twoje saldo (" + mode + "): " + fmt(data.economy().get(id, mode))));
    }

    private void pay(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Uzyj: /pay <gracz> <kwota>"));
            return;
        }
        Double amount = parsePositive(args[1]);
        if (amount == null) {
            player.sendMessage(Component.text("Niepoprawna kwota."));
            return;
        }
        String targetName = args[0];
        UUID from = player.getUniqueId();
        async(() -> {
            PlayerAccount target = data.accounts().findByNameLower(targetName);
            if (target == null) { msg(player, "Nie ma gracza " + targetName + "."); return; }
            if (target.getId().equals(from)) { msg(player, "Nie zaplacisz sam sobie."); return; }
            boolean ok = data.economy().transfer(from, target.getId(), mode, amount);
            msg(player, ok
                ? "Wyslano " + fmt(amount) + " do " + target.getName() + " (" + mode + ")."
                : "Za malo srodkow.");
        });
    }

    private void eco(Player player, String[] args) {
        if (!player.hasPermission(gg.elcartel.common.CoreConstants.PERM_ECO_ADMIN)) {
            player.sendMessage(Component.text("Brak uprawnien."));
            return;
        }
        if (args.length < 3) {
            player.sendMessage(Component.text("Uzyj: /eco <give|take|set> <gracz> <kwota>"));
            return;
        }
        String op = args[0].toLowerCase(Locale.ROOT);
        String name = args[1];
        Double amount = parsePositive(args[2]);
        if (amount == null) {
            player.sendMessage(Component.text("Niepoprawna kwota."));
            return;
        }
        async(() -> {
            PlayerAccount acc = data.accounts().findByNameLower(name);
            if (acc == null) { msg(player, "Nie ma gracza " + name + "."); return; }
            switch (op) {
                case "give" -> {
                    data.economy().deposit(acc.getId(), mode, amount);
                    msg(player, "Dodano " + fmt(amount) + " dla " + acc.getName() + " (" + mode + ").");
                }
                case "take" -> {
                    boolean ok = data.economy().withdraw(acc.getId(), mode, amount);
                    msg(player, ok ? "Zabrano " + fmt(amount) + " od " + acc.getName() + "." : "Za malo srodkow u gracza.");
                }
                case "set" -> {
                    data.economy().set(acc.getId(), mode, amount);
                    msg(player, "Ustawiono saldo " + acc.getName() + " na " + fmt(amount) + " (" + mode + ").");
                }
                default -> msg(player, "Uzyj: give|take|set");
            }
        });
    }

    private void async(Runnable r) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, r);
    }

    private void msg(Player player, String text) {
        plugin.getServer().getScheduler().runTask(plugin, () -> player.sendMessage(Component.text(text)));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static Double parsePositive(String s) {
        try {
            double v = Double.parseDouble(s.replace(",", "."));
            return v > 0 ? v : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public java.util.List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("eco")) {
            if (!sender.hasPermission(gg.elcartel.common.CoreConstants.PERM_ECO_ADMIN)) return java.util.List.of();
            if (args.length == 1) return java.util.List.of("give", "take", "set");
            if (args.length == 2) return null; // podpowie graczy z bukkita
            if (args.length == 3) return java.util.List.of("<kwota>");
        } else if (name.equals("pay")) {
            if (args.length == 1) return null; // podpowie graczy z bukkita
            if (args.length == 2) return java.util.List.of("<kwota>");
        } else if (name.equals("balance")) {
            if (args.length == 1) return null; // podpowie graczy
        }
        return java.util.List.of();
    }
}
