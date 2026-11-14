package core.amazake.core.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** MiniMessage helpers shared by the modules. */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {
    }

    public static MiniMessage mm() {
        return MM;
    }

    public static Component parse(final String miniMessage) {
        return MM.deserialize(miniMessage == null ? "" : miniMessage);
    }

    public static Component parse(final String miniMessage, final TagResolver... resolvers) {
        return MM.deserialize(miniMessage == null ? "" : miniMessage, resolvers);
    }

    /** A resolver for {@code <key>} -> literal text (the value cannot inject its own tags). */
    public static TagResolver var(final String key, final String value) {
        return Placeholder.unparsed(key, value == null ? "" : value);
    }

    /** A resolver for {@code <key>} -> an already-built component. */
    public static TagResolver var(final String key, final Component value) {
        return Placeholder.component(key, value == null ? Component.empty() : value);
    }

    /** Strips any tags a player typed, so user input never becomes MiniMessage markup. */
    public static String escape(final String raw) {
        return MM.escapeTags(raw == null ? "" : raw);
    }
}
