package gg.elcartel.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsowanie/format czasu kar: "30m","2h","7d","1w","perm". */
public final class Durations {

    private static final Pattern P = Pattern.compile("^(\\d+)([smhdw])$");

    private Durations() {
    }

    /** Zwraca czas trwania w ms; 0 = na stale; -1 = niepoprawny format. */
    public static long parseMs(String s) {
        if (s == null) {
            return -1;
        }
        String t = s.trim().toLowerCase();
        if (t.equals("perm") || t.equals("permanent") || t.equals("0")) {
            return 0;
        }
        Matcher m = P.matcher(t);
        if (!m.matches()) {
            return -1;
        }
        long n = Long.parseLong(m.group(1));
        switch (m.group(2)) {
            case "s": return n * 1000L;
            case "m": return n * 60_000L;
            case "h": return n * 3_600_000L;
            case "d": return n * 86_400_000L;
            case "w": return n * 604_800_000L;
            default: return -1;
        }
    }

    /** Czytelny czas pozostaly do expiresAt (epoch ms; 0 = na zawsze). */
    public static String formatUntil(long expiresAt) {
        if (expiresAt == 0) {
            return "na zawsze";
        }
        long rem = expiresAt - System.currentTimeMillis();
        if (rem <= 0) {
            return "wygasla";
        }
        long d = rem / 86_400_000L; rem %= 86_400_000L;
        long h = rem / 3_600_000L;  rem %= 3_600_000L;
        long mi = rem / 60_000L;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        sb.append(mi).append("m");
        return sb.toString().trim();
    }
}
