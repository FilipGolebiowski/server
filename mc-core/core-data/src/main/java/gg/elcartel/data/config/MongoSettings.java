package gg.elcartel.data.config;

/** Ustawienia polaczenia z MongoDB. */
public final class MongoSettings {

    private final String uri;       // np. mongodb://host:27017 lub mongodb+srv://... (klaster sharded: przez mongos)
    private final String database;  // nazwa bazy, np. "elcartel"

    public MongoSettings(String uri, String database) {
        this.uri = uri;
        this.database = database;
    }

    public String uri() {
        return uri;
    }

    public String database() {
        return database;
    }
}
