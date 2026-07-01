package gg.elcartel.paper.auth;

import gg.elcartel.data.CoreData;
import gg.elcartel.data.auth.PasswordHash;
import gg.elcartel.data.auth.PasswordHasher;
import gg.elcartel.data.auth.SecretCipher;
import gg.elcartel.data.auth.TotpService;
import gg.elcartel.data.model.AuthRecord;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Autoryzacja na limbo. Wrazliwe operacje (Argon2, baza) ZAWSZE asynchronicznie -
 * nigdy na watku glownym (Argon2 ~250-400 ms zamrozilby serwer). NIC nie loguje hasel.
 */
public final class AuthGate {

    enum Stage { PENDING, AWAITING_2FA, AUTHED }

    private static final int MIN_PASS = 6;
    private static final int MAX_PASS = 100;
    private static final int MAX_ATTEMPTS = 5;
    private static final int LOCKOUT_SEC = 300;
    private static final int TIMEOUT_TICKS = 60 * 20; // 60 s

    private final JavaPlugin plugin;
    private final CoreData data;
    private final SecretCipher cipher; // moze byc null jesli 2FA niekonfigurowane
    private final PasswordHasher hasher = new PasswordHasher();
    private final TotpService totp = new TotpService();
    private final Map<UUID, Stage> stage = new ConcurrentHashMap<>();

    public AuthGate(JavaPlugin plugin, CoreData data, SecretCipher cipher) {
        this.plugin = plugin;
        this.data = data;
        this.cipher = cipher;
    }

    public boolean isLocked(UUID id) {
        Stage s = stage.get(id);
        return s != null && s != Stage.AUTHED;
    }

    public void onQuit(UUID id) {
        stage.remove(id);
    }

    public void onJoin(Player player) {
        UUID id = player.getUniqueId();
        String name = player.getName();
        String ip = ipOf(player);
        
        // Ukryj gracza przed innymi i innych przed nim (Limbo mode)
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (!other.equals(player)) {
                player.hidePlayer(plugin, other);
                other.hidePlayer(plugin, player);
            }
        }
        
