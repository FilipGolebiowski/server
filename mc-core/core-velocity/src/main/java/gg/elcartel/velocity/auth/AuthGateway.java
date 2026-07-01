package gg.elcartel.velocity.auth;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import gg.elcartel.data.CoreData;
import net.kyori.adventure.text.Component;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Brama auth na proxy:
 *  - PreLogin: anti-bot (rate-limit per IP) + wykrycie premium -> wymuszenie online-mode
 *    (cracked nie podszyje sie pod nick premium),
 *  - kazdy nowy gracz najpierw na LIMBO (tam autoryzacja).
 */
public final class AuthGateway {

    private static final int CONN_LIMIT = 30;       // maks polaczen (zwiekszone z 6 na 30 dla testow)
    private static final int CONN_WINDOW_SEC = 10;  // w oknie sekund

    private final ProxyServer server;
    private final CoreData data;
    private final String limboName;
    private final MojangClient mojang;

    public AuthGateway(ProxyServer server, CoreData data, String limboName) {
        this.server = server;
        this.data = data;
        this.limboName = limboName;
        this.mojang = new MojangClient(data);
    }

    @Subscribe
    public EventTask onPreLogin(PreLoginEvent event) {
        return EventTask.async(() -> {
            String ip = extractIp(event.getConnection().getRemoteAddress());
            if (ip != null && !ip.equals("127.0.0.1") && !data.rateLimiter().allow("conn", ip, CONN_LIMIT, CONN_WINDOW_SEC)) {
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    Component.text("Zbyt wiele polaczen z Twojego IP. Sprobuj za chwile.")));
                return;
            }
            String username = event.getUsername();
            boolean premium = mojang.isPremium(username);
            if (!premium && data.accounts().isPremiumName(username)) {
                premium = true; // trwala rezerwacja w bazie - niezalezna od API Mojanga
            }
            if (premium) {
                event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
            }
            // cracked -> domyslnie dozwolone (offline); autoryzacja realizuje limbo
        });
    }

    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        server.getServer(limboName).ifPresent(event::setInitialServer);
    }

    private static String extractIp(SocketAddress addr) {
        if (addr instanceof InetSocketAddress isa && isa.getAddress() != null) {
            return isa.getAddress().getHostAddress();
        }
        return null;
    }
}
