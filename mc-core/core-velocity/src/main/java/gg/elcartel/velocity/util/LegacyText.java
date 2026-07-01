package gg.elcartel.velocity.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.EnumSet;
import java.util.Set;

/** Translator kodow '&' (legacy) na komponent Adventure. Zaleznosc: tylko adventure-api. */
public final class LegacyText {

    private LegacyText() {
    }

    public static Component legacy(String s) {
        TextComponent.Builder root = Component.text();
        TextColor color = null;
        Set<TextDecoration> decos = EnumSet.noneOf(TextDecoration.class);
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '&' || c == '\u00A7') && i + 1 < s.length()) {
                char code = Character.toLowerCase(s.charAt(i + 1));
                TextColor col = color(code);
                TextDecoration dec = deco(code);
                if (col != null || dec != null || code == 'r') {
                    flush(root, buf, color, decos);
                    if (code == 'r') {
                        color = null;
                        decos.clear();
                    } else if (col != null) {
                        color = col;
                        decos.clear();
                    } else {
                        decos.add(dec);
                    }
                    i++;
                    continue;
                }
            }
            buf.append(c);
        }
        flush(root, buf, color, decos);
        return root.build();
    }

    private static void flush(TextComponent.Builder root, StringBuilder buf, TextColor color, Set<TextDecoration> decos) {
        if (buf.length() == 0) {
            return;
        }
        TextComponent.Builder child = Component.text().content(buf.toString());
        if (color != null) {
            child.color(color);
        }
        for (TextDecoration d : decos) {
            child.decorate(d);
        }
        root.append(child.build());
        buf.setLength(0);
    }

    private static TextColor color(char c) {
        switch (c) {
            case '0': return NamedTextColor.BLACK;
            case '1': return NamedTextColor.DARK_BLUE;
            case '2': return NamedTextColor.DARK_GREEN;
            case '3': return NamedTextColor.DARK_AQUA;
            case '4': return NamedTextColor.DARK_RED;
            case '5': return NamedTextColor.DARK_PURPLE;
            case '6': return NamedTextColor.GOLD;
            case '7': return NamedTextColor.GRAY;
            case '8': return NamedTextColor.DARK_GRAY;
            case '9': return NamedTextColor.BLUE;
            case 'a': return NamedTextColor.GREEN;
            case 'b': return NamedTextColor.AQUA;
            case 'c': return NamedTextColor.RED;
            case 'd': return NamedTextColor.LIGHT_PURPLE;
            case 'e': return NamedTextColor.YELLOW;
            case 'f': return NamedTextColor.WHITE;
            default: return null;
        }
    }

    private static TextDecoration deco(char c) {
        switch (c) {
            case 'l': return TextDecoration.BOLD;
            case 'o': return TextDecoration.ITALIC;
            case 'n': return TextDecoration.UNDERLINED;
            case 'm': return TextDecoration.STRIKETHROUGH;
            case 'k': return TextDecoration.OBFUSCATED;
            default: return null;
        }
    }
}