        async(() -> {
            boolean premium = "1".equals(data.source().redis().get("premium:name:" + name.toLowerCase()));
            AuthRecord rec = premium ? null : data.auth().load(id);
            if (premium) {
                // pierwsze wejscie premium -> zaklep konto w bazie (rezerwacja nicku) + durable cache (30 dni)
                data.accounts().claimPremium(id, name, ip);
                data.source().redis().setex("premium:name:" + name.toLowerCase(), 2592000L, "1");
            }
            sync(() -> {
                if (premium) {
                    plugin.getLogger().info("Auto-login premium: " + player.getName());
                    complete(player);
                    return;
                }
                stage.put(id, Stage.PENDING);
                freeze(player);
                boolean isRegistered = rec != null;
                
                String msg = isRegistered 
                    ? "&aWitaj ponownie! Zaloguj się wpisując: &e/login <haslo>"
                    : "&aWitaj! Zarejestruj się wpisując: &e/register <haslo> <haslo>";
                
                player.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(msg));
                player.sendTitle(
                    org.bukkit.ChatColor.translateAlternateColorCodes('&', isRegistered ? "&6&lLOGOWANIE" : "&6&lREJESTRACJA"),
                    org.bukkit.ChatColor.translateAlternateColorCodes('&', isRegistered ? "&eUżyj komendy /login" : "&eUżyj komendy /register"),
                    10, 200, 20
                );
                
                scheduleTimeout(player);
            });
        });
    }

    public void handleRegister(Player player, String[] args) {
        UUID id = player.getUniqueId();
        if (stage.get(id) == Stage.AUTHED) {
            player.sendMessage(Component.text("Jestes juz zalogowany."));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Uzyj: /register <haslo> <haslo>"));
            return;
        }
        if (!args[0].equals(args[1])) {
            player.sendMessage(Component.text("Hasla nie sa identyczne."));
            return;
        }
        if (args[0].length() < MIN_PASS || args[0].length() > MAX_PASS) {
            player.sendMessage(Component.text("Haslo: " + MIN_PASS + "-" + MAX_PASS + " znakow."));
            return;
        }
        char[] pass = args[0].toCharArray();
        String ip = ipOf(player);
        async(() -> {
            try {
                if (data.accounts().isPremiumName(player.getName())) {
                    sync(() -> player.sendMessage(Component.text("Ten nick nalezy do konta premium - zaloguj sie kontem premium (Microsoft/Mojang).")));
                    return;
                }
                if (data.auth().load(id) != null) {
                    sync(() -> player.sendMessage(Component.text("Konto juz istnieje - uzyj /login.")));
                    return;
                }
                PasswordHash h = hasher.hash(pass);
                AuthRecord rec = new AuthRecord(id);
                rec.setPwSalt(h.salt());
                rec.setPwHash(h.hash());
                rec.setPwMemoryKiB(h.memoryKiB());
                rec.setPwIterations(h.iterations());
                rec.setPwParallelism(h.parallelism());
                long now = System.currentTimeMillis();
                rec.setRegisteredIp(ip);
                rec.setLastLoginIp(ip);
                rec.setCreatedAt(now);
                rec.setLastLoginAt(now);
                data.auth().save(rec);
                sync(() -> complete(player));
            } catch (Exception e) {
                sync(() -> player.sendMessage(Component.text("Blad rejestracji, sprobuj ponownie.")));
            } finally {
                Arrays.fill(pass, '\0');
            }
        });
    }

    public void handleLogin(Player player, String[] args) {
        UUID id = player.getUniqueId();
        if (stage.get(id) == Stage.AUTHED) {
            player.sendMessage(Component.text("Jestes juz zalogowany."));
            return;
        }
        if (args.length < 1) {
            player.sendMessage(Component.text("Uzyj: /login <haslo>"));
            return;
        }
        char[] pass = args[0].toCharArray();
        String ip = ipOf(player);
        String throttleKey = id.toString();
        async(() -> {
            try {
                if (data.loginThrottle().lockedForMs(throttleKey) > 0) {
                    sync(() -> player.sendMessage(Component.text("Zbyt wiele prob. Sprobuj za kilka minut.")));
                    return;
                }
                AuthRecord rec = data.auth().load(id);
                if (rec == null) {
                    sync(() -> player.sendMessage(Component.text("Brak konta - uzyj /register.")));
                    return;
                }
                boolean okPass = hasher.verify(pass, rec.getPwSalt(), rec.getPwHash(),
                    rec.getPwMemoryKiB(), rec.getPwIterations(), rec.getPwParallelism());
                if (!okPass) {
                    data.loginThrottle().recordFailure(throttleKey, MAX_ATTEMPTS, LOCKOUT_SEC);
                    sync(() -> player.sendMessage(Component.text("Niepoprawne haslo.")));
                    return;
                }
                data.loginThrottle().reset(throttleKey);
                rec.setLastLoginIp(ip);
                rec.setLastLoginAt(System.currentTimeMillis());
                data.auth().save(rec);
                if (rec.isTotpEnabled()) {
                    stage.put(id, Stage.AWAITING_2FA);
                    sync(() -> player.sendMessage(Component.text("Podaj kod 2FA: /otp <kod>")));
                } else {
                    sync(() -> complete(player));
                }
            } catch (Exception e) {
                sync(() -> player.sendMessage(Component.text("Blad logowania, sprobuj ponownie.")));
            } finally {
                Arrays.fill(pass, '\0');
            }
        });
    }

    public void handle2fa(Player player, String[] args) {
        UUID id = player.getUniqueId();
        if (stage.get(id) != Stage.AWAITING_2FA) {
            player.sendMessage(Component.text("Najpierw /login."));
            return;
        }
        if (args.length < 1) {
            player.sendMessage(Component.text("Uzyj: /otp <kod>"));
            return;
        }
        if (cipher == null) {
            player.sendMessage(Component.text("2FA niedostepne (brak konfiguracji serwera)."));
            return;
        }
        String code = args[0];
        async(() -> {
            try {
                AuthRecord rec = data.auth().load(id);
                if (rec == null || !rec.isTotpEnabled() || rec.getTotpSecretEnc() == null) {
                    sync(() -> player.sendMessage(Component.text("2FA niewlaczone dla konta.")));
                    return;
                }
                String secret = cipher.decrypt(rec.getTotpSecretEnc());
                boolean ok = totp.verify(secret, code, 1);
                sync(() -> {
                    if (ok) {
                        complete(player);
                    } else {
                        player.sendMessage(Component.text("Niepoprawny kod 2FA."));
                    }
                });
            } catch (Exception e) {
                sync(() -> player.sendMessage(Component.text("Blad weryfikacji 2FA.")));
            }
        });
    }

    /** Po udanej autoryzacji: oznacz i powiadom proxy (core:auth) -> przerzut na hub. */
    private void complete(Player player) {
        UUID id = player.getUniqueId();
        stage.put(id, Stage.AUTHED);
        player.sendMessage(Component.text("Zalogowano. Przenosimy Cie na serwer..."));
        
        try {
            com.google.common.io.ByteArrayDataOutput out = com.google.common.io.ByteStreams.newDataOutput();
            out.writeUTF(id.toString());
            player.sendPluginMessage(plugin, gg.elcartel.common.CoreConstants.CHANNEL_AUTH, out.toByteArray());
        } catch (Exception e) {
            plugin.getLogger().warning("Nie mozna wyslac plugin message elcartel:auth: " + e.getMessage());
        }
    }

    private void freeze(Player player) {
        player.setGameMode(GameMode.ADVENTURE);
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20 * 60 * 60, 0, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 60 * 60, 200, false, false));
    }

    private void scheduleTimeout(Player player) {
        UUID id = player.getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && isLocked(id)) {
                player.kick(Component.text("Czas na logowanie minal."));
            }
        }, TIMEOUT_TICKS);
    }

    private static String ipOf(Player player) {
        InetSocketAddress a = player.getAddress();
        return (a != null && a.getAddress() != null) ? a.getAddress().getHostAddress() : "?";
    }

    private void async(Runnable r) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, r);
    }

    private void sync(Runnable r) {
        plugin.getServer().getScheduler().runTask(plugin, r);
    }
}
