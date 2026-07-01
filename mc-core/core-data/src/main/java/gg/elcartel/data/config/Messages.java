package gg.elcartel.data.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Konfigurowalne teksty kar (i innych komunikatow). Ladowane z messages.properties
 * (te same sciezki co Config, ale plik messages.properties); brakujace klucze maja
 * wbudowane domyslne wartosci, wiec dziala nawet bez pliku.
 *
 * Format: kody kolorow '&' (np. &c) + placeholdery {player} {scope} {reason} {duration} {by}.
 * Zamiana '&' -> kolor i budowa komponentu dzieje sie po stronie pluginu (Velocity/Paper).
 */
public final class Messages {

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        // --- BAN ---
        DEFAULTS.put("ban.screen",
            "&c&lEL CARTEL &8- &cBAN\n\n&7Zakres: &f{scope}\n&7Powod: &f{reason}\n&7Wygasa: &f{duration}\n&7Nadal: &f{by}");
        DEFAULTS.put("ban.deny",
            "&cMasz bana na tryb &f{scope}&c. Wygasa: &f{duration}&c. Powod: &f{reason}");
        DEFAULTS.put("ban.broadcast",
            "&8[&cKARY&8] &f{player} &7zostal zbanowany &8(&7{scope}&8) &7przez &f{by}&7. Powod: &f{reason} &8[{duration}]");
        // --- MUTE ---
        DEFAULTS.put("mute.notify",
            "&cZostales wyciszony &8(&7{scope}&8)&c. Wygasa: &f{duration}&c. Powod: &f{reason}");
        DEFAULTS.put("mute.deny",
            "&cMasz wyciszenie &8(&7{scope}&8)&c, nie mozesz pisac. Wygasa: &f{duration}&c. Powod: &f{reason}");
        DEFAULTS.put("mute.broadcast",
            "&8[&eKARY&8] &f{player} &7zostal wyciszony &8(&7{scope}&8) &7przez &f{by}&7. &8[{duration}]");
        // --- KICK ---
        DEFAULTS.put("kick.screen",
            "&c&lEL CARTEL &8- &cWYRZUCONY\n\n&7Powod: &f{reason}\n&7Przez: &f{by}");
        DEFAULTS.put("kick.broadcast",
            "&8[&eKARY&8] &f{player} &7zostal wyrzucony przez &f{by}&7. Powod: &f{reason}");
        // --- WARN ---
        DEFAULTS.put("warn.notify",
            "&e&lOSTRZEZENIE &7od &f{by}&7: &f{reason}");
        DEFAULTS.put("warn.broadcast",
            "&8[&eKARY&8] &f{player} &7otrzymal ostrzezenie &8(&7{scope}&8)&7: &f{reason}");
        // --- FEEDBACK do admina / uzycie ---
        DEFAULTS.put("cmd.no-perm",      "&cBrak uprawnien &8(&7{perm}&8)&c.");
        DEFAULTS.put("cmd.usage",        "&7Uzyj: &f/{cmd} <gracz> [czas] [powod] [pokaz|cichy]");
        DEFAULTS.put("cmd.target-unknown","&cNie znam gracza &f{player}&c (musi byc online lub wczesniej wejsc).");
        DEFAULTS.put("cmd.no-mode",      "&cNie jestes na trybie. Wejdz na tryb, dodaj &f-t <tryb>&c albo uzyj &f/{cmd}proxy&c (siec).");
        DEFAULTS.put("cmd.ban.ok",       "&aZbanowano &f{player} &8(&7{scope}&8, &7{duration}&8)&a.");
        DEFAULTS.put("cmd.unban.ok",     "&aOdbanowano &f{player} &8(&7{scope}&8)&a.");
        DEFAULTS.put("cmd.unban.none",   "&7Brak aktywnego bana &8(&7{scope}&8)&7.");
        DEFAULTS.put("cmd.mute.ok",      "&aWyciszono &f{player} &8(&7{scope}&8, &7{duration}&8)&a.");
        DEFAULTS.put("cmd.unmute.ok",    "&aOdciszono &f{player} &8(&7{scope}&8)&a.");
        DEFAULTS.put("cmd.unmute.none",  "&7Brak aktywnego wyciszenia &8(&7{scope}&8)&7.");
        DEFAULTS.put("cmd.kick.ok",      "&aWyrzucono &f{player}&a.");
        DEFAULTS.put("cmd.kick.not-here","&7Gracz &f{player} &7nie jest na trybie &f{scope}&7.");
        DEFAULTS.put("cmd.warn.ok",      "&aOstrzezono &f{player} &8(&7{scope}&8)&a.");
        // --- GUI /ch (sektory/kanaly) ---
        DEFAULTS.put("sector.menu.title",   "&8&lSEKTORY");
        DEFAULTS.put("sector.item.name",    "&aKANAL: &f{channel}");
        DEFAULTS.put("sector.item.players", "&7Graczy: &f{players}&7/&f{cap}");
        DEFAULTS.put("sector.item.join",    "&eKliknij, aby dolaczyc");
        DEFAULTS.put("sector.item.current", "&aJestes polaczony z tym kanalem");
        DEFAULTS.put("sector.empty",        "&cBrak aktywnych kanalow");
        DEFAULTS.put("sector.already",      "&7Jestes juz na tym kanale.");
        DEFAULTS.put("sector.switching",    "&7Lacze z &f{channel}&7...");
        DEFAULTS.put("sector.edge",         "&7To skraj swiata - dalej nie ma sektora.");
    }

    private final Properties props = new Properties();
    private Path loadedFrom;

    public Messages() {
        String[] candidates = {
            System.getProperty("elcartel.messages"),
            System.getenv("ELCARTEL_MESSAGES"),
            "config/messages.properties",
            "../config/messages.properties",
            "../../config/messages.properties",
            "messages.properties",
            "../messages.properties",
            "../../messages.properties",
            "plugins/messages.properties"
        };
        for (String c : candidates) {
            if (c == null || c.isEmpty()) {
                continue;
            }
            if (tryLoad(Path.of(c))) {
                break;
            }
        }
    }

    private boolean tryLoad(Path path) {
        try {
            if (Files.isRegularFile(path)) {
                try (InputStream in = Files.newInputStream(path)) {
                    props.load(in);
                    loadedFrom = path.toAbsolutePath();
                    return true;
                }
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    /** Surowy szablon (plik > default > sam klucz). */
    public String raw(String key) {
        return props.getProperty(key, DEFAULTS.getOrDefault(key, key));
    }

    /** Szablon z podstawieniem placeholderow: format("ban.screen", "scope", s, "reason", r, ...). */
    public String format(String key, String... kv) {
        String t = raw(key);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String val = (kv[i + 1] == null || kv[i + 1].isEmpty()) ? "-" : kv[i + 1];
            t = t.replace("{" + kv[i] + "}", val);
        }
        return t;
    }

    public Path loadedFrom() {
        return loadedFrom;
    }
}
