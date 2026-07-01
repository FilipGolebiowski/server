package gg.elcartel.data.auth;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Deterministyczne offline-UUID dla graczy cracked (zgodne ze schematem offline-mode).
 * Premium maja prawdziwe online-UUID z Mojanga; mapowanie trzymamy spojnie w profilu.
 */
public final class OfflineUuid {

    private OfflineUuid() {
    }

    public static UUID of(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
