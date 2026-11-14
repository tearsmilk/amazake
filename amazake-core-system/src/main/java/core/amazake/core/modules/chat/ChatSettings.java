package core.amazake.core.modules.chat;

import org.bukkit.configuration.ConfigurationSection;

/**
 * An immutable snapshot of the chat module's config ({@code modules.chat}). Held in an {@code AtomicReference} and
 * replaced whole on config reload so the async chat listener always reads a consistent set of settings.
 *
 * @param mode            default channel (global or local)
 * @param radiusSquared   local radius, squared (blocks^2), compared against distanceSquared
 * @param globalPrefix    char that forces a message global ({@code 0} = none)
 * @param localPrefix     char that forces a message local ({@code 0} = none)
 * @param cooldownMillis  minimum gap between messages in ms ({@code 0} = off)
 * @param formatGlobal    MiniMessage format for global messages
 * @param formatLocal     MiniMessage format for local messages
 * @param cooldownMessage MiniMessage warning shown when a message is throttled ({@code <seconds>} available)
 */
public record ChatSettings(ChatRouting.Mode mode, double radiusSquared, char globalPrefix, char localPrefix,
                           long cooldownMillis, String formatGlobal, String formatLocal, String cooldownMessage) {

    public static final String DEFAULT_GLOBAL_FORMAT =
        "<prefix><white><player></white><suffix> <dark_gray>»</dark_gray> <gray><message></gray>";
    public static final String DEFAULT_LOCAL_FORMAT =
        "<gray>[</gray><green>Л</green><gray>]</gray> <prefix><white><player></white><suffix>"
            + " <dark_gray>»</dark_gray> <gray><message></gray>";
    public static final String DEFAULT_COOLDOWN_MESSAGE =
        "<red>Не так быстро! Подождите ещё <yellow><seconds></yellow> с.</red>";

    /** Reads a snapshot from the module's config section, falling back to the documented defaults. */
    public static ChatSettings from(final ConfigurationSection section) {
        final ChatRouting.Mode mode = ChatRouting.Mode.parse(section.getString("mode", "global"));

        double radius = section.getDouble("local.radius", 100.0D);
        if (radius <= 0.0D) {
            radius = 100.0D;
        }

        final char globalPrefix = firstChar(section.getString("global-prefix", "!"));
        final char localPrefix = firstChar(section.getString("local-prefix", ""));

        double cooldownSeconds = section.getDouble("cooldown-seconds", 0.0D);
        if (cooldownSeconds < 0.0D) {
            cooldownSeconds = 0.0D;
        }
        final long cooldownMillis = Math.round(cooldownSeconds * 1000.0D);

        final String formatGlobal = section.getString("format.global", DEFAULT_GLOBAL_FORMAT);
        final String formatLocal = section.getString("format.local", DEFAULT_LOCAL_FORMAT);
        final String cooldownMessage = section.getString("cooldown-message", DEFAULT_COOLDOWN_MESSAGE);

        return new ChatSettings(mode, radius * radius, globalPrefix, localPrefix, cooldownMillis,
            formatGlobal, formatLocal, cooldownMessage);
    }

    /** The first char of a config string, or {@code 0} when empty/null (meaning "no prefix"). */
    static char firstChar(final String raw) {
        return raw == null || raw.isEmpty() ? (char) 0 : raw.charAt(0);
    }
}
