package gg.elcartel.data.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Konfiguracja z pliku properties (sekrety) + fallback na zmienne srodowiskowe.
 * Kolejnosc szukania pliku: -Delcartel.config, ENV ELCARTEL_CONFIG, potem
 * elcartel.properties, ../elcartel.properties, ../../elcartel.properties, plugins/elcartel.properties.
 * Wartosc: najpierw plik, potem ENV, potem podany default.
 */
public final class Config {

    private final Properties props = new Properties();
    private Path loadedFrom;

    public Config() {
        String[] candidates = {
            System.getProperty("elcartel.config"),
            System.getenv("ELCARTEL_CONFIG"),
            "config/elcartel.properties",
            "../config/elcartel.properties",
            "../../config/elcartel.properties",
            "elcartel.properties",
            "../elcartel.properties",
            "../../elcartel.properties",
            "plugins/elcartel.properties"
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
            // pomijamy - sprobujemy kolejnego kandydata
        }
        return false;
    }

    public String get(String key, String def) {
        String v = props.getProperty(key);
        if (v == null || v.isEmpty()) {
            v = System.getenv(key);
        }
        return (v == null || v.isEmpty()) ? def : v;
    }

    public String get(String key) {
        return get(key, null);
    }

    /** Skad zaladowano config (null = z samych ENV). */
    public Path loadedFrom() {
        return loadedFrom;
    }
}
