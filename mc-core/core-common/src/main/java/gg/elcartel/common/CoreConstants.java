package gg.elcartel.common;

/** Wspolne stale rdzenia sieci. M0 - placeholder, rozbudowa w kolejnych etapach. */
public final class CoreConstants {

    private CoreConstants() {
    }

    /** Nazwa sieci. */
    public static final String NETWORK = "El Cartel";

    /** Wersja rdzenia. */
    public static final String CORE_VERSION = "0.1.0-SNAPSHOT";

    // --- KANAŁY PLUGIN MESSAGE ---
    public static final String CHANNEL_SWITCH = "elcartel:switch";
    public static final String CHANNEL_AUTH = "elcartel:auth";
    public static final String CHANNEL_TP = "elcartel:tp";

    // --- UPRAWNIENIA ---
    public static final String PERM_ADMIN = "elcartel.admin";
    public static final String PERM_ECO_ADMIN = "elcartel.eco.admin";
    public static final String PERM_BYPASS_SPAM = "elcartel.bypass.spam";
}
