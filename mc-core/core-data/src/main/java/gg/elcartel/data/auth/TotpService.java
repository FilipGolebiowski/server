package gg.elcartel.data.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * TOTP (RFC 6238, HmacSHA1, 30 s, 6 cyfr) - wlasna implementacja, bez zewnetrznej zaleznosci.
 * Sekret w base32 (zgodne z aplikacjami typu Google/Microsoft Authenticator).
 * Porownanie kodu stale-czasowe; sekret przechowujemy WYLACZNIE zaszyfrowany (SecretCipher).
 */
public final class TotpService {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int PERIOD = 30;
    private static final int DIGITS = 6;

    private final SecureRandom rng = new SecureRandom();

    /** Nowy sekret (20 bajtow -> 32 znaki base32). */
    public String generateSecret() {
        byte[] buf = new byte[20];
        rng.nextBytes(buf);
        return base32Encode(buf);
    }

    /** Weryfikacja kodu z tolerancja +-window krokow (np. window=1 => +-30 s). */
    public boolean verify(String base32Secret, String code, int window) {
        if (code == null) {
            return false;
        }
        long counter = Instant.now().getEpochSecond() / PERIOD;
        for (long i = -window; i <= window; i++) {
            if (constantTimeEquals(code(base32Secret, counter + i, DIGITS), code)) {
                return true;
            }
        }
        return false;
    }

    /** Kod dla konkretnego licznika (testowalne wg wektorow RFC 6238). */
    public String code(String base32Secret, long counter, int digits) {
        try {
            byte[] key = base32Decode(base32Secret);
            byte[] msg = new byte[8];
            for (int i = 7; i >= 0; i--) {
                msg[i] = (byte) (counter & 0xff);
                counter >>= 8;
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(msg);
            int off = h[h.length - 1] & 0x0f;
            int bin = ((h[off] & 0x7f) << 24)
                | ((h[off + 1] & 0xff) << 16)
                | ((h[off + 2] & 0xff) << 8)
                | (h[off + 3] & 0xff);
            int otp = bin % (int) Math.pow(10, digits);
            return String.format("%0" + digits + "d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("Blad generowania TOTP", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return sb.toString();
    }

    static byte[] base32Decode(String s) {
        String clean = s.trim().replace("=", "").toUpperCase();
        int buffer = 0;
        int bits = 0;
        byte[] out = new byte[clean.length() * 5 / 8];
        int idx = 0;
        for (int i = 0; i < clean.length(); i++) {
            int val = ALPHABET.indexOf(clean.charAt(i));
            if (val < 0) {
                throw new IllegalArgumentException("Niepoprawny znak base32");
            }
            buffer = (buffer << 5) | val;
            bits += 5;
            if (bits >= 8) {
                out[idx++] = (byte) ((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out;
    }
}
