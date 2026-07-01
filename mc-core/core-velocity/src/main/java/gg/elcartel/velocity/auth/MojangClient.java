package gg.elcartel.velocity.auth;

import gg.elcartel.data.CoreData;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Wykrywanie kont premium przez API Mojanga, z cache w Redis pod kluczem
 * premium:name:<nick> (limbo czyta ten sam klucz).
 *  - 200 + "id" -> premium (cache 6 h),
 *  - 404 / 204  -> nie premium (cache 10 min),
 *  - inny blad (403 / 5xx / timeout) -> BEZ cache, false (sciezka cracked, ponowi nastepnym razem).
 *
 * Uzywamy nowoczesnego endpointu minecraftservices - stary api.mojang.com/users/...
 * bywa wylaczany i zwraca losowe 403.
 */
public final class MojangClient {

    private static final String API = "https://api.minecraftservices.com/minecraft/profile/lookup/name/";
    private static final int CACHE_OK_SEC = 21600; // 6 h
    private static final int CACHE_NO_SEC = 600;   // 10 min

    private final CoreData data;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .build();

    public MojangClient(CoreData data) {
        this.data = data;
    }

    public boolean isPremium(String name) {
        String key = "premium:name:" + name.toLowerCase();
        String cached = data.source().redis().get(key);
        if ("1".equals(cached)) {
            return true;
        }
        if ("0".equals(cached)) {
            return false;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(API + name))
                .timeout(Duration.ofSeconds(4))
                .GET()
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code == 200 && resp.body() != null && resp.body().contains("\"id\"")) {
                data.source().redis().setex(key, CACHE_OK_SEC, "1");
                return true;
            }
            if (code == 404 || code == 204) {
                data.source().redis().setex(key, CACHE_NO_SEC, "0"); // nick nie istnieje = nie premium
                return false;
            }
            return false; // blad przejsciowy - nie cache'ujemy
        } catch (Exception e) {
            return false;
        }
    }
}
