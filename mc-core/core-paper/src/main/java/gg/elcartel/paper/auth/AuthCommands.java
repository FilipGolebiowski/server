package gg.elcartel.paper.auth;

import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Komendy auth: /register, /login, /otp (alias /2fa). Tylko dla graczy. */
public final class AuthCommands implements org.bukkit.command.TabExecutor {

    private final AuthGate gate;

    public AuthCommands(AuthGate gate) {
        this.gate = gate;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Tylko dla graczy."));
            return true;
        }
        switch (command.getName().toLowerCase()) {
            case "register" -> gate.handleRegister(player, args);
            case "login" -> gate.handleLogin(player, args);
            case "otp" -> gate.handle2fa(player, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public java.util.List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            switch (command.getName().toLowerCase()) {
                case "register": return java.util.List.of("<haslo>");
                case "login": return java.util.List.of("<haslo>");
                case "otp": return java.util.List.of("<kod_z_aplikacji>");
            }
        } else if (args.length == 2 && command.getName().toLowerCase().equals("register")) {
            return java.util.List.of("<powtorz_haslo>");
        }
        return java.util.List.of();
    }
}
