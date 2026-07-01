package gg.elcartel.velocity.auth;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Stan autoryzacji graczy na proxy (oznaczani po udanym /login na limbo). */
public final class AuthState {

    private final Set<UUID> authed = ConcurrentHashMap.newKeySet();

    public void markAuthed(UUID id) {
        authed.add(id);
    }

    public void clear(UUID id) {
        authed.remove(id);
    }

    public boolean isAuthed(UUID id) {
        return authed.contains(id);
    }
}
