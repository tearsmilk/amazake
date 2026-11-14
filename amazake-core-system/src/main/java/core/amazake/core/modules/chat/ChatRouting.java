package core.amazake.core.modules.chat;

import java.util.Locale;

/**
 * Pure channel-routing logic for the chat module: given the raw text a player typed, the configured default
 * {@link Mode} and the global/local prefix characters, decide whether the message goes to the global or the local
 * channel and strip the forcing prefix from the text. No Bukkit types, so it is fully unit-testable.
 */
public final class ChatRouting {

    private ChatRouting() {
    }

    /** The channel a message is sent to. */
    public enum Channel {
        GLOBAL,
        LOCAL
    }

    /** The server's default chat mode. */
    public enum Mode {
        GLOBAL,
        LOCAL;

        /** Parses {@code "local"} to {@link #LOCAL}, anything else (incl. null) to {@link #GLOBAL}. */
        public static Mode parse(final String raw) {
            return raw != null && raw.trim().toLowerCase(Locale.ROOT).equals("local") ? LOCAL : GLOBAL;
        }
    }

    /**
     * The routing result.
     *
     * @param channel        where the message goes
     * @param message        the text to display (the forcing prefix char, if any, and leading spaces stripped)
     * @param prefixStripped whether a forcing prefix char was found and removed
     */
    public record Decision(Channel channel, String message, boolean prefixStripped) {
    }

    /**
     * Decides the channel for {@code message}.
     *
     * <p>In {@link Mode#LOCAL} every message is local unless it starts with {@code globalPrefix}, which forces it
     * global. In {@link Mode#GLOBAL} every message is global unless {@code localPrefix} is set and the message
     * starts with it, which forces it local. A prefix char of {@code 0} ({@code '\0'}) means "no such prefix".</p>
     *
     * @param message      the raw text the player typed (may be null)
     * @param mode         the default mode
     * @param globalPrefix the char that forces global ({@code 0} = none)
     * @param localPrefix  the char that forces local ({@code 0} = none)
     */
    public static Decision decide(final String message, final Mode mode, final char globalPrefix,
                                  final char localPrefix) {
        final String msg = message == null ? "" : message;
        if (mode == Mode.LOCAL) {
            if (globalPrefix != 0 && startsWith(msg, globalPrefix)) {
                return new Decision(Channel.GLOBAL, strip(msg), true);
            }
            return new Decision(Channel.LOCAL, msg, false);
        }
        if (localPrefix != 0 && startsWith(msg, localPrefix)) {
            return new Decision(Channel.LOCAL, strip(msg), true);
        }
        return new Decision(Channel.GLOBAL, msg, false);
    }

    private static boolean startsWith(final String msg, final char prefix) {
        return !msg.isEmpty() && msg.charAt(0) == prefix;
    }

    /** Removes the single leading prefix char and any whitespace right after it. */
    private static String strip(final String msg) {
        return msg.substring(1).stripLeading();
    }
}
