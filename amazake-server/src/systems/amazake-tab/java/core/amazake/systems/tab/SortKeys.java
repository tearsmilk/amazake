package core.amazake.systems.tab;

import java.util.Locale;

/**
 * Team names that sort the tab list. The client sorts tab entries by list order, spectators last, then by the
 * name of the entry's scoreboard team (plain {@link String#compareTo}), then by the player name. So every player
 * gets their own team named {@code az<priority:3 digits><lower-case name>}: lower priority comes first, players of
 * one group are sorted by name, and the name keeps the team unique (player names are unique ignoring case).
 * Pure.
 */
public final class SortKeys {

    public static final String PREFIX = "az";
    public static final int MAX_PRIORITY = 999;

    private SortKeys() {
    }

    public static int clampPriority(final int priority) {
        return Math.max(0, Math.min(MAX_PRIORITY, priority));
    }

    public static String teamName(final int priority, final String playerName) {
        final int p = clampPriority(priority);
        final StringBuilder sb = new StringBuilder(PREFIX.length() + 3 + playerName.length());
        sb.append(PREFIX);
        if (p < 100) {
            sb.append('0');
        }
        if (p < 10) {
            sb.append('0');
        }
        sb.append(p).append(playerName.toLowerCase(Locale.ROOT));
        return sb.toString();
    }

    /** True for team names made by {@link #teamName}. */
    public static boolean isOurs(final String teamName) {
        if (teamName.length() < PREFIX.length() + 3 || !teamName.startsWith(PREFIX)) {
            return false;
        }
        for (int i = PREFIX.length(); i < PREFIX.length() + 3; i++) {
            final char c = teamName.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
