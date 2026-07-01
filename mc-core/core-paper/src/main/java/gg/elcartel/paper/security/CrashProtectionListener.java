package gg.elcartel.paper.security;

import gg.elcartel.paper.CorePaperPlugin;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import gg.elcartel.paper.util.LegacyText;

public final class CrashProtectionListener implements Listener {

    private final CorePaperPlugin plugin;

    public CrashProtectionListener(CorePaperPlugin plugin) {
        this.plugin = plugin;
    }

    private int getMaxBookPages() {
        return plugin.getConfig().getInt("security.anti_crash.book_max_pages", 50);
    }

    private int getMaxPageLength() {
        return plugin.getConfig().getInt("security.anti_crash.book_max_page_length", 320);
    }

    private int getMaxSignLineLength() {
        return plugin.getConfig().getInt("security.anti_crash.sign_max_line_length", 100);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBookEdit(PlayerEditBookEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(gg.elcartel.common.CoreConstants.PERM_BYPASS_SPAM)) return;

        int pages = event.getNewBookMeta().getPageCount();
        int maxPages = getMaxBookPages();
        if (pages > maxPages) {
            event.setCancelled(true);
            player.sendMessage(LegacyText.legacy("&cTwoja ksiazka ma za duzo stron! (Limit: " + maxPages + ")"));
            return;
        }

        int maxLength = getMaxPageLength();
        for (String page : event.getNewBookMeta().getPages()) {
            if (page.length() > maxLength) {
                event.setCancelled(true);
                player.sendMessage(LegacyText.legacy("&cJedna ze stron Twojej ksiazki zawiera za duzo znakow!"));
                return;
            }
            // Zabezpieczenie przed ogromnymi znakami Unicode zmuszajacymi klienta do renderowania ciezkich czcionek
            if (!isPrintableAsciiOrStandard(page)) {
                event.setCancelled(true);
                player.sendMessage(LegacyText.legacy("&cNiedozwolone znaki w ksiazce!"));
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(gg.elcartel.common.CoreConstants.PERM_BYPASS_SPAM)) return;

        int maxLength = getMaxSignLineLength();
        for (String line : event.getLines()) {
            if (line != null && line.length() > maxLength) {
                event.setCancelled(true);
                player.sendMessage(LegacyText.legacy("&cLinijka na tabliczce jest za dluga!"));
                return;
            }
            if (line != null && !isPrintableAsciiOrStandard(line)) {
                event.setCancelled(true);
                player.sendMessage(LegacyText.legacy("&cNiedozwolone znaki na tabliczce!"));
                return;
            }
        }
    }

    // Pozwala na polskie znaki, podstawowe formatowania i standardowy zestaw. Odfiltrowuje krzaczki zmuszajace pakiety do pęcznienia.
    private boolean isPrintableAsciiOrStandard(String text) {
        for (char c : text.toCharArray()) {
            if (c > 0xFFFD) return false; // Odfiltruj "Private Use Area" lub dziwne znaki z konca tablicy (np. krzaczki book-crasherskie)
            if (c >= 0x2500 && c <= 0x257F) return false; // Box Drawing
        }
        return true;
    }
}
