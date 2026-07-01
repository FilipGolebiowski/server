package gg.elcartel.data.config;

/** Ustawienia polaczenia z Redis (URI Lettuce, np. redis://:haslo@host:6379/0). */
public final class RedisSettings {

    private final String uri;

    public RedisSettings(String uri) {
        this.uri = uri;
    }

    public String uri() {
        return uri;
    }
}
