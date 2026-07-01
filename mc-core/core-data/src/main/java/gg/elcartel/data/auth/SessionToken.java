package gg.elcartel.data.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Podpisany token sesji (HMAC-SHA256) z waznoscia. Uzywany m.in. do przenoszenia
 * gracza miedzy proxy (cookie, sekcja 8 BLUEPRINT-kanaly) - NIE niesie danych wrazliwych,
 * tylko uuid + czas waznosci, podpisane kluczem z ENV. Weryfikacja stale-czasowa.
 */
public final class SessionToken {

    private final SecretKeySpec key;

    public SessionToken(byte[] hmacKey) {
        this.key = new SecretKeySpec(hmacKey, "HmacSHA256");
    }

    public String issue(UUID id, long ttlMs) {
        long exp = System.currentTimeMillis() + ttlMs;
        String payload = id.toString() + ":" + exp;
        String p = b64(payload.getBytes(StandardCharsets.UTF_8));
        String sig = b64(hmac(p));
        return p + "." + sig;
    }

    public Optional<UUID> verify(String token) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        String p = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        byte[] expected = hmac(p);
        if (!MessageDigest.isEqual(expected, b64decode(sig))) {
            return Optional.empty();
        }
        String payload = new String(b64decode(p), StandardCharsets.UTF_8);
        int colon = payload.lastIndexOf(':');
        if (colon <= 0) {
            return Optional.empty();
        }
        try {
            long exp = Long.parseLong(payload.substring(colon + 1));
            if (System.currentTimeMillis() > exp) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(payload.substring(0, colon)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC blad", e);
        }
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static byte[] b64decode(String s) {
        return Base64.getUrlDecoder().decode(s);
    }
}
