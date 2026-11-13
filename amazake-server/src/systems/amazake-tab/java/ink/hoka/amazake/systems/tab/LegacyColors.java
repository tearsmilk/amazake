package ink.hoka.amazake.systems.tab;

/**
 * Turns legacy colour codes ({@code §a}, {@code &l}, {@code &#rrggbb}, {@code §x§r§r§g§g§b§b}) into MiniMessage tags,
 * so text from Vault prefixes and PlaceholderAPI can be mixed into MiniMessage templates. Pure.
 */
public final class LegacyColors {

    private static final String[] TAGS = new String[128];

    static {
        final String[][] map = {
            {"0", "black"}, {"1", "dark_blue"}, {"2", "dark_green"}, {"3", "dark_aqua"}, {"4", "dark_red"},
            {"5", "dark_purple"}, {"6", "gold"}, {"7", "gray"}, {"8", "dark_gray"}, {"9", "blue"}, {"a", "green"},
            {"b", "aqua"}, {"c", "red"}, {"d", "light_purple"}, {"e", "yellow"}, {"f", "white"},
            {"k", "obfuscated"}, {"l", "bold"}, {"m", "strikethrough"}, {"n", "underlined"}, {"o", "italic"},
            {"r", "reset"},
        };
        for (final String[] m : map) {
            final char c = m[0].charAt(0);
            TAGS[c] = "<" + m[1] + ">";
            TAGS[Character.toUpperCase(c)] = "<" + m[1] + ">";
        }
    }

    private LegacyColors() {
    }

    /** Converts {@code §} codes, and also {@code &} codes when {@code ampersand} is true. */
    public static String toMiniMessage(final String text, final boolean ampersand) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.indexOf('§') < 0 && (!ampersand || text.indexOf('&') < 0)) {
            return text;
        }
        final StringBuilder out = new StringBuilder(text.length() + 16);
        int i = 0;
        while (i < text.length()) {
            final char c = text.charAt(i);
            final boolean marker = c == '§' || (ampersand && c == '&');
            if (marker && i + 1 < text.length()) {
                final char code = text.charAt(i + 1);
                // §x§r§r§g§g§b§b (Bukkit hex)
                if ((code == 'x' || code == 'X') && isBukkitHex(text, i, c)) {
                    out.append("<#");
                    for (int k = 0; k < 6; k++) {
                        out.append(Character.toLowerCase(text.charAt(i + 3 + k * 2)));
                    }
                    out.append('>');
                    i += 14;
                    continue;
                }
                // &#rrggbb
                if (code == '#' && i + 8 <= text.length() && isHex(text, i + 2, 6)) {
                    out.append("<#").append(text, i + 2, i + 8).append('>');
                    i += 8;
                    continue;
                }
                if (code < 128 && TAGS[code] != null) {
                    out.append(TAGS[code]);
                    i += 2;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isBukkitHex(final String text, final int start, final char marker) {
        if (start + 14 > text.length()) {
            return false;
        }
        for (int k = 0; k < 6; k++) {
            final int at = start + 2 + k * 2;
            if (text.charAt(at) != marker || Character.digit(text.charAt(at + 1), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(final String text, final int from, final int count) {
        for (int k = from; k < from + count; k++) {
            if (Character.digit(text.charAt(k), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
